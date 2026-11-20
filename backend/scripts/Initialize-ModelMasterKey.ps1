#Requires -Version 7.0
<# 仅服务器初始化：随机生成 256 位加密主密钥，使用当前 Windows 用户 DPAPI 加密保存。
   它用于加密数据库中的个人 API Key，与用户在网页填写的 DeepSeek Key 是不同的密钥。
   已有文件只验证和保留；脚本不轮换、不显示密钥、不访问模型 API。
#>
$ErrorActionPreference='Stop'
if(-not $IsWindows){throw 'This local helper requires Windows DPAPI.'}
$taskRepository=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskPrivate=Join-Path $taskRepository 'data\private'
$taskMasterFile=Join-Path $taskPrivate 'model-master-key.credential.xml'
if(Test-Path -LiteralPath $taskMasterFile){
    $taskExisting=(Import-Clixml -LiteralPath $taskMasterFile).GetNetworkCredential().Password
    try{if([Convert]::FromBase64String($taskExisting).Length -ne 32){throw 'Invalid existing master key.'}}
    finally{Remove-Variable taskExisting}
    Write-Output 'Existing encrypted model master key retained.'
    exit 0
}
New-Item -ItemType Directory -Path $taskPrivate -Force | Out-Null
$taskMaster=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
try{
    $taskSecure=ConvertTo-SecureString -String $taskMaster -AsPlainText -Force
    [PSCredential]::new('learnloop-model-master',$taskSecure) | Export-Clixml -LiteralPath $taskMasterFile -NoClobber
}finally{Remove-Variable taskMaster,taskSecure -ErrorAction SilentlyContinue}
Write-Output 'Model encryption master key created and encrypted for this Windows user.'
