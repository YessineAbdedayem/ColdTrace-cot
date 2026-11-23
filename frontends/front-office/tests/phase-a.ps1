param([int]$Port = 14443)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$gitBash = Join-Path $env:ProgramFiles 'Git/bin/bash.exe'
if (!(Test-Path -LiteralPath $gitBash)) { throw 'Install Git for Windows (Git Bash and OpenSSL are required).' }
$runId = [Guid]::NewGuid().ToString('N')
$temporary = Join-Path ([IO.Path]::GetTempPath()) "coldtrace-phase-a-$runId"
$certificates = Join-Path $temporary 'certs'
New-Item -ItemType Directory -Path $certificates,(Join-Path $temporary 'scripts') | Out-Null
Copy-Item -LiteralPath (Join-Path $repo 'gateway-iot/scripts/gen-certs.sh') -Destination (Join-Path $temporary 'scripts/gen-certs.sh')
$env:HOSTING_CERTS_DIR = $certificates
$env:HOSTING_TLS_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:PHASE_A_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:HOSTING_HTTPS_PORT = [string]$Port
$env:PHASE_A_CERT_SCRIPT = (Join-Path $temporary 'scripts/gen-certs.sh').Replace('\','/')
$env:PHASE_A_CERT_DIR = $certificates.Replace('\','/')
$env:PHASE_A_WEB_SCRIPT = (Join-Path $repo 'middleware/wildfly/gen-web-cert.sh').Replace('\','/')
$bootstrap = Join-Path $temporary 'generate.sh'
[IO.File]::WriteAllText($bootstrap, "bash `"`$PHASE_A_CERT_SCRIPT`" && CERTS_DIR=`"`$PHASE_A_CERT_DIR`" WEB_TLS_PASSWORD=`"`$HOSTING_TLS_PASSWORD`" bash `"`$PHASE_A_WEB_SCRIPT`"`n")
$ErrorActionPreference = 'Continue' # Windows PowerShell treats OpenSSL progress on stderr as NativeCommandError.
& $gitBash $bootstrap *> (Join-Path $temporary 'certificate-generation.log')
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) { throw "Temporary certificate generation failed; see $temporary/certificate-generation.log" }
$project = "coldtrace-phase-a-check-$runId"
$env:PHASE_A_CONFIG = Join-Path $temporary 'config.json'
$config = @{certDir=$certificates; tlsPassword=$env:HOSTING_TLS_PASSWORD; password=$env:PHASE_A_PASSWORD; port=$Port; project=$project}
[IO.File]::WriteAllText($env:PHASE_A_CONFIG, ($config | ConvertTo-Json))
$chrome = Join-Path $env:ProgramFiles 'Google/Chrome/Application/chrome.exe'
if (Test-Path -LiteralPath $chrome) { $env:BROWSER_EXECUTABLE = $chrome }
$compose = @('compose','-p',$project,'-f',(Join-Path $PSScriptRoot 'hosting.compose.yml'),'-f',(Join-Path $PSScriptRoot 'phase-a.compose.yml'))
Push-Location $repo
try {
    & npm.cmd ci --prefix frontends/front-office
    if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
    if (!$env:BROWSER_EXECUTABLE) {
        Push-Location frontends/front-office
        try { & npx.cmd playwright install chromium; if ($LASTEXITCODE -ne 0) { throw 'Chromium installation failed' } }
        finally { Pop-Location }
    }
    & docker @compose up -d --build
    if ($LASTEXITCODE -ne 0) { throw 'Isolated Compose startup failed' }
    & node frontends/front-office/tests/phase-a-integration.cjs
    if ($LASTEXITCODE -ne 0) { throw 'Phase A tests failed; see middleware/api/target/phase-a-evidence' }
} finally {
    # Stop/remove ONLY containers in this unique test project. Retain its data volumes and temporary keys.
    & docker @compose down
    Pop-Location
    Write-Host "Retained isolated test configuration: $temporary (contains disposable credentials)."
}
