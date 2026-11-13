# Start the Java version (loads the front-end files from .\app).
# Usage: powershell -ExecutionPolicy Bypass -File run.ps1 [--debug] [--smoke-test ...]
# Keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less scripts as ANSI,
# and non-ASCII comments can corrupt parsing.
$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $here 'out\classes'
$lib = Join-Path $here 'lib'
# Front-end assets: index.html / styles.css / renderer.js / review-core.js / word-banks / dictionary
$appRoot = Join-Path $here 'app'

if (-not (Test-Path (Join-Path $out 'com\sentence\App.class'))) {
    & (Join-Path $here 'build.ps1')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if ($env:JAVA_HOME) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $java = (Get-Command java).Source
}

$cp = ((@($out) + (Get-ChildItem $lib -Filter 'jackson-*.jar' | ForEach-Object { $_.FullName })) -join ';')

# Note: JVM options containing '=' must be quoted elements of an array,
# otherwise PowerShell passes them through as separate tokens.
$javaArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    '--module-path', $lib,
    '--add-modules', 'javafx.controls,javafx.web,javafx.swing',
    '-cp', $cp,
    'com.sentence.App',
    "--app-root=$appRoot"
)

# Windows PowerShell 5.1 decodes native-command output with the console code page
# (936 on Chinese Windows), while the JVM writes UTF-8 because of -Dstdout.encoding.
# Switch the console to UTF-8 for the app's lifetime, then restore it.
$previousOutputEncoding = $null
try {
    $previousOutputEncoding = [Console]::OutputEncoding
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
} catch {
    # No console (output redirected): nothing to adjust.
}

& $java @javaArgs @args
$exitCode = $LASTEXITCODE

if ($null -ne $previousOutputEncoding) {
    try { [Console]::OutputEncoding = $previousOutputEncoding } catch { }
}
exit $exitCode
