param([switch]$LocalOnly,[string]$OutputDirectory)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
Set-Location -LiteralPath $root
if(!$OutputDirectory){$OutputDirectory=Join-Path $root ('test-results/identity-phone-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))}
$out=[IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $out | Out-Null
$env:PLAYER_ACCEPTANCE_OUT=$out
$env:MARKET_CONTENT_OUT=$out
$env:JAVA_HOME='C:/Users/jiangyi/.jdks/azul-17.0.19'
$env:BOOST_TEST_MEMORY_LIMIT='1'
$commands=[Collections.Generic.List[object]]::new()
function Check([string]$name,[scriptblock]$command){
 $start=(Get-Date).ToUniversalTime().ToString('o')
 $ErrorActionPreference='Continue' # Windows PowerShell treats native stderr warnings as ErrorRecords; judge the real exit code.
 & $command *> (Join-Path $out ($name+'.log'))
 $code=$LASTEXITCODE
 $ErrorActionPreference='Stop'
 $commands.Add(@{name=$name;startedAt=$start;finishedAt=(Get-Date).ToUniversalTime().ToString('o');exitCode=$code;command=$command.ToString()})
 if($code -ne 0){throw "$name failed; see $out"}
 Write-Output "PASS $name"
}
try{
 Check 'prepare-mysql' {& C:/Python314/python.exe -X utf8 scripts/prepare-player-test-db.py}
 $qaEnv=Get-Content -LiteralPath .runtime/player-acceptance-20261007/test-db-environment.json -Raw | ConvertFrom-Json
 $qaEnv.PSObject.Properties | ForEach-Object {[Environment]::SetEnvironmentVariable($_.Name,[string]$_.Value,'Process')}
 Check 'backend-all-mysql' {& 'C:/Users/jiangyi/tools/apache-maven-3.9.16/bin/mvn.cmd' -f RuoYi-Vue/pom.xml -pl ruoyi-admin -am package}
 Check 'mini-lint' {node scripts/lint-mini.cjs}
 Check 'admin-lint' {npm.cmd --prefix RuoYi-Vue3 run lint}
 Check 'phone-recovery' {node --experimental-vm-modules scripts/test-phone-recovery.cjs}
 $env:BOOST_WX_APP_ID='wxd31a958f0fc66a19';$env:BOOST_PUBLIC_API_ORIGIN='https://ceshisss.oksja.cn'
 Check 'release-build' {node scripts/build-release.cjs}
 Check 'http-browser-run' {node scripts/accept-identity-phone.cjs}
 $result=@{localStatus='已实测通过';status='环境阻塞';commands=$commands;external=@{sms='环境阻塞';identity='环境阻塞';physicalPhone='未测试'};message='当前不满足所列范围的发布条件。仅本地隔离检查通过；真实短信、实名渠道和真机未验收。';exitCode=$(if($LocalOnly){0}else{2});scope=$(if($LocalOnly){'明确选择本地隔离验收'}else{'完整验收'})}
}catch{$result=@{status='失败';commands=$commands;error=$_.Exception.Message;exitCode=1}}
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $out 'one-command.json') -Encoding utf8
Write-Output $result.message
exit $result.exitCode
