# One-shot test: build -> unit tests + parity with the original JS -> WebView smoke test -> live LLM.
# Usage: powershell -ExecutionPolicy Bypass -File test.ps1
$ErrorActionPreference = 'Stop'

# Keep Chinese test output readable under Windows PowerShell 5.1 (console defaults
# to the OEM code page while the JVM writes UTF-8).
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $here 'out\classes'
$lib = Join-Path $here 'lib'
$appRoot = Join-Path $here 'app'
$utf8 = New-Object System.Text.UTF8Encoding($false)

& (Join-Path $here 'build.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ''
Write-Host '[1/5] Generate parity data with the original review-core.js'
$parity = Join-Path $here 'out\parity.json'
$lines = & node (Join-Path $here 'tools\parity-dump.mjs')
if ($LASTEXITCODE -ne 0) { Write-Host 'parity-dump failed'; exit 1 }
$json = $lines -join "`n"
[System.IO.File]::WriteAllText($parity, $json, $utf8)
Write-Host "  -> $parity"

if ($env:JAVA_HOME) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $java = (Get-Command java).Source
}
$cp = ((@($out) + (Get-ChildItem $lib -Filter 'jackson-*.jar' | ForEach-Object { $_.FullName })) -join ';')

$common = @(
    "-Duser.home=$(Join-Path $here 'out\home')",
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
    '--module-path', $lib, '--add-modules', 'javafx.controls,javafx.web,javafx.swing',
    '-cp', $cp, 'com.sentence.App'
)

Write-Host ''
Write-Host '[2/5] Unit tests + parity with review-core.js'
& $java @common '--self-test' "--app-root=$appRoot" "--parity-file=$parity"
if ($LASTEXITCODE -ne 0) {
    Write-Host 'self-test FAILED'
    exit $LASTEXITCODE
}

Write-Host ''
Write-Host '[3/5] WebView smoke test (learn view)'
$snapshot = Join-Path $here 'out\smoke.png'
& $java @common '--smoke-test' "--app-root=$appRoot" "--data-dir=$(Join-Path $here 'out\userdata')" "--snapshot=$snapshot"
if ($LASTEXITCODE -ne 0) {
    Write-Host 'smoke test FAILED'
    exit $LASTEXITCODE
}

Write-Host ''
Write-Host '[4/5] WebView smoke test (review view, real LLM)'
$reviewState = Join-Path $here 'out\userdata-review'
New-Item -ItemType Directory -Force -Path $reviewState | Out-Null
$inner = '{"currentDay":2,"lastDate":"2026-01-01","days":{"1":["abandon","mitigate","ubiquitous"]},"reviewedDay":2,"reviewedWords":[]}'
$escaped = $inner.Replace('\', '\\').Replace('"', '\"')
[System.IO.File]::WriteAllText((Join-Path $reviewState 'review-state.json'),
    '{"learnedDays":"' + $escaped + '"}', $utf8)
$reviewShot = Join-Path $here 'out\smoke-review.png'
& $java @common '--smoke-test' "--app-root=$appRoot" "--data-dir=$reviewState" "--snapshot=$reviewShot"
if ($LASTEXITCODE -ne 0) {
    Write-Host 'review smoke test FAILED'
    exit $LASTEXITCODE
}

Write-Host ''
Write-Host '[5/5] Live DeepSeek call (consumes a few tokens)'
& $java @common '--live-test' "--app-root=$appRoot"
if ($LASTEXITCODE -ne 0) {
    Write-Host 'live LLM test FAILED (check the API key / network)'
    exit $LASTEXITCODE
}

Write-Host ''
Write-Host "ALL TESTS PASSED."
Write-Host "Snapshots: $snapshot"
Write-Host "           $reviewShot"
