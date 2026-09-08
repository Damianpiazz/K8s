#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Starts a local Minikube cluster (profile "ecommerce") for the e-commerce
    platform, with the ingress and metallb addons enabled, and waits until the
    ingress-nginx controller is Ready.

.DESCRIPTION
    Phase 9 local-dev helper. Creates the single dev cluster this repo's
    manifests target when you are not using AKS.

    Requirements: minikube, kubectl, Docker (driver) — see scripts/README.md.

.EXAMPLE
    .\scripts\bootstrap\minikube-start.ps1
    .\scripts\bootstrap\minikube-start.ps1 -Cpus 6 -MemoryMB 12288

.NOTES
    PowerShell 5.1 compatible. No secrets. All defaults are safe for local dev.
#>
[CmdletBinding()]
param(
    [string]$Profile     = 'ecommerce',
    [int]$Cpus           = 4,
    [int]$MemoryMB       = 8192,
    [string]$Driver      = 'docker',
    [string]$K8sVersion  = '',          # optional, e.g. 'v1.30.0'
    [switch]$SkipAddons,
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'

function Write-Step { param([string]$Msg) Write-Host "==> $Msg" -ForegroundColor Cyan }
function Write-Ok   { param([string]$Msg) Write-Host "    $Msg" -ForegroundColor Green }
function Write-Warn { param([string]$Msg) Write-Host "    WARN: $Msg" -ForegroundColor Yellow }

function Test-NamespacedDeployment {
    # Returns $true when `kubectl get deploy <Name> -n <Namespace>` succeeds.
    param([string]$Name, [string]$Namespace)
    $null = & kubectl get deploy $Name -n $Namespace 2>$null
    return ($LASTEXITCODE -eq 0)
}

# ── Pre-flight ───────────────────────────────────────────────────────────────
foreach ($tool in @('minikube', 'kubectl')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Required tool '$tool' not found on PATH. See scripts/README.md for requirements."
    }
}

Write-Step "Starting Minikube profile '$Profile' (driver=$Driver, cpus=$Cpus, memory=${MemoryMB}Mi)"

$minikubeArgs = @(
    'start',
    '--profile', $Profile,
    '--driver', $Driver,
    '--cpus', "$Cpus",
    '--memory', "$MemoryMB"
)
if ($K8sVersion) { $minikubeArgs += @('--kubernetes-version', $K8sVersion) }

# Idempotent: on an already-running profile this exits fast ("already exists").
& minikube @minikubeArgs
if ($LASTEXITCODE -ne 0) {
    throw "minikube start failed (exit $LASTEXITCODE). Check Docker is running and the driver is healthy."
}

# ── Addons ────────────────────────────────────────────────────────────────────
if (-not $SkipAddons) {
    Write-Step "Enabling addons: ingress, metallb"
    & minikube addons enable ingress --profile $Profile 2>$null | Out-Null
    & minikube addons enable metallb --profile $Profile 2>$null | Out-Null
    Write-Ok "addons enabled (metallb needs an IP range — see note below)"
}

# ── Wait for cluster Ready ────────────────────────────────────────────────────
Write-Step "Waiting for the cluster to be Ready (timeout ${TimeoutSeconds}s)"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$ready = $false
while ((Get-Date) -lt $deadline) {
    & minikube status --profile $Profile 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 5
}
if (-not $ready) {
    throw "Minikube profile '$Profile' did not become Ready within ${TimeoutSeconds}s. Run 'minikube status -p $Profile'."
}
Write-Ok "cluster is Ready"

# ── Wait for the ingress-nginx controller (ingress addon) ────────────────────
# Modern minikube runs the addon in the ingress-nginx namespace; older
# releases run it in kube-system. Probe both, then poll a label search.
Write-Step "Waiting for the ingress-nginx controller"
$controller = @('deploy/ingress-nginx-controller', '-n', 'ingress-nginx')
if (-not (Test-NamespacedDeployment 'ingress-nginx-controller' 'ingress-nginx')) {
    if (Test-NamespacedDeployment 'ingress-nginx-controller' 'kube-system') {
        $controller = @('deploy/ingress-nginx-controller', '-n', 'kube-system')
    }
    else {
        $controller = @()
    }
}

if ($controller.Count -gt 0) {
    & kubectl wait @('--for=condition=available') @controller "--timeout=${TimeoutSeconds}s"
    if ($LASTEXITCODE -ne 0) {
        Write-Warn "ingress-nginx controller not available — check 'kubectl get pods -A'."
    }
    else {
        Write-Ok "ingress-nginx controller is available"
    }
}
else {
    Write-Warn "ingress addon controller not found yet; it may still be starting. Check: kubectl get pods -A"
}

# ── Summary ───────────────────────────────────────────────────────────────────
Write-Step "Local cluster ready"
Write-Ok "kubectl context:  kubectl config use-context $Profile"
Write-Ok "Apply the platform:  .\scripts\deploy\apply-overlay.ps1 -Env dev   (see scripts/README.md)"
Write-Ok "Bootstrap Argo CD:   .\scripts\deploy\bootstrap-argocd.ps1"
Write-Warn "Metallb: assign an IP range matching your Docker network — 'minikube addons configure metallb' shows the range to use"
Write-Warn "Image tags: cluster/overlays/* reference 'acr.azurecr.io' placeholders — see services/README.md ('acr.azurecr.io')"