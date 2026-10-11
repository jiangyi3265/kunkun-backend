$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$node=Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
if(!(Test-Path -LiteralPath $node)){throw 'Node 24.19 or newer is required'}
if(!$env:JAVA_HOME){$java=Join-Path $env:USERPROFILE '.jdks\azul-17.0.19';if(Test-Path -LiteralPath $java){$env:JAVA_HOME=$java}}
Set-Location -LiteralPath $root
& $node (Join-Path $PSScriptRoot 'accept-player-trade.cjs')
exit $LASTEXITCODE
