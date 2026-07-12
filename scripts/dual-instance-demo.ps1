param(
    [switch]$ChecklistOnly,
    [switch]$NonInteractive,
    [switch]$ExternalInfrastructure,
    [int]$StartupTimeoutSeconds = 180,
    [int]$MySqlPort = 3306,
    [int]$RedisPort = 6379,
    [int]$InstanceAPort = 8080,
    [int]$InstanceBPort = 8081,
    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$QueueSuffix = "dual-demo"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$backendDirectory = Join-Path $root "stocksage-backend"
$logDirectory = Join-Path $root "tmp\dual-instance"
$taskStream = "stream:research-tasks:$QueueSuffix"
$taskGroup = "research-workers-$QueueSuffix"
$taskDlqStream = "stream:research-tasks-dlq:$QueueSuffix"
$instances = @()

function Assert-DemoInfrastructure {
    if ($ExternalInfrastructure) {
        foreach ($endpoint in @(
            @{ Name = "MySQL"; Port = $MySqlPort },
            @{ Name = "Redis"; Port = $RedisPort }
        )) {
            if (-not (Test-NetConnection localhost -Port $endpoint.Port -InformationLevel Quiet)) {
                throw "$($endpoint.Name) 未监听 localhost:$($endpoint.Port)。"
            }
        }
        return
    }

    $runningServices = @(& docker compose --project-directory $root ps --services --filter status=running)
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose 状态读取失败。请先在仓库根目录启动基础设施：docker compose up -d"
    }

    foreach ($requiredService in @("mysql", "redis")) {
        if ($runningServices -notcontains $requiredService) {
            throw "缺少正在运行的 $requiredService。请先执行：docker compose up -d mysql redis"
        }
    }
}

function Start-BackendInstance {
    param(
        [string]$Name,
        [int]$Port,
        [int]$DatabasePort
    )

    $stdout = Join-Path $logDirectory "$Name.out.log"
    $stderr = Join-Path $logDirectory "$Name.err.log"
    $escapedBackendDirectory = $backendDirectory.Replace("'", "''")
    $command = @"
`$ErrorActionPreference = 'Stop'
`$env:SERVER_PORT = '$Port'
`$env:STOCKSAGE_INSTANCE_ID = '$Name'
`$env:STOCKSAGE_DB_URL = 'jdbc:mysql://localhost:$DatabasePort/stocksage?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
`$env:STOCKSAGE_REDIS_PORT = '$RedisPort'
# 演示环境缩短接管窗口；生产默认仍是 30 分钟租约/claim idle。
`$env:STOCKSAGE_RESEARCH_TASK_LEASE_TTL_MS = '10000'
`$env:STOCKSAGE_RESEARCH_TASK_QUEUE_CLAIM_MIN_IDLE_MS = '12000'
`$env:STOCKSAGE_RESEARCH_TASK_QUEUE_RECLAIM_INTERVAL_MS = '2000'
`$env:STOCKSAGE_RESEARCH_TASK_QUEUE_STREAM = '$taskStream'
`$env:STOCKSAGE_RESEARCH_TASK_QUEUE_GROUP = '$taskGroup'
`$env:STOCKSAGE_RESEARCH_TASK_QUEUE_DLQ_STREAM = '$taskDlqStream'
Set-Location -LiteralPath '$escapedBackendDirectory'
& .\mvnw.cmd -DskipTests spring-boot:run
exit `$LASTEXITCODE
"@
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    # 某些 agent/CI 宿主会同时注入 Path 与 PATH；Windows PowerShell 继承环境时会把它们视为重复键。
    $pathValue = [Environment]::GetEnvironmentVariable("PATH", "Process")
    [Environment]::SetEnvironmentVariable("Path", $null, "Process")
    [Environment]::SetEnvironmentVariable("PATH", $pathValue, "Process")
    $process = Start-Process `
        -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-EncodedCommand", $encodedCommand) `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru

    [pscustomobject]@{
        Name = $Name
        Port = $Port
        Process = $process
        Stdout = $stdout
        Stderr = $stderr
    }
}

function Wait-BackendReady {
    param(
        [string]$Name,
        [int]$Port,
        [int]$TimeoutSeconds
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$Port/actuator/health" -TimeoutSec 3
            if ($response.StatusCode -eq 200) {
                Write-Host "$Name 已就绪：http://localhost:$Port"
                return
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "$Name 在 $TimeoutSeconds 秒内未就绪，请检查 $logDirectory 下的日志。"
}

function Stop-ProcessTree {
    param([int]$RootProcessId)

    $snapshot = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
    $discovered = [Collections.Generic.List[int]]::new()
    $pending = [Collections.Generic.Queue[int]]::new()
    $pending.Enqueue($RootProcessId)
    while ($pending.Count -gt 0) {
        $currentProcessId = $pending.Dequeue()
        $discovered.Add($currentProcessId)
        foreach ($child in @($snapshot | Where-Object { $_.ParentProcessId -eq $currentProcessId })) {
            $pending.Enqueue([int]$child.ProcessId)
        }
    }

    $processIds = $discovered.ToArray()
    [Array]::Reverse($processIds)
    foreach ($processId in $processIds) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    }
}

function Stop-TrackedInstance {
    param([object]$Instance)

    if ($null -eq $Instance -or $null -eq $Instance.Process) {
        return
    }
    $Instance.Process.Refresh()
    if (-not $Instance.Process.HasExited) {
        Write-Host "停止 $($Instance.Name)（进程树根 PID $($Instance.Process.Id)）..."
        Stop-ProcessTree -RootProcessId $Instance.Process.Id
    }
}

function Test-AnyInstanceRunning {
    foreach ($instance in $instances) {
        $instance.Process.Refresh()
        if (-not $instance.Process.HasExited) {
            return $true
        }
    }
    return $false
}

function Show-VerificationChecklist {
    Write-Host @"

=== WS1 双实例人工验证清单 ===

准备认证（把响应中的 token 填入后续 <XSRF_TOKEN>；登录账号需提前注册）：
  curl.exe -sS -c .\tmp\dual-instance\cookies.txt http://localhost:$InstanceAPort/api/auth/csrf
  curl.exe -sS -b .\tmp\dual-instance\cookies.txt -c .\tmp\dual-instance\cookies.txt -H "Content-Type: application/json" -H "X-XSRF-TOKEN: <XSRF_TOKEN>" -d '{"email":"<EMAIL>","password":"<PASSWORD>"}' http://localhost:$InstanceAPort/api/auth/login

1. 同一任务不双跑
   在两个终端同时向 $InstanceAPort、$InstanceBPort 提交完全相同的请求（用首次响应中的 conversationId 替换占位符）：
   curl.exe -N -b .\tmp\dual-instance\cookies.txt -H "Accept: text/event-stream" -H "Content-Type: application/json" -H "X-XSRF-TOKEN: <XSRF_TOKEN>" -d '{"conversationId":<CONVERSATION_ID>,"origin":"chat","message":"请对 AAPL 做完整深度研究","images":[],"replaceLastTurn":false}' http://localhost:$InstanceAPort/api/chat/stream
   curl.exe -N -b .\tmp\dual-instance\cookies.txt -H "Accept: text/event-stream" -H "Content-Type: application/json" -H "X-XSRF-TOKEN: <XSRF_TOKEN>" -d '{"conversationId":<CONVERSATION_ID>,"origin":"chat","message":"请对 AAPL 做完整深度研究","images":[],"replaceLastTurn":false}' http://localhost:$InstanceBPort/api/chat/stream
   观察：两条 SSE 都指向同一 taskId；DB 只有一条活跃任务，attempts 不因重复提交增加。
   docker exec stocksage-mysql mysql -uroot -p12345 stocksage -e "SELECT id,idempotency_key,status,stage,attempts,lease_token FROM research_tasks ORDER BY id DESC LIMIT 5;"

2. 强杀实例后接管
   等 checkpoint 显示 debate_rounds_completed=2 后，在本脚本提示符输入 A 或 B 强停当前执行实例。
   docker exec stocksage-mysql mysql -uroot -p12345 stocksage -e "SELECT task_id,stage_completed,debate_rounds_completed,planned_rounds,updated_at FROM research_task_checkpoints WHERE task_id=<TASK_ID>;"
   docker exec stocksage-redis redis-cli XPENDING $taskStream $taskGroup - + 10
   观察：约 12 秒演示 claim idle + 调度间隔后，pending consumer 改变；任务最终 SUCCEEDED。记录实际接管耗时，不能用配置值代替实测值。

3. 跨实例流式
   保持 SSE 连接在 $InstanceAPort，同时确认实际 worker 日志来自另一个实例：
   Get-Content .\tmp\dual-instance\instance-a.out.log,.\tmp\dual-instance\instance-b.out.log -Wait | Select-String "Research Debate|taskId"
   docker exec stocksage-redis redis-cli XRANGE stream:trace-events:<TRACE_ID> - + COUNT 20
   观察：worker 所在实例持续 XADD；持有 SSE 的另一实例持续收到 thought/section/task-final，entry id 单调递增。

4. 前两轮不重烧
   Get-ChildItem .\tmp\dual-instance\instance-*.out.log | Select-String "Research Debate round started"
   docker exec stocksage-mysql mysql -uroot -p12345 stocksage -e "SELECT status,stage,attempts,result_report_version_id FROM research_tasks WHERE id=<TASK_ID>;"
   观察：接管实例从 round=3 开始，round=1/2 各只出现一次。token 省量必须等 WS2 usage 记录落地后再填，不从日志字数推算。

演示专用参数：lease TTL=10s、claim min idle=12s、reclaim interval=2s；队列隔离为 $taskStream / $taskGroup；生产默认值未被修改。
基础设施模式：$(if ($ExternalInfrastructure) { "外部 localhost MySQL:$MySqlPort / Redis:$RedisPort" } else { "Docker Compose MySQL/Redis" })。
"@
}

if ($ChecklistOnly) {
    Show-VerificationChecklist
    return
}

Assert-DemoInfrastructure
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

try {
    $instances = @(
        Start-BackendInstance -Name "instance-a" -Port $InstanceAPort -DatabasePort $MySqlPort
        Start-BackendInstance -Name "instance-b" -Port $InstanceBPort -DatabasePort $MySqlPort
    )
    foreach ($instance in $instances) {
        Write-Host "$($instance.Name)：PID=$($instance.Process.Id)，stdout=$($instance.Stdout)，stderr=$($instance.Stderr)"
    }
    foreach ($instance in $instances) {
        Wait-BackendReady -Name $instance.Name -Port $instance.Port -TimeoutSeconds $StartupTimeoutSeconds
    }

    Show-VerificationChecklist
    if ($NonInteractive) {
        Write-Host "非交互模式已就绪；请从外部按清单验证并停止两个实例，脚本会在实例全部退出后清理。"
        while (Test-AnyInstanceRunning) {
            Start-Sleep -Seconds 2
        }
    } else {
        while ($true) {
            $action = (Read-Host "输入 A/B 强停对应实例模拟崩溃，L 显示日志路径，Q 停止全部并退出").Trim().ToUpperInvariant()
            switch ($action) {
                "A" { Stop-TrackedInstance -Instance $instances[0] }
                "B" { Stop-TrackedInstance -Instance $instances[1] }
                "L" {
                    foreach ($instance in $instances) {
                        Write-Host "$($instance.Name)：$($instance.Stdout) / $($instance.Stderr)"
                    }
                }
                "Q" { break }
                default { Write-Host "请输入 A、B、L 或 Q。" }
            }
            if ($action -eq "Q") {
                break
            }
        }
    }
} finally {
    foreach ($instance in $instances) {
        Stop-TrackedInstance -Instance $instance
    }
}
