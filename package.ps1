param([switch]$Console)

# Build a self-contained app image (bundled Java runtime + JavaFX) with jpackage.
# Output: out\dist\CreateSentence\CreateSentence.exe  (no console window, no JDK needed)
# Usage: powershell -ExecutionPolicy Bypass -File package.ps1 [-Console]
#        -Console keeps a console window for troubleshooting.
$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$appRoot = Join-Path $here 'app'
$out = Join-Path $here 'out\classes'
$lib = Join-Path $here 'lib'
$pkg = Join-Path $here 'out\package-input'
$dist = Join-Path $here 'out\dist'
$name = 'CreateSentence'

& (Join-Path $here 'build.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if ($env:JAVA_HOME) {
    $javaHome = $env:JAVA_HOME
} else {
    $javaHome = Split-Path -Parent (Split-Path -Parent (Get-Command java).Source)
}
$jarTool = Join-Path $javaHome 'bin\jar.exe'
$jpackage = Join-Path $javaHome 'bin\jpackage.exe'
foreach ($tool in @($jarTool, $jpackage)) {
    if (-not (Test-Path $tool)) {
        Write-Host "Tool not found: $tool (need JDK 14+, JDK 21 recommended)"
        exit 1
    }
}

Remove-Item -Recurse -Force $pkg -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force $dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $pkg | Out-Null
New-Item -ItemType Directory -Force -Path $dist | Out-Null

# 1) Application jar (includes bridge scripts and other resources)
& $jarTool --create --file (Join-Path $pkg 'app.jar') -C $out .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# 2) Third-party jars (jpackage puts every jar in the app dir on the classpath).
#    Do NOT copy JavaFX jars here: they are bundled into the runtime image via
#    --module-path / --add-modules and must stay modules.
Copy-Item (Join-Path $lib 'jackson-*.jar') $pkg -Force

# 3) Front-end assets: must sit next to app.jar; Settings locates index.html from the jar path
foreach ($f in @('index.html', 'styles.css', 'renderer.js', 'review-core.js', 'TOEFL_abridged.txt')) {
    Copy-Item (Join-Path $appRoot $f) $pkg -Force
}
Copy-Item (Join-Path $appRoot 'word-banks') $pkg -Recurse -Force
Copy-Item (Join-Path $appRoot '.env.example') $pkg -Force
# Optional: bundle app\.env so the exe works out of the box (dist/ is gitignored).
if (Test-Path (Join-Path $appRoot '.env')) {
    Copy-Item (Join-Path $appRoot '.env') $pkg -Force
}

# 4) Build the app image (GUI by default; -Console adds a console for troubleshooting)
$jpackageArgs = @(
    '--type', 'app-image',
    '--name', $name,
    '--app-version', '0.3.0',
    '--vendor', 'local',
    '--description', 'Create Sentence - learn English by making sentences',
    '--input', $pkg,
    '--main-jar', 'app.jar',
    '--main-class', 'com.sentence.App',
    '--module-path', $lib,
    '--add-modules', 'javafx.controls,javafx.web,javafx.swing,java.net.http,jdk.crypto.ec,jdk.unsupported',
    '--dest', $dist,
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '-Dstdout.encoding=UTF-8',
    '--java-options', '-Dstderr.encoding=UTF-8',
    '--java-options', '--add-modules=javafx.controls,javafx.web,javafx.swing'
)
if ($Console) {
    $jpackageArgs += '--win-console'
}
& $jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$exe = Join-Path $dist "$name\$name.exe"
Write-Host ''
if (Test-Path $exe) {
    Write-Host "Packaged: $exe"
    Write-Host "Put your .env next to app.jar (or in %APPDATA%\create-sentence\.env)."
} else {
    Write-Host 'jpackage finished but the exe was not found.'
    exit 1
}
