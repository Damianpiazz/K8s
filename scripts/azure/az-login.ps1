#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Authenticates to Azure (az login), selects a subscription, and fetches the
    AKS kubeconfig (az aks get-credentials) for the target environment.

.DESCRIPTION
    Wraps the manual steps of the CD pipeline's "Azure login" + "Set AKS
    context" jobs (see .github/workflows/cd.yml). Reads identifiers from
    parameters; optionally pulls resource_group_name / cluster_name straight
    from `terraform output` in infra/terraform/envs/<env>.

.EXAMPLE
    .\scripts\azure\az-login.ps1 -Env dev -SubscriptionId <YOUR-SUBSCRIPTION-ID>
    .\scripts\azure\az-login.ps1 -Env staging -UseTerraformOutputs
    .\scripts\azure\az-login.ps1 -Env prod -ResourceGroup <RG> -ClusterName <AKS>

.NOTES
    PowerShell 5.1 compatible. No secrets — you must already have Azure CLI
    installed and the subscription id handy (az account list shows them).
#>
[CmdletBinding()]
param(
    [ValidateSet('dev', 'staging', 'prod')]
    [string]$Env = 'dev',
    [string]$SubscriptionId = '',          # optional: 'az account list -o table'
    [string]$ResourceGroup = '',           # defaults per env (placeholders below)
    [string]$ClusterName = '',             # defaults per env (placeholders below)
    [switch]$UseTerraformOutputs           # prefer `terraform output` values
)

$ErrorActionPreference = 'Stop'

function Write-Step { param([string]$Msg) Write-Host "==> $Msg" -ForegroundColor Cyan }
function Write-Ok   { param([string]$Msg) Write-Host "    $Msg" -ForegroundColor Green }
function Write-Warn { param([string]$Msg) Write-Host "    WARN: $Msg" -ForegroundColor Yellow }

if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
    throw "Required tool 'az' (Azure CLI) not found on PATH. See scripts/README.md."
}

# Env-derived defaults — REPLACE with the real names once terraform has
# provisioned the environment (terraform output resource_group_name).
if (-not $ResourceGroup) { $ResourceGroup = "rg-ecommerce-$Env-PLACEHOLDER" }
if (-not $ClusterName)   { $ClusterName   = "aks-ecommerce-$Env-PLACEHOLDER" }

# Optional: source identifiers from terraform output (authoritative).
if ($UseTerraformOutputs) {
    $tfDir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path "infra\terraform\envs\$Env"
    if (-not (Get-Command terraform -ErrorAction SilentlyContinue)) {
        Write-Warn "terraform not found — continuing with parameter/default values"
    }
    elseif (-not (Test-Path $tfDir)) {
        Write-Warn "terraform env dir not found ($tfDir) — continuing with parameter/default values"
    }
    else {
        $rg  = (& terraform -chdir=$tfDir output -raw resource_group_name 2>$null) -join ''
        $cName = (& terraform -chdir=$tfDir output -raw cluster_name 2>$null) -join ''
        if ($rg)    { $ResourceGroup = $rg;    Write-Ok "resource group (from terraform): $ResourceGroup" }
        if ($cName) { $ClusterName   = $cName; Write-Ok "cluster name (from terraform): $ClusterName" }
    }
}

# ── 1. Login ─────────────────────────────────────────────────────────────────
Write-Step "Logging in to Azure"
& az login
if ($LASTEXITCODE -ne 0) { throw "az login failed (exit $LASTEXITCODE)." }

# ── 2. Select subscription ───────────────────────────────────────────────────
if ($SubscriptionId) {
    Write-Step "Selecting subscription $SubscriptionId"
    & az account set --subscription $SubscriptionId
    if ($LASTEXITCODE -ne 0) { throw "az account set failed — is the subscription id correct?" }
    Write-Ok "active subscription: $SubscriptionId"
}
else {
    Write-Warn "No -SubscriptionId provided — using your default subscription:"
    & az account show --query "{name:name, id:id}" -o table
}

# ── 3. Fetch the AKS kubeconfig ──────────────────────────────────────────────
Write-Step "Fetching AKS credentials (RG=$ResourceGroup, cluster=$ClusterName)"
if ($ResourceGroup -match 'PLACEHOLDER') {
    Write-Warn "Resource group name still contains PLACEHOLDER — pass -ResourceGroup/-ClusterName or -UseTerraformOutputs."
}
& az aks get-credentials --resource-group $ResourceGroup --name $ClusterName --overwrite-existing
if ($LASTEXITCODE -ne 0) {
    throw "az aks get-credentials failed (exit $LASTEXITCODE). Verify the RG/cluster names and that your account has access."
}

# ── 4. Sanity ────────────────────────────────────────────────────────────────
& kubectl config current-context
Write-Step "Done — context ready"
Write-Ok "Apply the platform:   .\scripts\deploy\apply-overlay.ps1 -Env $Env"
Write-Ok "Bootstrap Argo CD:    .\scripts\deploy\bootstrap-argocd.ps1"
Write-Ok "Manual CD fallback:   .\scripts\azure\deploy-cd-manual.ps1 -Env $Env"