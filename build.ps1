param([switch]$Test, [switch]$Capture)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$snakeProject = $PSScriptRoot
$snakeBuild = Join-Path $snakeProject 'build'
New-Item -ItemType Directory -Path $snakeBuild -Force | Out-Null
$snakeFiles = @(Get-ChildItem -LiteralPath (Join-Path $snakeProject 'A1143349_previous') -Filter '*.java' | Select-Object -ExpandProperty FullName)
$snakeFiles += @(Get-ChildItem -LiteralPath (Join-Path $snakeProject 'tests') -Filter '*.java' | Select-Object -ExpandProperty FullName)
& javac -encoding UTF-8 --release 17 -d $snakeBuild $snakeFiles
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Push-Location -LiteralPath $snakeProject
try {
    if ($Test) { & java '-Dfile.encoding=UTF-8' -cp $snakeBuild DisconnectTests; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
    if ($Capture) { & java '-Dfile.encoding=UTF-8' -cp $snakeBuild CaptureDisconnectScreens; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
} finally { Pop-Location }
