param([string]$OutputDirectory)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$node=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
$python=if($env:QA_PYTHON){$env:QA_PYTHON}else{'C:\Python314\python.exe'}
$npmCli=if($env:QA_NPM_CLI){$env:QA_NPM_CLI}else{'D:\nodeJS\node_modules\npm\bin\npm-cli.js'}
if(-not $OutputDirectory){$OutputDirectory='test-results/deployed-check-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')}
$outputRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$env:QA_OUTPUT_DIR=$outputRoot
Remove-Item Env:QA_EXPECT_OLD_RACE -ErrorAction SilentlyContinue
$started=[DateTime]::UtcNow.ToString('o');$checks=[Collections.Generic.List[object]]::new()
function Invoke-Check([string]$name,[string]$exe,[string[]]$arguments,[string]$workingDirectory){
    $begin=[DateTime]::UtcNow.ToString('o');$code=-1
    Push-Location -LiteralPath $workingDirectory
    # Windows PowerShell wraps native stderr as ErrorRecord, including benign
    # Node warnings. Keep that stderr in the log and judge the real process code.
    $previousPreference=$ErrorActionPreference
    try{$ErrorActionPreference='Continue';& $exe @arguments *> (Join-Path $outputRoot ($name+'.log'));$code=$LASTEXITCODE}finally{$ErrorActionPreference=$previousPreference;Pop-Location}
    $checks.Add(@{name=$name;command=$exe+' '+($arguments -join ' ');startedAtUtc=$begin;finishedAtUtc=[DateTime]::UtcNow.ToString('o');exitCode=$code;status=$(if($code -eq 0){'已实测通过'}else{'失败'});log=$name+'.log'})
    Write-Host ($name+': exit '+$code)
}
Invoke-Check 'mini-lint' $node @('scripts/lint-mini.cjs') $projectRoot
Invoke-Check 'compiled-wxss' $node @('scripts/check-wxss.cjs') $projectRoot
Invoke-Check 'session-regression' $node @('--experimental-vm-modules','--test','scripts/test-session-races.cjs') $projectRoot
Invoke-Check 'account-regression' $node @('--experimental-vm-modules','scripts/test-account-ui-races.cjs') $projectRoot
Invoke-Check 'current-secrets' $python @('-X','utf8','scripts/acceptance-secrets.py') $projectRoot
Invoke-Check 'mini-security' $node @($npmCli,'audit','--json','--registry=https://registry.npmjs.org') (Join-Path $projectRoot '代练')
Invoke-Check 'admin-security' $node @($npmCli,'audit','--json','--registry=https://registry.npmjs.org') (Join-Path $projectRoot 'RuoYi-Vue3')
Invoke-Check 'real-server-browser' $node @('scripts/e2e-test-server-login.cjs') $projectRoot
Invoke-Check 'source-final' $python @('-X','utf8','scripts/acceptance-support.py','source') $projectRoot
$failures=@($checks | Where-Object {$_.exitCode -ne 0})
$result=@{startedAtUtc=$started;finishedAtUtc=[DateTime]::UtcNow.ToString('o');scope='Existing restricted nonpayment deployment: current lint/WXSS/VM/known-secret/audit and actual production H5/API/SQL; does not rebuild or migrate/deploy, does not claim device/payment/capacity/legal/ops coverage';checks=$checks.ToArray();passed=($failures.Count -eq 0);releaseStatus='当前不满足所列范围的发布条件';remainingRequired='Full local gate has an unresolved intermittent blank page; real WeChat device and operator/ops acceptance outstanding; any dependency audit failure blocks release';exitCode=$(if($failures.Count -eq 0){0}else{1})}
$result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $outputRoot 'acceptance-result.json') -Encoding UTF8
exit $result.exitCode
