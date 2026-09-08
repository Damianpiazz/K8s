#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Creates a local Kind cluster (name "ecommerce") with the standard
    ingress-nginx provider setup for Kind, and waits for the controller.

.DESCRIPTION
    Alternative local cluster to minikube (choose ONE). Uses the kind ingress
    recipe: a control-plane node labelled "ingress-ready" plus host port
    mappings 80/443, then applies the official kind ingress-nginx manifest.

    NOTE on the repo's own ingress-nginx: cluster/base ships the GitOps path
    (Helm chart via Argo CD). This script is the local quickstart alternative
    so the ingress works before Argo CD is bootstrapped.

    Requirements: kind, kubectl, Docker — see scripts/README.md.

.EXAMPLE
    .\scripts\bootstrap\kind-start.ps1
    .\scripts\bootstrap\kind-start.ps1 -ClusterName ecommerce -Workers 1

.NOTES
    PowerShell 5.1 compatible. No secrets.
#>
[CmdletBinding()]
param(
    [string]$ClusterName = 'ecommerce',
    [int]$Workers = 1,
    [string]$K8sVersion = '',           # optional, e.g. 'v1.30.0'
    [string]$IngressYamlUrl = 'https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/kind/deploy.yaml',
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'

function Write-Step { param([string]$Msg) Write-Host "==> $Msg" -ForegroundColor Cyan }
function Write-Ok   { param([string]$Msg) Write-Host "    $Msg" -ForegroundColor Green }
function Write-Warn { param([string]$Msg) Write-Host "    WARN: $Msg" -ForegroundColor Yellow }

# ── Pre-flight ───────────────────────────────────────────────────────────────
foreach ($tool in @('kind', 'kubectl')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Required tool '$tool' not found on PATH. See scripts/README.md for requirements."
    }
}

# ── Cluster config (kind is declarative): ingress-ready node + port maps ─────
# The control-plane gets the ingress-ready node label (the official kind
# ingress-nginx manifest schedules the controller on it) plus host port
# mappings 80/443. Worker nodes stay unlabelled — the controller lands on
# the control-plane, which kind keeps schedulable.
$configPath = Join-Path $env:TEMP "kind-${ClusterName}-config.yaml"
$nodes = @(
    @"
- role: control-plane
  kubeadmConfigPatches:
    - |
      kind: InitConfiguration
      nodeRegistration:
        kubeletExtraArgs:
          node-labels: "ingress-ready=true"
  extraPortMappings:
    - containerPort: 80
      hostPort: 80
      protocol: TCP
    - containerPort: 443
      hostPort: 443
      protocol: TCP
"@
)
for ($i = 0; $i -lt $Workers; $i++) {
    $nodes += '  - role: worker'
}

$config = @"
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: $ClusterName
nodes:
$($nodes -join "`n")
"@
if ($K8sVersion) {
    # Pin the node image explicitly when a version was requested.
    $config = $config + "`n" + "image: kindest/node:$K8sVersion"
}
Set-Content -Path $configPath -Value $config -Encoding UTF8

Write-Step "Creating kind cluster '$ClusterName' (workers=$Workers)"
& kind create cluster --name $ClusterName --config $configPath
if ($LASTEXITCODE -ne 0) {
    throw "kind create cluster failed (exit $LASTEXITCODE). Check Docker is running."
}
Remove-Item -Path $configPath -Force -ErrorAction SilentlyContinue

# ── Wait for Ready ────────────────────────────────────────────────────────────
Write-Step "Waiting for the cluster to be Ready (timeout ${TimeoutSeconds}s)"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$ready = $false
while ((Get-Date) -lt $deadline) {
    $null = & kubectl cluster-info 2>$null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 5
}
if (-not $ready) {
    throw "Kind cluster '$ClusterName' did not become Ready within ${TimeoutSeconds}s."
}
Write-Ok "cluster is Ready"

# ── ingress-nginx (official kind provider manifest) ──────────────────────────
Write-Step "Installing ingress-nginx (kind provider manifest)"
$manifest = Join-Path $env:TEMP "kind-${ClusterName}-ingress-nginx.yaml"
try {
    Invoke-WebRequest -Uri $IngressYamlUrl -OutFile $manifest -UseBasicParsing -TimeoutSec 60
    & kubectl apply -f $manifest
    if ($LASTEXITCODE -ne 0) { throw "kubectl apply of ingress-nginx manifest failed." }
}
catch {
    Write-Warn "Could not fetch/apply the kind ingress manifest ($IngressYamlUrl)."
    Write-Warn "Install ingress-nginx manually (see kubernetes.github.io/ingress-nginx/deploy) or bootstrap Argo CD and let it install the repo chart."
    return
}
Remove-Item -Path $manifest -Force -ErrorAction SilentlyContinue

Write-Step "Waiting for the ingress-nginx controller"
& kubectl wait --namespace ingress-nginx --for=condition=ready pod --selector=app.kubernetes.io/component=controller --timeout="${TimeoutSeconds}s" 2>$null
& kubectl -n ingress-nginx wait --for=condition=available deploy/ingress-nginx-controller --timeout="${TimeoutSeconds}s"
if ($LASTEXITCODE -eq 0) { Write-Ok "ingress-nginx controller is available" }

# ── Summary ───────────────────────────────────────────────────────────────────
Write-Step "Kind cluster ready"
Write-Ok "kubectl context: kind-$ClusterName"
Write-Ok "Ingress listens on host ports 80/443 — the api-gateway Ingress (api.<domain>) becomes reachable after the platform is applied."
Write-Ok "Apply the platform:  .\scripts\deploy\apply-overlay.ps1 -Env dev"
Write-Ok "Bootstrap Argo CD:   .\scripts\deploy\bootstrap-argocd.ps1"
Write-Warn "Metallb/LoadBalancer: kind has no LB by default. For LoadBalancer-type Services use 'kubectl port-forward' or MetalLB via the manifest (out of scope here)."