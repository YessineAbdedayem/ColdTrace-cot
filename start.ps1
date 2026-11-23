# ColdTrace: starts the whole project on this PC with one command.
#
#   .\start              platform + MLOps (trains the model only the first time)
#   .\start -Retrain     also regenerate the data and retrain the model
#   .\start -NoMl        platform only (no MLflow, no model, no early warnings)
#   .\start -Stop        stop everything (data is kept)
#
# Safe to run again at any time: each step skips what is already done.
# Needs Docker Desktop running. Git for Windows is only needed on a fresh PC (to create the certificates).

param(
    [switch]$Retrain,
    [switch]$NoMl,
    [switch]$Stop
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Gateway = Join-Path $Root "gateway-iot"
$Mlops = Join-Path $Root "mlops"
$Wildfly = Join-Path $Root "middleware\wildfly"
# The names of the WildFly virtual hosts (middleware/wildfly); they must point to this PC in the hosts file.
$HostNames = @("coldtrace.test", "www.coldtrace.test", "api.coldtrace.test", "iam.coldtrace.test", "admin.coldtrace.test", "mlflow.coldtrace.test")

function Step($text) { Write-Host ""; Write-Host "==> $text" -ForegroundColor Cyan }
function Info($text) { Write-Host "    $text" }
function Warn($text) { Write-Host "    $text" -ForegroundColor Yellow }
function Fail($text) { Write-Host ""; Write-Host "ERROR: $text" -ForegroundColor Red; exit 1 }

# Runs docker compose in a folder and stops the script if it fails.
function Compose($dir, [string[]]$composeArgs) {
    Push-Location $dir
    try {
        & docker compose @composeArgs
        if ($LASTEXITCODE -ne 0 -and $composeArgs[0] -eq "up") {
            # Docker Desktop sometimes reports a name conflict with the container it has just created: retry once.
            Warn "docker compose up failed, retrying once..."
            Start-Sleep -Seconds 3
            & docker compose @composeArgs
        }
        if ($LASTEXITCODE -ne 0) { Fail "docker compose $($composeArgs -join ' ') failed in $dir" }
    } finally {
        Pop-Location
    }
}

# Writes a text file without a BOM (docker compose reads .env files byte for byte).
function Write-PlainFile($path, $text) {
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function New-Password {
    $bytes = New-Object byte[] 18
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    # URL-safe: the password goes inside a mongodb:// URI
    return ([Convert]::ToBase64String($bytes) -replace '[+/=]', 'x')
}

# Gives a .env variable a random password when it is missing, empty, or still a "change-me" example.
# A value someone chose is never changed. Returns $true if the file changed.
function Set-SecretIfMissing($file, $name) {
    $lines = [System.Collections.Generic.List[string]](Get-Content $file)
    $index = -1
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match "^$name=") { $index = $i; break } }
    if ($index -ge 0) {
        $value = $lines[$index].Substring($name.Length + 1).Trim()
        if ($value -and -not $value.StartsWith("change-me")) { return $false }
        $lines[$index] = "$name=$(New-Password)"
    } else {
        $lines.Add("$name=$(New-Password)")
    }
    Write-PlainFile $file (($lines -join "`n") + "`n")
    return $true
}

function Get-ModelHealth {
    try {
        # 127.0.0.1, not localhost: Windows PowerShell tries IPv6 first, and Docker publishes the port on IPv4 only
        return Invoke-RestMethod -Uri "http://127.0.0.1:8001/health" -TimeoutSec 3
    } catch {
        return $null
    }
}

# Asks MLflow itself (inside its container) whether a model has the @production alias.
# Returns $true or $false, or $null if MLflow did not answer within 2 minutes.
$ProductionCheck = @'
import sys, urllib.request, urllib.error
url = "http://localhost:5000/api/2.0/mlflow/registered-models/alias?name=coldtrace-early-warning&alias=production"
try:
    urllib.request.urlopen(url, timeout=5)
    sys.exit(0)                                  # found
except urllib.error.HTTPError as e:
    # MLflow answers 400 "alias ... not found" when there is no such model or alias yet
    sys.exit(3 if 400 <= e.code < 500 else 2)
except Exception:
    sys.exit(2)                                  # MLflow not ready yet
'@
function Test-ProductionModel {
    Push-Location $Mlops
    try {
        for ($i = 0; $i -lt 60; $i++) {
            $ErrorActionPreference = "Continue"
            $ProductionCheck | & docker compose exec -T mlflow python - 2>$null
            $code = $LASTEXITCODE
            $ErrorActionPreference = "Stop"
            if ($code -eq 0) { return $true }
            if ($code -eq 3) { return $false }
            Start-Sleep -Seconds 2
        }
        return $null
    } finally {
        Pop-Location
    }
}

# ---------------------------------------------------------------------------------------------------------------

$ErrorActionPreference = "Continue"   # Windows PowerShell 5.1 turns redirected stderr into an exception otherwise
& docker info *> $null
$dockerOk = $LASTEXITCODE -eq 0
$ErrorActionPreference = "Stop"
if (-not $dockerOk) { Fail "Docker is not running. Start Docker Desktop, wait until it says 'running', then try again." }

if ($Stop) {
    Step "Stopping ColdTrace (data is kept)"
    if (Test-Path (Join-Path $Mlops ".env")) { Compose $Mlops @("down") }
    Compose $Gateway @("down")
    Write-Host ""; Write-Host "Stopped." -ForegroundColor Green
    exit 0
}

# --- 1. Platform --------------------------------------------------------------------------------------------------

Step "1/4  Platform: configuration"
$gatewayEnv = Join-Path $Gateway ".env"
if (-not (Test-Path $gatewayEnv)) {
    Copy-Item (Join-Path $Gateway ".env.example") $gatewayEnv
    Warn "Created gateway-iot/.env from .env.example (development passwords)."
} else {
    Info "gateway-iot/.env found"
}
# Login server accounts (middleware/iam): admin, and the demo users "qualite" and "operateur"
$newSecrets = @("IAM_ADMIN_PASSWORD", "IAM_QUALITY_PASSWORD", "IAM_OPERATOR_PASSWORD") |
    Where-Object { Set-SecretIfMissing $gatewayEnv $_ }
if ($newSecrets) {
    Info "Generated login passwords in gateway-iot/.env: $($newSecrets -join ', ')"
}

$certs = Join-Path $Gateway "certs"
if (Test-Path (Join-Path $certs "ca.crt") -PathType Container) {
    Fail "gateway-iot/certs/ca.crt is a folder (left by a start without certificates). Run '.\start -Stop', delete that folder, then run .\start again."
}
$missingCert = @("ca.crt", "cloud-broker.crt", "cloud-broker.key") | Where-Object { -not (Test-Path (Join-Path $certs $_)) }
if ($missingCert) {
    $bash = "C:\Program Files\Git\bin\bash.exe"
    if (-not (Test-Path $bash)) { Fail "Certificates are missing and Git Bash was not found to create them. Install Git for Windows, or run gateway-iot/scripts/gen-certs.sh yourself." }
    Info "Creating the development certificates..."
    $env:MSYS_NO_PATHCONV = "1"
    & $bash (Join-Path $Gateway "scripts/gen-certs.sh")
    if ($LASTEXITCODE -ne 0) { Fail "Could not create the certificates (gateway-iot/scripts/gen-certs.sh)." }
} else {
    Info "certificates found"
}

# HTTPS certificate of the WildFly (*.coldtrace.test), signed by the development CA above.
$newTls = Set-SecretIfMissing $gatewayEnv "WEB_TLS_PASSWORD"
if ($newTls -or -not (Test-Path (Join-Path $certs "web.p12"))) {
    $bash = "C:\Program Files\Git\bin\bash.exe"
    if (-not (Test-Path $bash)) { Fail "The HTTPS certificate is missing and Git Bash was not found to create it. Install Git for Windows, or run middleware/wildfly/gen-web-cert.sh yourself." }
    Info "Creating the HTTPS certificate for *.coldtrace.test..."
    $env:WEB_TLS_PASSWORD = ((Get-Content $gatewayEnv) -match '^WEB_TLS_PASSWORD=' | Select-Object -First 1).Substring(17).Trim()
    & $bash (Join-Path $Wildfly "gen-web-cert.sh")
    Remove-Item Env:\WEB_TLS_PASSWORD
    if ($LASTEXITCODE -ne 0) { Fail "Could not create the HTTPS certificate (middleware/wildfly/gen-web-cert.sh)." }
} else {
    Info "HTTPS certificate found"
}

# The browser must trust the development CA, or it refuses https://*.coldtrace.test.
# Current user's store: no administrator rights needed; Windows asks for a confirmation the first time.
$ca = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2 (Join-Path $certs "ca.crt")
if (-not (Get-ChildItem Cert:\CurrentUser\Root | Where-Object { $_.Thumbprint -eq $ca.Thumbprint })) {
    Warn "Trusting the ColdTrace development CA for this Windows user: confirm with 'Yes' in the window that opens."
    Import-Certificate -FilePath (Join-Path $certs "ca.crt") -CertStoreLocation Cert:\CurrentUser\Root | Out-Null
    Info "Development CA trusted (restart the browser if it was open)"
} else {
    Info "development CA already trusted"
}

# The names of the virtual hosts must point to this PC.
$hostsFile = "$env:WINDIR\System32\drivers\etc\hosts"
$hostsText = if (Test-Path $hostsFile) { Get-Content $hostsFile -Raw } else { "" }
$missingNames = $HostNames | Where-Object { $hostsText -notmatch "(?m)^\s*127\.0\.0\.1\s+(.*\s)?$([regex]::Escape($_))(\s|#|$)" }
if ($missingNames) {
    Warn "These names are not in the hosts file yet: $($missingNames -join ', ')"
    Warn "In PowerShell as administrator, run:"
    Warn "  Add-Content -Path $hostsFile -Encoding ASCII -Value ""``n127.0.0.1   $($missingNames -join ' ')"""
    Warn "  ipconfig /flushdns"
}

Step "2/4  Platform: build and start (the first time takes a few minutes)"
Compose $Gateway @("up", "-d", "--build")

Info "Waiting for MongoDB..."
Push-Location $Gateway
$mongoId = (& docker compose ps -q mongo)
Pop-Location
$healthy = $false
for ($i = 0; $i -lt 60; $i++) {
    if ((& docker inspect --format "{{.State.Health.Status}}" $mongoId) -eq "healthy") { $healthy = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $healthy) { Fail "MongoDB did not become ready in 2 minutes. See: cd gateway-iot; docker compose logs mongo" }
Info "MongoDB ready"

# --- 3. MLOps -----------------------------------------------------------------------------------------------------

if (-not $NoMl) {
    Step "3/4  MLOps: MLflow and the model API"
    $mlEnv = Join-Path $Mlops ".env"
    if (-not (Test-Path $mlEnv)) {
        $example = Get-Content (Join-Path $Mlops ".env.example") -Raw
        Write-PlainFile $mlEnv ($example -replace 'ML_MONGO_PASSWORD=.*', "ML_MONGO_PASSWORD=$(New-Password)")
        Info "Created mlops/.env with a random password"
    } else {
        Info "mlops/.env found"
    }
    # Creates the pipeline's MongoDB user, or updates its password to match mlops/.env.
    Compose $Mlops @("run", "--rm", "ml-user")
    Compose $Mlops @("up", "-d", "--build", "mlflow", "model-api", "monitor")

    # Does MLflow already have a production model? Then there is nothing to train.
    Info "Checking MLflow for a production model..."
    $hasModel = Test-ProductionModel
    if ($null -eq $hasModel) {
        Fail "MLflow did not answer within 2 minutes. See: cd mlops; docker compose logs mlflow"
    }

    if ($Retrain -or -not $hasModel) {
        Step "4/4  MLOps: generate data and train the model (about 3 minutes, more the first time)"
        Compose $Mlops @("run", "--rm", "--build", "pipeline", "run.py", "--generate")

        Info "Waiting for the model API to load the new model (it checks every minute)..."
        for ($i = 0; $i -lt 45; $i++) {
            $health = Get-ModelHealth
            if ($health -and $health.status -eq "ok") { break }
            Start-Sleep -Seconds 2
        }
        if (-not ($health -and $health.status -eq "ok")) {
            Warn "The model API has not loaded the model yet. Check in a minute: curl.exe http://localhost:8001/health"
        }
    } else {
        Step "4/4  MLOps: a model is already in production, no training (use -Retrain to train again)"
    }
} else {
    Step "3/4 and 4/4  MLOps skipped (-NoMl): no early warnings"
}

# --- Summary ------------------------------------------------------------------------------------------------------

Write-Host ""
Write-Host "ColdTrace is running." -ForegroundColor Green
Write-Host ""
Write-Host "  All served by ONE WildFly, one virtual host per name (middleware/wildfly):"
Write-Host "  Front-office (monitor)  https://coldtrace.test        (or https://www.coldtrace.test)"
Write-Host "  Back-office             https://admin.coldtrace.test"
Write-Host "  REST API                https://api.coldtrace.test/api/health"
Write-Host "  Login server (IAM)      https://iam.coldtrace.test   (users admin, qualite, operateur:"
Write-Host "                          passwords in gateway-iot/.env, IAM_*_PASSWORD)"
if (-not $NoMl) {
    $model = Get-ModelHealth
    $state = if ($model -and $model.status -eq "ok") { "model version $($model.modelVersion)" } else { "still loading" }
    Write-Host "  Model API               https://api.coldtrace.test/ml/docs   ($state)"
    Write-Host "  Live model scores       https://api.coldtrace.test/ml/monitoring   (first scores about 15 min after start)"
    Write-Host "  MLflow                  https://mlflow.coldtrace.test"
}
if ($missingNames) {
    Write-Host ""
    Write-Host "  The addresses above need these names in the hosts file first: $($missingNames -join ', ')" -ForegroundColor Yellow
}
Write-Host ""
Write-Host "  Try it: click Thaw on a fridge in the monitor. An early_warning incident opens before the temperature one."
Write-Host "  WildFly log: cd gateway-iot; docker compose logs -f wildfly"
Write-Host "  Stop:    .\start -Stop"
