# Build Java sources into out\classes (no Maven/Gradle needed).
# Usage: powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $here 'out\classes'
$lib = Join-Path $here 'lib'
$src = Join-Path $here 'src\main\java'
$res = Join-Path $here 'src\main\resources'

if (-not (Test-Path (Join-Path $lib 'javafx-web-21.0.5-win.jar'))) {
    Write-Host 'Dependencies missing. Run: node tools/fetch-deps.mjs'
    exit 1
}

if ($env:JAVA_HOME) {
    $javaHome = $env:JAVA_HOME
} else {
    $javaHome = Split-Path -Parent (Split-Path -Parent (Get-Command java).Source)
}
$javac = Join-Path $javaHome 'bin\javac.exe'
if (-not (Test-Path $javac)) {
    Write-Host "javac not found: $javac"
    exit 1
}

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null

$jackson = (Get-ChildItem $lib -Filter 'jackson-*.jar' | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Recurse -Filter *.java $src | ForEach-Object { $_.FullName }

& $javac --release 21 -encoding UTF-8 `
    --module-path $lib --add-modules javafx.controls,javafx.web,javafx.swing `
    -cp $jackson -d $out $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Copy-Item (Join-Path $res '*.js') $out -Force
Write-Host "Build OK: $out"
