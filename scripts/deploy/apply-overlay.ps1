#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Applies a cluster overlay to the current kubectl context with pre-checks:
    kubectl present, overlay exists, kustomize renders cleanly.

.DESCRIPTION
    Direct GitOps-free apply (the CD pipeline uses Argo CD or kubectl apply -k
    itself — this script wraps the same command with safety checks for manual
    work). Use the render check even when the kustomize binary is missing: the
    kubectl kustomize fallback ships in kubectl.

    WARNING: cluster/overlays/<env> carries 'latest' image tags; the Kyverno
    policy disallow-latest-tag ENFORCES a non-latest tag in namespace
    ecommerce, so a raw apply may be rejected until the CD pipeline (or a
    manual deploy-cd-manual.ps1 run) pins real tags. The script warns about
    this before applying.

.EXAMPLE
    .\scripts\deploy\apply-overlay.ps1 -Env dev
    .\scripts\deploy\apply-overlay.ps1 -Env staging -SkipRenderCheck

.NOTES
    PowerShell 5.1 compatible. No secrets.
#>
[CmdletBinding()]
param(
    [ValidateSet('dev', 'staging', 'prod')]
    [string]$Env = 'dev',
    [switch]$SkipRenderCheck,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function Write-Step { param([string]$Msg) Write-Host "==> $Msg" -ForegroundColor Cyan }
function Write-Ok   { param([string]$Msg) Write-Host "    $Msg" -ForegroundColor Green }
function Write-Warn { param([string]$Msg) Write-Host "    WARN: $Msg" -ForegroundColor Yellow }

if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
    throw "Required tool 'kubectl' not found on PATH. See scripts/README.md."
}

$RepoRoot   = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$OverlayDir = Join-Path $RepoRoot "cluster\overlays\$Env"
if (-not (Test-Path (Join-Path $OverlayDir 'kustomization.yaml'))) {
    throw "Overlay not found: $OverlayDir\kustomization.yaml"
}

# ── Pre-check 1: render cleanly (kustomize, or kubectl kustomize fallback) ──
if (-not $SkipRenderCheck) {
    Write-Step "Render check: cluster/overlays/$Env"
    $kustomize = Get-Command kustomize -ErrorAction SilentlyContinue
    if ($kustomize) {
        & $kustomize.Source build $OverlayDir *> $null
    }
    else {
        Write-Warn "kustomize binary missing — using 'kubectl kustomize' fallback"
        & kubectl kustomize $OverlayDir *> $null
    }
    if ($LASTEXITCODE -ne 0) {
        throw "kustomize build failed for cluster/overlays/$Env (exit $LASTEXITCODE). Fix the manifests before applying."
    }
    Write-Ok "render OK"
}

# ── Pre-check 2: warn about the Kyverno latest-tag gate ─────────────────────
Write-Warn "namespace ecommerce enforces Kyverno 'disallow-latest-tag' — if any image still resolves to :latest, Pod creation will be rejected."
Write-Warn "Pin tags first (scripts/azure/deploy-cd-manual.ps1) or expect the apply to fail on those Pods."

# ── Apply ────────────────────────────────────────────────────────────────────
Write-Step "Applying cluster/overlays/$Env to the current kubectl context"
if ($DryRun) {
    & kubectl apply -k $OverlayDir --dry-run=client
}
else {
    & kubectl apply -k $OverlayDir
}
if ($LASTEXITCODE -ne 0) {
    throw "kubectl apply -k failed (exit $LASTEXITCODE)."
}

Write-Step "Waiting for the ecommerce namespace rollouts (best effort)"
$waitArgs = @('rollout', 'status', 'deployment', '-n', 'ecommerce', '--timeout=180s')
& kubectl @waitArgs 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Warn "Some Deployments are not fully rolled out yet. Check: kubectl get pods -n ecommerce"
}
else {
    Write-Ok "all ecommerce Deployments rolled out"
}

Write-Step "Done"
Write-Ok "Verify: kubectl get pods -n ecommerce"
Write-Ok "Ingress (after DNS/domain is set): kubectl get ingress -n ecommerce"