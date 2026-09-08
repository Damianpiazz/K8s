#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Validates every manifest in the repo: (1) python YAML parse of all
    .yaml/.yml files, (2) kustomize build of cluster/, services/, observability/
    and security/ trees (kubectl kustomize fallback when the binary is missing).

.DESCRIPTION
    Aggregates the Python checks from tests/manifests/ into one local command:
      1. python tests\manifests\test-yaml-parse.py        (syntax + duplicate keys)
      2. kustomize build over: cluster/overlays/*, cluster/base, observability,
         security, and every services/*/k8s/{base,overlays/prod}
    The kustomize step is skipped with a warning when neither kustomize nor
    kubectl is available.

.EXAMPLE
    .\scripts\tests\validate-manifests.ps1
    .\scripts\tests\validate-manifests.ps1 -SkipKustomize

.NOTES
    PowerShell 5.1 compatible. Requires python + pyyaml (see scripts/README.md).
    No secrets. Does NOT touch .github CI definitions.
#>
[CmdletBinding()]
param(
    [switch]$SkipKustomize,
    [switch]$SkipYaml
)

$ErrorActionPreference = 'Stop'

function Write-Step { param([string]$Msg) Write-Host "==> $Msg" -ForegroundColor Cyan }
function Write-Ok   { param([string]$Msg) Write-Host "    $Msg" -ForegroundColor Green }
function Write-Fail { param([string]$Msg) Write-Host "    $Msg" -ForegroundColor Red }
function Write-Warn { param([string]$Msg) Write-Host "    WARN: $Msg" -ForegroundColor Yellow }

$RepoRoot    = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$TestsDir    = Join-Path $RepoRoot 'tests\manifests'
$python      = Get-Command python -ErrorAction SilentlyContinue

$failed = $false

# ── 1. YAML parse (all .yaml/.yml in the repo) ───────────────────────────────
if (-not $SkipYaml) {
    if (-not $python) {
        Write-Fail "python not found on PATH — cannot run the YAML parse check."
        $failed = $true
    }
    else {
        Write-Step "YAML parse check (python tests/manifests/test-yaml-parse.py)"
        & $python.Source (Join-Path $TestsDir 'test-yaml-parse.py')
        if ($LASTEXITCODE -ne 0) {
            Write-Fail "YAML parse check FAILED (exit $LASTEXITCODE)."
            $failed = $true
        }
        else {
            Write-Ok "YAML parse check passed"
        }
    }
}

# ── 2. kustomize build across the trees ─────────────────────────────────────
if (-not $SkipKustomize) {
    $kustomize = Get-Command kustomize -ErrorAction SilentlyContinue
    $kubectl   = Get-Command kubectl -ErrorAction SilentlyContinue

    $targets = @()
    $targets += 'cluster\base'
    $targets += 'cluster\overlays\dev', 'cluster\overlays\staging', 'cluster\overlays\prod'
    $targets += 'observability', 'security'
    foreach ($svc in @(Get-ChildItem (Join-Path $RepoRoot 'services') -Directory | ForEach-Object { $_.Name } | Sort-Object)) {
        $svcKust = Join-Path $RepoRoot "services\$svc\k8s\base"
        if (Test-Path (Join-Path $svcKust 'kustomization.yaml')) { $targets += "services\$svc\k8s\base" }
        $svcKustProd = Join-Path $RepoRoot "services\$svc\k8s\overlays\prod"
        if (Test-Path (Join-Path $svcKustProd 'kustomization.yaml')) { $targets += "services\$svc\k8s\overlays\prod" }
    }

    if ($kustomize) {
        Write-Step "Kustomize build check across $($targets.Count) targets (kustomize build)"
        foreach ($t in $targets) {
            & $kustomize.Source build (Join-Path $RepoRoot $t) *> $null
            if ($LASTEXITCODE -eq 0) { Write-Ok "  OK   $t" }
            else { Write-Fail "  FAIL $t  (kustomize build exit $LASTEXITCODE)"; $failed = $true }
        }
    }
    elseif ($kubectl) {
        Write-Warn "kustomize binary missing — using 'kubectl kustomize' fallback"
        Write-Step "Kustomize build check across $($targets.Count) targets (kubectl kustomize)"
        foreach ($t in $targets) {
            & kubectl kustomize (Join-Path $RepoRoot $t) *> $null
            if ($LASTEXITCODE -eq 0) { Write-Ok "  OK   $t" }
            else { Write-Fail "  FAIL $t  (kubectl kustomize exit $LASTEXITCODE)"; $failed = $true }
        }
    }
    else {
        Write-Warn "Neither kustomize nor kubectl found — kustomize build check SKIPPED (install either one; see scripts/README.md)."
    }
}

# ── Summary ──────────────────────────────────────────────────────────────────
Write-Step "Manifest validation"
if ($failed) {
    Write-Fail "VALIDATION FAILED — fix the reported issues and re-run."
    exit 1
}
Write-Ok "All manifest checks passed."
exit 0