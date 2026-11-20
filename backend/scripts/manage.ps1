param([Parameter(Mandatory=$true)][ValidateSet('build','start','stop','status')][string]$Action)
$ErrorActionPreference='Stop'
$taskProject=Split-Path -Parent $PSScriptRoot
$taskJavaHome=[Environment]::GetEnvironmentVariable('JAVA_HOME','User')
if(-not $taskJavaHome){$taskJavaHome=[Environment]::GetEnvironmentVariable('JAVA_HOME','Machine')}
if(-not $taskJavaHome -or -not(Test-Path -LiteralPath (Join-Path $taskJavaHome 'bin\javac.exe'))){throw 'A full JDK 21 is required. Check JAVA_HOME.'}
$env:JAVA_HOME=$taskJavaHome
$env:Path=(Join-Path $taskJavaHome 'bin')+';'+$env:Path
$taskJar=Join-Path $taskProject 'target\learnloop-0.0.1-SNAPSHOT.jar'
$taskRun=Join-Path $taskProject 'run'
$taskPidFile=Join-Path $taskRun 'learnloop.pid'
$taskHealth='http://127.0.0.1:8080/actuator/health'
function Get-LearnLoopProcess {
    if(-not(Test-Path -LiteralPath $taskPidFile)){return $null}
    $taskPidText=(Get-Content -LiteralPath $taskPidFile -Raw).Trim()
    if($taskPidText -notmatch '^\d+$'){throw 'The saved process ID is invalid.'}
    $taskProc=Get-CimInstance -ClassName Win32_Process -Filter ('ProcessId = '+$taskPidText) -ErrorAction Stop
    if(-not $taskProc){return $null}
    if($taskProc.Name -ne 'java.exe' -or -not $taskProc.CommandLine -or $taskProc.CommandLine.IndexOf($taskJar,[StringComparison]::OrdinalIgnoreCase) -lt 0){
        throw 'The saved process ID belongs to another application. It will not be stopped.'
    }
    return $taskProc
}
if($Action -eq 'build'){
    if(Get-LearnLoopProcess){throw 'Stop LearnLoop with stop.cmd before rebuilding.'}
    Push-Location -LiteralPath $taskProject
    try {
        & (Join-Path $taskProject 'mvnw.cmd') --batch-mode --no-transfer-progress clean verify
        if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
    }finally{Pop-Location}
    Write-Output ('Built: '+$taskJar)
    exit 0
}
if($Action -eq 'stop'){
    $taskProc=Get-LearnLoopProcess
    if($taskProc){Stop-Process -Id $taskProc.ProcessId -ErrorAction Stop; Wait-Process -Id $taskProc.ProcessId -Timeout 15 -ErrorAction SilentlyContinue}
    if(Test-Path -LiteralPath $taskPidFile){Remove-Item -LiteralPath $taskPidFile}
    Write-Output 'LearnLoop stopped.'
    exit 0
}
if($Action -eq 'status'){
    $taskProc=Get-LearnLoopProcess
    if(-not $taskProc){Write-Output 'LearnLoop is not running.';exit 0}
    $taskResponse=Invoke-RestMethod -Uri $taskHealth -TimeoutSec 5
    [PSCustomObject]@{ProcessId=$taskProc.ProcessId;Status=$taskResponse.status;Health=$taskHealth;Jar=$taskJar} | Format-List
    if($taskResponse.status -ne 'UP'){exit 1}
    exit 0
}
$taskExisting=Get-LearnLoopProcess
if($taskExisting){Write-Output ('LearnLoop is already running. PID: '+$taskExisting.ProcessId);exit 0}
if(-not(Test-Path -LiteralPath $taskJar)){throw 'The application JAR is missing. Run build.cmd first.'}
$taskListeners=[System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
if(@($taskListeners | Where-Object Port -eq 8080).Count -gt 0){throw 'Port 8080 is in use. No existing application was stopped.'}
New-Item -ItemType Directory -Path $taskRun -Force | Out-Null
$taskStdout=Join-Path $taskRun 'stdout.log'
$taskStderr=Join-Path $taskRun 'stderr.log'
$taskArguments='-Xms64m -Xmx512m -jar "'+$taskJar+'"'
$taskOldDb=$env:LEARNLOOP_DB_PASSWORD
$taskOldKey=$env:LEARNLOOP_PARSER_SERVICE_KEY
$taskOldMaster=$env:LEARNLOOP_MODEL_MASTER_KEY
try {
    # 当前用户 DPAPI 解密，仅注入子进程环境；不将口令拼进 java 命令行。
    if(-not $env:LEARNLOOP_DB_PASSWORD){throw 'Set LEARNLOOP_DB_PASSWORD in this terminal before starting LearnLoop.'}
    $taskKeyFile=Join-Path (Split-Path -Parent $taskProject) 'data\private\parser-service-key.credential.xml'
    if(-not $env:LEARNLOOP_PARSER_SERVICE_KEY -and (Test-Path -LiteralPath $taskKeyFile)){
        $env:LEARNLOOP_PARSER_SERVICE_KEY=(Import-Clixml -LiteralPath $taskKeyFile).GetNetworkCredential().Password
    }
    # 生成主密钥本身不启用网页密钥模式；仍须显式 MODEL_SETTINGS_ENABLED=true。
    $taskMasterFile=Join-Path (Split-Path -Parent $taskProject) 'data\private\model-master-key.credential.xml'
    if(-not $env:LEARNLOOP_MODEL_MASTER_KEY -and (Test-Path -LiteralPath $taskMasterFile)){
        $env:LEARNLOOP_MODEL_MASTER_KEY=(Import-Clixml -LiteralPath $taskMasterFile).GetNetworkCredential().Password
    }
    $taskStarted=Start-Process -FilePath (Join-Path $taskJavaHome 'bin\java.exe') -ArgumentList $taskArguments -WorkingDirectory $taskProject -WindowStyle Hidden -RedirectStandardOutput $taskStdout -RedirectStandardError $taskStderr -PassThru
} finally {$env:LEARNLOOP_DB_PASSWORD=$taskOldDb;$env:LEARNLOOP_PARSER_SERVICE_KEY=$taskOldKey;$env:LEARNLOOP_MODEL_MASTER_KEY=$taskOldMaster;Remove-Variable taskOldDb,taskOldKey,taskOldMaster}
$taskStarted.Id | Set-Content -LiteralPath $taskPidFile -Encoding ascii
for($taskTry=0;$taskTry -lt 60;$taskTry++){
    $taskStarted.Refresh()
    if($taskStarted.HasExited){break}
    try{
        $taskResponse=Invoke-RestMethod -Uri $taskHealth -TimeoutSec 2
        if($taskResponse.status -eq 'UP'){
            Write-Output ('LearnLoop started. PID: '+$taskStarted.Id)
            Write-Output ('Health: '+$taskHealth)
            exit 0
        }
    }catch{}
    Start-Sleep -Milliseconds 500
}
Get-Content -LiteralPath $taskStdout -Tail 30 -ErrorAction SilentlyContinue
Get-Content -LiteralPath $taskStderr -Tail 30 -ErrorAction SilentlyContinue
throw 'LearnLoop did not become healthy. Check the run directory logs.'
