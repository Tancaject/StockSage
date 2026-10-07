param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$taskRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$classPathFile = Join-Path $taskRoot 'stocksage-backend/target/harness-eval-classpath.txt'
if (-not (Test-Path -LiteralPath $classPathFile)) {
    throw 'Missing runtime classpath. From stocksage-backend run: .\mvnw.cmd -q dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/harness-eval-classpath.txt'
}
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot 'artifacts' }
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$classPath = (Join-Path $taskRoot 'stocksage-backend/target/classes') + ';' + (Get-Content -LiteralPath $classPathFile -Raw).Trim()
$argumentFile = Join-Path $taskRoot 'stocksage-backend/target/deep-report-repair-replay.args'
$javaArguments = @('-Dfile.encoding=UTF-8', '--add-modules', 'jdk.httpserver', '--class-path',
    ('"' + $classPath.Replace('\', '/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'DeepReportRepairReplay.java').Replace('\', '/') + '"'),
    ('"' + $OutputDirectory.Replace('\', '/') + '"'))
[System.IO.File]::WriteAllLines($argumentFile, $javaArguments, [System.Text.UTF8Encoding]::new($false))
$javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
& $javaExecutable ('@' + $argumentFile)
if ($LASTEXITCODE -ne 0) { throw "DEEP report repair replay failed with exit code $LASTEXITCODE" }
