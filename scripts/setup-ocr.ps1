$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $projectRoot '.runtime\python'
$cacheDir = Join-Path $projectRoot '.runtime\pip-cache'
python -m pip install --upgrade --target $runtimeDir --cache-dir $cacheDir -r (Join-Path $PSScriptRoot 'ocr-requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'OCR installation failed' }
