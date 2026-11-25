$ErrorActionPreference = 'Stop'
python -X utf8 (Join-Path $PSScriptRoot 'tools\build.py') @args
exit $LASTEXITCODE
