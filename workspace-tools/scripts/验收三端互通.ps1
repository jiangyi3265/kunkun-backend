$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot
$taskNode=Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
if(!(Test-Path -LiteralPath $taskNode)){throw 'Node runtime is required'}
if(!$env:JAVA_HOME){$env:JAVA_HOME=Join-Path $env:USERPROFILE '.jdks\azul-17.0.19'}
Set-Location -LiteralPath $taskRoot
& $taskNode (Join-Path $PSScriptRoot 'accept-interconnect.cjs')
exit $LASTEXITCODE
