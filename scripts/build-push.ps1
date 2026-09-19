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
# el overlay local rewrites a host.docker.internal:5000/<repo>/<svc>.
# Fix 27 (estrategia unica de tags): cada ambiente tiene UN solo mecanismo que
# fija tags. LOCAL lo mantiene este script (al final actualiza newTag del
# overlay local al SHA corto que acaba de pushear). AZURE lo mantiene el CD
# (newTag = SHA largo del commit en dev/staging/prod). No editar newTag a mano.
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

# Fix 27: mantener el contrato de tags del overlay LOCAL — newTag siempre
# refleja lo que este script acaba de pushear (nunca :latest). Solo en modo
# local; Azure lo gestiona el CD con SHA largo.
if (-not $Azure) {
    $kustPath = Join-Path $PSScriptRoot "..\cluster\overlays\local\kustomization.yaml"
    $kust = [System.IO.File]::ReadAllText($kustPath, [System.Text.Encoding]::UTF8)
    $newTagRepl = '${1}' + $Tag
    foreach ($svc in $services) {
        $pattern = "(?s)(- name: acr\.azurecr\.io/$svc\r?\n.*?newTag: )\S+"
        $updated = [regex]::Replace($kust, $pattern, $newTagRepl)
        if ($updated -eq $kust) {
            Write-Warning "newTag de $svc no encontrado en $kustPath (patron inesperado)"
        }
        $kust = $updated
    }
    [System.IO.File]::WriteAllText($kustPath, $kust, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "==> Overlay local actualizado: newTag=$Tag para $($services.Count) servicios" -ForegroundColor Cyan
}

Write-Host "==> OK: $($services.Count) imagenes en $Repository" -ForegroundColor Green
Write-Host "    Deploy local: kubectl apply -k cluster/overlays/local"