#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Manual fallback for the CD pipeline: builds Docker images for one or more
    services, pushes them to ACR, and rewrites the matching newTag entries in
    cluster/overlays/<env>/kustomization.yaml.

.DESCRIPTION
    THE REAL PIPELINE is .github/workflows/cd.yml (push to main). This script
    only EMULATES the same steps locally when you cannot or do not want to run
    GitHub Actions — e.g. for a demo or a one-off env deploy. It mirrors the
    cd.yml contract exactly: images are matched by newName == "<acr>.azurecr.io/<svc>"
    and newTag is set to the commit SHA.

    IMPORTANT: the overlay's newName entries must equal the ACR login server
    you pass with -Acr. The repo ships placeholders ("acr.azurecr.io") — update
    them once (services/README.md) or nothing will match and the script warns.

    The script does NOT commit or push: after the tag rewrite, commit the
    kustomization change yourself (or let Argo CD pick it up from a branch).

.EXAMPLE
    # All 17 services, tag = current commit SHA, real ACR:
    .\scripts\azure\deploy-cd-manual.ps1 -Env dev -Acr myacr -SubscriptionId <ID>
    # One service only:
    .\scripts\azure\deploy-cd-manual.ps1 -Env prod -Acr myacr -Service catalog-svc -Tag v1.0.0
    # Skip build/push and only rewrite the kustomization tags (already pushed):
    .\scripts\azure\deploy-cd-manual.ps1 -Env prod -Acr myacr -SkipBuild -SkipPush

.NOTES
    PowerShell 5.1 compatible. No secrets — run az login first (az-login.ps1)
    or provide ACR credentials via docker login.
#>
[CmdletBinding()]
param(
    [ValidateSet('dev', 'staging', 'prod')]
    [string]$Env = 'prod',
    [string]$Acr = 'acr',                       # ACR login server (no .azurecr.io suffix needed? pass full name without scheme)
    [string]$Tag = '',                          # default: git rev-parse HEAD
    [string]$Service = '',                      # optional: single service name
    [switch]$SkipBuild,
    [switch]$SkipPush,
    [switch]$SkipTagRewrite,
    [string]$DockerContext = ''                 # optional: custom docker build context (default: services/<svc>)
)

$ErrorActionPreference = 'Stop'

function Write-Step { param([string]$Msg) Write-Host "==> $Msg" -ForegroundColor Cyan }
function Write-Ok   { param([string]$Msg) Write-Host "    $Msg" -ForegroundColor Green }
function Write-Warn { param([string]$Msg) Write-Host "    WARN: $Msg" -ForegroundColor Yellow }

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path   # scripts/azure -> repo root
$KustFile = Join-Path $RepoRoot "cluster\overlays\$Env\kustomization.yaml"
if (-not (Test-Path $KustFile)) {
    throw "Overlay kustomization not found: $KustFile"
}

# ── Tag ──────────────────────────────────────────────────────────────────────
if (-not $Tag) {
    $null = & git rev-parse HEAD 2>$null
    if ($LASTEXITCODE -eq 0) { $Tag = (& git rev-parse HEAD).Trim() }
    else { throw "Could not derive a tag from 'git rev-parse HEAD'. Pass -Tag explicitly (note: Kyverno rejects ':latest'; use a real tag, e.g. a SHA or version)." }
}
Write-Step "Manual CD emulation — env=$Env acr=$Acr tag=$Tag"

# ── Service list ─────────────────────────────────────────────────────────────
if ($Service) {
    $services = @($Service)
}
else {
    # Every image entry in the overlay whose newName starts with "<acr>.azurecr.io/".
    $services = @()
    $lines = Get-Content $KustFile
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i].Trim()
        if ($line -match '^newName:\s+([A-Za-z0-9._-]+)\.azurecr\.io/([A-Za-z0-9_-]+)$') {
            $services += $Matches[2]
        }
    }
    $services = $services | Sort-Object -Unique
}
if ($services.Count -eq 0) {
    throw "No services matched in $KustFile. Check that newName entries follow '<acr>.azurecr.io/<svc>'."
}
Write-Ok "$($services.Count) service(s) to process: $($services -join ', ')"

# ── 1. ACR login ─────────────────────────────────────────────────────────────
Write-Step "Logging in to ACR: $Acr"
& az acr login --name $Acr
if ($LASTEXITCODE -ne 0) {
    throw "az acr login failed (exit $LASTEXITCODE). Run .\scripts\azure\az-login.ps1 first (az account set needed too)."
}

# ── 2. Build + push ──────────────────────────────────────────────────────────
foreach ($svc in $services) {
    $image = "$Acr.azurecr.io/$svc`:$Tag"
    $context = if ($DockerContext) { $DockerContext } else { Join-Path $RepoRoot "services\$svc" }

    if (-not (Test-Path (Join-Path $context 'Dockerfile'))) {
        Write-Warn "No Dockerfile at $context — skipping build/push for '$svc'"
        continue
    }

    if (-not $SkipBuild) {
        Write-Step "Building $image  (context: $context)"
        & docker build -t $image $context
        if ($LASTEXITCODE -ne 0) { throw "docker build failed for $svc (exit $LASTEXITCODE)." }
    }

    if (-not $SkipPush) {
        Write-Step "Pushing $image"
        & docker push $image
        if ($LASTEXITCODE -ne 0) { throw "docker push failed for $svc (exit $LASTEXITCODE)." }
    }
}

# ── 3. Rewrite newTag in the overlay (mirrors cd.yml set-env + yq) ───────────
if (-not $SkipTagRewrite) {
    Write-Step "Rewriting newTag in cluster/overlays/$Env (matching newName = <acr>.azurecr.io/<svc>)"
    $changed = $false
    foreach ($svc in $services) {
        $image = "$Acr.azurecr.io/$svc"
        # Block for one images[] entry: name / newName / newTag on consecutive lines.
        $pattern = "(?m)^(\s*-\s+name:\s*" + [regex]::Escape($image) + "\r?\n\s+newName:\s*" + [regex]::Escape($image) + "\r?\n\s+newTag:\s*)[^\r\n]+"
        $content = Get-Content $KustFile -Raw
        if ($content -match $pattern) {
            $content = [regex]::Replace($content, $pattern, "\`${1}$Tag")
            # Write without BOM (kustomize/kubectl are BOM-tolerant but the
            # repo files are UTF-8 no-BOM — keep it that way).
            [System.IO.File]::WriteAllText($KustFile, $content, (New-Object System.Text.UTF8Encoding($false)))
            $changed = $true
            Write-Ok "  $image -> newTag: $Tag"
        }
        else {
            Write-Warn "  No image entry matching '$image' in $KustFile — the overlay still uses the 'acr.azurecr.io' placeholder (see services/README.md)."
        }
    }

    if ($changed) {
        Write-Step "kustomization.yaml updated — review and commit it"
        Write-Ok "  git diff cluster/overlays/$Env/kustomization.yaml"
        Write-Ok "  git add cluster/overlays/$Env/kustomization.yaml && git commit -m 'chore($Env): bump image tags to $Tag'"
    }
}
else {
    Write-Ok "Tag rewrite skipped (-SkipTagRewrite) — tags in the overlay are untouched."
}

Write-Step "Done"
if ($Service) { Write-Ok "Next: apply the overlay or sync Argo CD (env=$Env)." }
else          { Write-Ok "Next: apply the overlay (.\scripts\deploy\apply-overlay.ps1 -Env $Env) or sync Argo CD (.\scripts\deploy\sync-argocd-app.ps1 -AppName ecommerce-$Env)." }
Write-Warn "The real deployment path remains .github/workflows/cd.yml — this script is the documented manual fallback only."