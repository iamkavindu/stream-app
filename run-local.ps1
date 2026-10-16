# Windows PowerShell 5.1 / PowerShell 7. No session-wide environment changes.
$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'scripts/local/run.py'
if (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3 $runner @args
} elseif (Get-Command python -ErrorAction SilentlyContinue) {
    & python $runner @args
} else {
    throw 'Install Python 3.10+ (including the Python launcher), then rerun this command.'
}
exit $LASTEXITCODE
