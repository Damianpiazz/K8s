# ---------------------------------------------------------------------------
# build-push.ps1 - Construye y sube las 18 imagenes de la plataforma (ASCII-only).
#
# Destino local (default): el registry sidecar de Floci-AZ.
#   .\scripts\build-push.ps1
# Destino ACR real:
#   az login; az acr login --name <acr>
#   .\scripts\build-push.ps1 -Repository <acr-login-server> -Azure
#
# Parametros:
#   -Repository  login server destino (default: localhost:5000/localecommercefloci01)
#   -Tag         tag de las imagenes (default: SHA corto de git)
#   -Azure       usa 'az acr login' antes de pushear y requiere Repository real
#   -SkipBuild   solo push de imagenes ya construidas
#
# Convencion (work-unit-commits / cd.yml): NUNCA usar :latest para el deploy;
# el overlay local ya rewrites a host.docker.internal:5000/<repo>/<svc>.
# ---------------------------------------------------------------------------
param(
    [string]$Repository = "localhost:5000/localecommercefloci01",
    [string]$Tag = "",
    [switch]$Azure,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

# Las 18 imagenes de la plataforma (orden Fase 5 -> 8)
$services = @(
    "config-service",
    "discovery-service",
    "api-gateway",
    "auth",
    "catalog-svc",
    "cart-svc",
    "checkout-svc",
    "order-svc",
    "payment-svc",
    "notification-svc",
    "search-svc",
    "recommendation-svc",
    "inventory-svc",
    "shipping-svc",
    "returns-svc",
    "analytics-svc",
    "bff-web",
    "frontend"
)

# Tag default: SHA corto del commit actual
if ([string]::IsNullOrWhiteSpace($Tag)) {
    $Tag = (git rev-parse --short HEAD).Trim()
    if ([string]::IsNullOrWhiteSpace($Tag)) {
        Write-Error "No se pudo obtener el SHA de git. Pasalo con -Tag."
    }
}
Write-Host "==> Imagenes: $Repository/<svc>:$Tag" -ForegroundColor Cyan

if ($Azure) {
    Write-Host "==> Login ACR (az acr login)..." -ForegroundColor Cyan
    $acrName = $Repository.Split(".")[0]
    az acr login --name $acrName
    if ($LASTEXITCODE -ne 0) { throw "az acr login fallo para $acrName" }
}
elseif (-not $SkipBuild) {
    # Registry local de floci en localhost:5000 - Docker lo trata como
    # insecure automaticamente; no hace falta login.
    Write-Host "==> Destino local (registry sidecar de Floci-AZ)" -ForegroundColor Gray
}

foreach ($svc in $services) {
    $image = "$Repository/$svc`:$Tag"
    $ctx = Join-Path $PSScriptRoot "..\services\$svc"

    if (-not (Test-Path -LiteralPath (Join-Path $ctx "Dockerfile"))) {
        Write-Warning "SKIP $svc - missing Dockerfile in $ctx"
        continue
    }

    if (-not $SkipBuild) {
        Write-Host "==> build  $image" -ForegroundColor Cyan
        docker build -t $image $ctx
        if ($LASTEXITCODE -ne 0) { throw "docker build fallo para $svc" }
    }

    Write-Host "==> push   $image" -ForegroundColor Cyan
    docker push $image
    if ($LASTEXITCODE -ne 0) { throw "docker push fallo para $svc" }
}

Write-Host "==> OK: $($services.Count) imagenes en $Repository" -ForegroundColor Green
Write-Host "    Deploy local: kubectl apply -k cluster/overlays/local"