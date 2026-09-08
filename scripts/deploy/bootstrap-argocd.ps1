#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Bootstraps Argo CD on the current cluster: installs the controller (via
    cluster/base/argocd/install, falling back to the official install.yaml),
    waits for the server, prints the initial admin password, and (optionally)
    opens a port-forward to the UI.

.DESCRIPTION
    Mirrors the bootstrap order documented in cluster/base/README.md step 1.
    After the server is up the script prints the remaining steps
    (apply the app-of-apps + child Applications, then -k cluster/base).

    NOTE: newer kubectl versions reject remote kustomize bases; the script
    detects that and falls back to the official Argo CD manifests URL.

.EXAMPLE
    .\scripts\deploy\bootstrap-argocd.ps1
    .\scripts\deploy\bootstrap-argocd.ps1 -NoPortForward -Version v2.12.3

.NOTES
    PowerShell 5.1 compatible. No secrets. The password is printed once from
    the cluster's argocd-initial-admin-secret (like the README does).
#>
[CmdletBinding()]
param(
    [switch]$NoPortForward,
    [string]$Version = 'v2.12.3',
    [int]$LocalPort = 8080,
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'

function Write-Step { param([string]$Msg) Write-Host "==> $Msg" -ForegroundColor Cyan }
function Write-Ok   { param([string]$Msg) Write-Host "    $Msg" -ForegroundColor Green }
function Write-Warn { param([string]$Msg) Write-Host "    WARN: $Msg" -ForegroundColor Yellow }

if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
    throw "Required tool 'kubectl' not found on PATH. See scripts/README.md."
}

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

# ── 1. Install Argo CD ───────────────────────────────────────────────────────
Write-Step "Installing Argo CD ($Version) into namespace 'argocd'"
$null = & kubectl create namespace argocd 2>$null   # idempotent

$installDir = Join-Path $RepoRoot 'cluster\base\argocd\install'
& kubectl apply -k $installDir 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Warn "kubectl apply -k cluster/base/argocd/install failed (remote base may be rejected by this kubectl) — falling back to the official install.yaml"
    & kubectl apply -n argocd -f "https://raw.githubusercontent.com/argoproj/argo-cd/$Version/manifests/install.yaml"
    if ($LASTEXITCODE -ne 0) {
        throw "Argo CD install failed (exit $LASTEXITCODE). Check network access and the version tag."
    }
}

# ── 2. Wait for the server ───────────────────────────────────────────────────
Write-Step "Waiting for argocd-server (timeout ${TimeoutSeconds}s)"
& kubectl -n argocd wait --for=condition=available deploy/argocd-server --timeout="${TimeoutSeconds}s"
if ($LASTEXITCODE -ne 0) {
    throw "argocd-server did not become available. Check: kubectl get pods -n argocd"
}
Write-Ok "argocd-server is available"

# ── 3. Initial admin password ────────────────────────────────────────────────
Write-Step "Initial admin credentials"
$b64 = (& kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' 2>$null) -join ''
if ($b64) {
    $password = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($b64))
    Write-Ok "user: admin"
    Write-Ok "password (CLUSTER-SCOPED — change it after first login): $password"
}
else {
    Write-Warn "Could not read argocd-initial-admin-secret. Run: kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}'"
}

# ── 4. (Optional) port-forward to the UI ─────────────────────────────────────
if (-not $NoPortForward) {
    Write-Step "Opening port-forward to the Argo CD UI"
    Write-Ok "UI: https://localhost:${LocalPort}  (user: admin, password above)"
    Write-Warn "Keep this script running OR start the forward yourself: kubectl -n argocd port-forward svc/argocd-server ${LocalPort}:443"
    & kubectl -n argocd port-forward svc/argocd-server "${LocalPort}:443"
}

# ── 5. Next steps ────────────────────────────────────────────────────────────
Write-Step "Next steps (bootstrap order from cluster/base/README.md)"
Write-Ok "1. kubectl apply -f cluster/base/argocd/app-of-apps.yaml"
Write-Ok "2. kubectl apply -f cluster/base/argocd/applications/"
Write-Ok "3. kubectl get applications -n argocd   # wait until Healthy"
Write-Ok "4. kubectl apply -k cluster/base"
Write-Ok "Or use: .\scripts\deploy\apply-overlay.ps1 -Env dev  (renders + applies the whole overlay)"