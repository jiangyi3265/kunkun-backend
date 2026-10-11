param([string]$OutputDirectory, [string]$ResumeFrom, [string]$ContinuationFrom)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$bundledNode = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
$node = if (Test-Path -LiteralPath $bundledNode) { $bundledNode } else { (Get-Command node -ErrorAction Stop).Source }
$version = & $node -p 'process.versions.node'
if ([version]$version -lt [version]'24.19.0') { throw 'Acceptance requires Node 24.19.0 or newer; an older local dev runtime previously exited unexpectedly.' }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks/azul-17.0.19' }
if (-not $env:QA_PYTHON) { $env:QA_PYTHON = (Get-Command python -ErrorAction Stop).Source }
if ($OutputDirectory) { $env:QA_OUTPUT_DIR = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory)) }
if ($ResumeFrom) { $env:QA_RESUME_FROM = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $ResumeFrom)) }
if ($ContinuationFrom) { $env:QA_CONTINUATION_FROM = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $ContinuationFrom)) }
Push-Location -LiteralPath $projectRoot
try {
    & $node (Join-Path $PSScriptRoot 'acceptance.cjs')
    $acceptanceExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $acceptanceExitCode
