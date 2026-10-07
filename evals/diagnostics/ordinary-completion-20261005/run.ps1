param([string]$OutputPath = "")
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
$classpathFile = Join-Path $repo "stocksage-backend/target/harness-eval-classpath.txt"
if (-not (Test-Path -LiteralPath $classpathFile)) { throw "先生成后端 runtime classpath：$classpathFile" }
$classes = Join-Path $repo "stocksage-backend/target/ordinary-completion-e2e"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$classpath = (Join-Path $repo "stocksage-backend/target/classes") + ";" + (Get-Content -LiteralPath $classpathFile -Raw).Trim()
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { (Get-Command javac).Source }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
function Quote-JavaArgument([string]$Value) { '"' + $Value.Replace('\', '/').Replace('"', '\"') + '"' }
$compileArgs = Join-Path $classes "compile.args"
@('-encoding', 'UTF-8', '-cp', (Quote-JavaArgument $classpath), '-d', (Quote-JavaArgument $classes),
    (Quote-JavaArgument (Join-Path $PSScriptRoot "OrdinaryCompletionReplay.java"))) | Set-Content -LiteralPath $compileArgs -Encoding utf8
& $javac ("@" + $compileArgs)
if ($LASTEXITCODE -ne 0) { throw "端到端驱动编译失败。" }
if (-not $OutputPath) { $OutputPath = Join-Path $repo "target/ordinary-completion-e2e-20261005/result.json" }
$runArgs = Join-Path $classes "run.args"
@('-Dfile.encoding=UTF-8', '-cp', (Quote-JavaArgument ($classes + ";" + $classpath)), 'OrdinaryCompletionReplay',
    (Quote-JavaArgument $repo), (Quote-JavaArgument $OutputPath)) | Set-Content -LiteralPath $runArgs -Encoding utf8
& $java ("@" + $runArgs)
if ($LASTEXITCODE -ne 0) { throw "普通生成完成判定未通过，查看产物：$OutputPath" }
