#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Syncs an Argo CD Application with prune via the argocd CLI.

.DESCRIPTION
    Manual equivalent of the CD pipeline's Argo CD job (.github/workflows/cd.yml
    step "Configure & sync Argo CD"). Requires a logged-in argocd CLI
    (argocd login <server> --username admin) or a configured --server/--auth-token.

    Naming: the CD pipeline syncs "ecommerce-<env>" (e.g. ecommerce-prod);
    the root platform Application from cluster/base is "ecommerce-apps".

.EXAMPLE
    .\scripts\deploy\sync-argocd-app.ps1 -AppName ecommerce-prod
    .\scripts\deploy\sync-argocd-app.ps1 -AppName ecommerce-prod -NoPrune

.NOTES
    PowerShell 5.1 compatible. No secrets — pass credentials via environment
    variables or an already-logged-in CLI context.
#>
[CmdletBinding()]
param(
    [string]$AppName = 'ecommerce-apps',
    [switch]$NoPrune,
    [string]$Server = '',       # optional: argocd --server argocd.example.com:443
    [string]$Timeout = '300s'
)

$ErrorActionPreference = 'Stop'

if (-not (Get-Command argocd -ErrorAction SilentlyContinue)) {
    throw "Required tool 'argocd' (CLI) not found on PATH. Install it: https://argo-cd.readthedocs.io/en/stable/cli_installation/"
}

$serverArgs = @()
if ($Server) { $serverArgs = @('--server', $Server) }

Write-Host "==> Syncing Argo CD Application '$AppName'" -ForegroundColor Cyan

$syncArgs = @('app', 'sync', $AppName)
if (-not $NoPrune) { $syncArgs += '--prune' }
$syncArgs += @('--timeout', $Timeout)
$syncArgs += $serverArgs

& argocd @syncArgs
if ($LASTEXITCODE -ne 0) {
    throw "argocd app sync failed (exit $LASTEXITCODE). Verify the CLI is logged in: argocd account get-user-info"
}

Write-Host "==> Application status" -ForegroundColor Cyan
& argocd app get $AppName @serverArgs
Write-Host "    If the app shows OutOfSync, sync again or check the app's source path (cluster/overlays/<env>)." -ForegroundColor Yellow