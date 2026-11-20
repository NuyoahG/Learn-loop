#Requires -Version 7.0
<# 仅首次运行：生成随机服务凭据，用当前 Windows 用户 DPAPI 加密保存。
   不轮换已有凭据，不显示口令，不把凭据写入命令参数、源码或 Git。 #>
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskPrivate=Join-Path $taskRoot 'data\private'
$taskKeyFile=Join-Path $taskPrivate 'parser-service-key.credential.xml'
if(Test-Path -LiteralPath $taskKeyFile){
    $taskExisting=Import-Clixml -LiteralPath $taskKeyFile
    if($taskExisting.GetNetworkCredential().Password.Length -lt 32){throw 'Existing key is invalid; no file was changed.'}
    Write-Output 'Existing encrypted parser key retained.'
    exit 0
}
New-Item -ItemType Directory -Path $taskPrivate -Force | Out-Null
$taskKey=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$taskSecure=ConvertTo-SecureString -String $taskKey -AsPlainText -Force
[PSCredential]::new('learnloop-parser',$taskSecure) | Export-Clixml -LiteralPath $taskKeyFile -NoClobber
Remove-Variable taskKey,taskSecure
Write-Output 'Parser key created and encrypted for this Windows user.'
