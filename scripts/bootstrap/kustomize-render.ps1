#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Renders a Kustomize overlay (dev/staging/prod) to a single multi-doc YAML
    file for inspection, using `kustomize build` or the `kubectl kustomize`
    fallback.

.DESCRIPTION
    Equivalent of `kustomize build cluster/overlays/<env>`. This is exactly
    what Argo CD renders server-side, so it is the fastest pre-flight check
    before applying an overlay.

.EXAMPLE
    .\scripts\bootstrap\kustomize-render.ps1 -Env dev
    .\scripts\bootstrap\kustomize-render.ps1 -Env prod -OutDir .\build\render

.NOTES
    PowerShell 5.1 compatible. Output goes to $env:TEMP\ecommerce-render by
    default (never into the repo). No secrets.
#>
[CmdletBinding()]
param(
    [ValidateSet('dev', 'staging', 'prod')]
    [string]$Env = 'dev',
    [string]$OutDir = (Join-Path $env:TEMP 'ecommerce-render')
)

$ErrorActionPreference = 'Stop'

if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
    throw "Required tool 'kubectl' not found on PATH (needed for the kustomize fallback). See scripts/README.md."
}

$RepoRoot   = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$OverlayDir = Join-Path $RepoRoot "cluster\overlays\$Env"
if (-not (Test-Path (Join-Path $OverlayDir 'kustomization.yaml'))) {
    throw "Overlay not found: $OverlayDir\kustomization.yaml"
}

$kustomize = Get-Command kustomize -ErrorAction SilentlyContinue
if ($kustomize) {
    $cmd = $kustomize.Source
    $useKubectl = $false
}
else {
    # kubectl ships a built-in kustomize (kubectl kustomize <dir>).
    $cmd = (Get-Command kubectl).Source
    $useKubectl = $true
}

$targetDir = Join-Path $OutDir $Env
New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
$outFile = Join-Path $targetDir 'rendered.yaml'

Write-Host "==> Rendering cluster/overlays/$Env" -ForegroundColor Cyan
if ($useKubectl) {
    Write-Host "    using fallback: kubectl kustomize (kustomize binary not found)" -ForegroundColor Yellow
    & $cmd kustomize $(Join-Path $RepoRoot "cluster\overlays\$Env") | Set-Content -Path $outFile -Encoding UTF8
}
else {
    & $cmd build (Join-Path $RepoRoot "cluster\overlays\$Env") | Set-Content -Path $outFile -Encoding UTF8
}
if ($LASTEXITCODE -ne 0) {
    throw "kustomize build failed for cluster/overlays/$Env (exit $LASTEXITCODE)."
}

$count = (Select-String -Path $outFile -Pattern '^---$' -AllMatches).Matches.Count
Write-Host "    OK — rendered $Env overlay ($count documents)" -ForegroundColor Green
Write-Host "    Output: $outFile" -ForegroundColor Green
Write-Host "    Inspect with: kubectl diff -f $outFile (optional, needs cluster)" -ForegroundColor Cyan