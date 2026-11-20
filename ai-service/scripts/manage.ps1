#Requires -Version 7.0
param([Parameter(Mandatory)][ValidateSet('start','stop','status')][string]$Action)
$ErrorActionPreference='Stop'
$taskProject=Split-Path -Parent $PSScriptRoot
$taskPython=Join-Path $taskProject '.venv\Scripts\python.exe'
$taskRun=Join-Path $taskProject 'run'
$taskPidFile=Join-Path $taskRun 'parser.pid'
$taskHealth='http://127.0.0.1:8001/health'
function Get-ParserProcess {
    if(-not(Test-Path -LiteralPath $taskPidFile)){return $null}
    $taskPidText=(Get-Content -LiteralPath $taskPidFile -Raw).Trim()
    if($taskPidText -notmatch '^\d+$'){throw 'Invalid parser PID file.'}
    $taskProcess=Get-CimInstance Win32_Process -Filter ('ProcessId='+$taskPidText)
    if(-not $taskProcess){return $null}
    if($taskProcess.Name -ne 'python.exe' -or -not $taskProcess.CommandLine -or
       $taskProcess.CommandLine.IndexOf($taskPython,[StringComparison]::OrdinalIgnoreCase) -lt 0 -or
       $taskProcess.CommandLine -notmatch 'app.main:app') {throw 'PID belongs to another app; no process was stopped.'}
    return $taskProcess
}
if($Action -eq 'stop'){
    $taskProcess=Get-ParserProcess
    if($taskProcess){
        # 精确校验父进程后回收其解析子进程，避免停止服务后遗留计算任务。
        $taskNative=[Diagnostics.Process]::GetProcessById($taskProcess.ProcessId)
        try{$taskNative.Kill($true);$taskNative.WaitForExit(5000)|Out-Null}finally{$taskNative.Dispose()}
    }
    if(Test-Path -LiteralPath $taskPidFile){Remove-Item -LiteralPath $taskPidFile}
    Write-Output 'Parser stopped.';exit 0
}
if($Action -eq 'status'){
    $taskProcess=Get-ParserProcess
    if(-not $taskProcess){Write-Output 'Parser is not running.';exit 0}
    $taskResponse=Invoke-RestMethod $taskHealth -TimeoutSec 3
    [PSCustomObject]@{ProcessId=$taskProcess.ProcessId;Status=$taskResponse.status;ParserVersion=$taskResponse.parserVersion;Health=$taskHealth}
    exit 0
}
if(Get-ParserProcess){Write-Output 'Parser is already running.';exit 0}
if(-not(Test-Path -LiteralPath $taskPython)){throw 'Create ai-service/.venv and install requirements.lock first.'}
if(@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()|Where-Object Port -eq 8001).Count){throw 'Port 8001 is in use. No existing application was stopped.'}
$taskKeyFile=Join-Path (Split-Path -Parent $taskProject) 'data\private\parser-service-key.credential.xml'
if(-not(Test-Path -LiteralPath $taskKeyFile)){throw 'Run scripts/Initialize-LocalSecrets.ps1 first.'}
$taskPrevious=$env:LEARNLOOP_PARSER_SERVICE_KEY
# 模型密钥只在启动子进程前临时解密；命令行参数、日志和源码均不携带它。
# 存在凭据文件不会自动启用出题，必须由当前终端显式设置 GENERATION_ENABLED=true。
$taskPreviousModelKey=$env:LEARNLOOP_LLM_API_KEY
try {
    $env:LEARNLOOP_PARSER_SERVICE_KEY=(Import-Clixml -LiteralPath $taskKeyFile).GetNetworkCredential().Password
    if($env:LEARNLOOP_GENERATION_ENABLED -and $env:LEARNLOOP_GENERATION_ENABLED.Trim().ToLowerInvariant() -eq 'true'){
        $taskModelKeyFile=Join-Path (Split-Path -Parent $taskProject) 'data\private\deepseek-api-key.credential.xml'
        if(-not $env:LEARNLOOP_LLM_API_KEY -and (Test-Path -LiteralPath $taskModelKeyFile)){
            $env:LEARNLOOP_LLM_API_KEY=(Import-Clixml -LiteralPath $taskModelKeyFile).GetNetworkCredential().Password
        }
    }
    New-Item -ItemType Directory -Path $taskRun -Force | Out-Null
    $taskProcess=Start-Process -FilePath $taskPython -ArgumentList '-m uvicorn app.main:app --host 127.0.0.1 --port 8001 --workers 1 --no-access-log' -WorkingDirectory $taskProject -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskRun 'stdout.log') -RedirectStandardError (Join-Path $taskRun 'stderr.log') -PassThru
    $taskProcess.Id | Set-Content -LiteralPath $taskPidFile -Encoding ascii
} finally {
    $env:LEARNLOOP_PARSER_SERVICE_KEY=$taskPrevious
    $env:LEARNLOOP_LLM_API_KEY=$taskPreviousModelKey
    Remove-Variable taskPrevious,taskPreviousModelKey
}
for($taskTry=0;$taskTry -lt 40;$taskTry++){
    $taskProcess.Refresh();if($taskProcess.HasExited){break}
    try{if((Invoke-RestMethod $taskHealth -TimeoutSec 1).status -eq 'UP'){Write-Output ('Parser started. PID: '+$taskProcess.Id);exit 0}}catch{}
    Start-Sleep -Milliseconds 250
}
throw 'Parser startup failed. Inspect ai-service/run logs.'
