#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Runs `mvn -B test` for every service under services/ (or a single one)
    and reports pass/fail per service. Exit code is non-zero if any test run
    fails.

.DESCRIPTION
    Local equivalent of the ci.yml "Test <service>" matrix job. Uses mvn from
    the PATH, or the Maven wrapper (mvnw) if present at the repo root or inside
    the service. Services without a pom.xml are skipped with a note.

.EXAMPLE
    .\scripts\tests\run-unit-tests.ps1
    .\scripts\tests\run-unit-tests.ps1 -Service catalog-svc
    .\scripts\tests\run-unit-tests.ps1 -Skip catalog-svc,analytics-svc

.NOTES
    PowerShell 5.1 compatible. Requires Java 17 + Maven (or mvnw) — see
    scripts/README.md. No secrets. Does NOT touch the .github CI definitions.
#>
[CmdletBinding()]
param(
    [string]$Service = '',          # optional: single service to test
    [string[]]$Skip = @()           # optional: services to exclude
)

$ErrorActionPreference = 'Stop'

function Write-Step { param([string]$Msg) Write-Host "==> $Msg" -ForegroundColor Cyan }
function Write-Pass { param([string]$Msg) Write-Host $Msg -ForegroundColor Green }
function Write-Fail { param([string]$Msg) Write-Host $Msg -ForegroundColor Red }
function Write-Warn { param([string]$Msg) Write-Host $Msg -ForegroundColor Yellow }

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ServicesDir = Join-Path $RepoRoot 'services'

if (-not (Test-Path $ServicesDir)) {
    throw "services/ not found at $ServicesDir — run this script from the repo."
}

# Locate the Maven executable: mvn on PATH, else mvnw at repo root,
# else services\mvnw (unlikely but covered).
$mvn = (Get-Command mvn -ErrorAction SilentlyContinue).Source
if (-not $mvn) {
    foreach ($candidate in @((Join-Path $RepoRoot 'mvnw.cmd'), (Join-Path $RepoRoot 'mvnw'))) {
        if (Test-Path $candidate) { $mvn = $candidate; break }
    }
}
if (-not $mvn) {
    throw "Neither 'mvn' nor a Maven wrapper (mvnw) was found. Install Maven (https://maven.apache.org) or add a wrapper. See scripts/README.md."
}
Write-Step "Using Maven: $mvn"

# Discover services (dirs directly under services/).
$allServices = @(Get-ChildItem -Path $ServicesDir -Directory | ForEach-Object { $_.Name } | Sort-Object)
if ($Service) {
    if ($allServices -notcontains $Service) {
        throw "Unknown service '$Service' (not a directory under services/)."
    }
    $allServices = @($Service)
}
$allServices = @($allServices | Where-Object { $_ -notin $Skip })

# ── Run ──────────────────────────────────────────────────────────────────────
$results = @()
$failed = 0
foreach ($svc in $allServices) {
    $svcDir  = Join-Path $ServicesDir $svc
    $pomFile = Join-Path $svcDir 'pom.xml'

    if (-not (Test-Path $pomFile)) {
        Write-Warn "  SKIP   $svc (no pom.xml)"
        $results += [pscustomobject]@{ Service = $svc; Status = 'SKIP (no pom)' }
        continue
    }

    Write-Step "Running tests for $svc  (mvn -B test --file services/$svc/pom.xml)"
    & $mvn -B test --file $pomFile
    if ($LASTEXITCODE -eq 0) {
        Write-Pass "  PASS   $svc"
        $results += [pscustomobject]@{ Service = $svc; Status = 'PASS' }
    }
    else {
        Write-Fail "  FAIL   $svc (exit $LASTEXITCODE)"
        $results += [pscustomobject]@{ Service = $svc; Status = "FAIL ($LASTEXITCODE)" }
        $failed++
    }
}

# ── Report ───────────────────────────────────────────────────────────────────
Write-Step "Summary"
$results | ForEach-Object {
    $mark = switch -Regex ($_.Status) {
        '^PASS' { 'PASS' }
        '^FAIL' { 'FAIL' }
        default { 'SKIP' }
    }
    $color = 'Yellow'
    if ($_.Status -like 'FAIL*') { $color = 'Red' }
    elseif ($_.Status -like 'PASS*') { $color = 'Green' }
    Write-Host ("  {0,-6} {1}" -f $mark, $_.Service) -ForegroundColor $color
}

$passedCount = @($results | Where-Object { $_.Status -like 'PASS*' }).Count
$skipCount   = @($results | Where-Object { $_.Status -like 'SKIP*' }).Count
Write-Host ""
Write-Host "  $passedCount passed, $failed failed, $skipCount skipped (of $($allServices.Count) selected)" -ForegroundColor $(if ($failed -gt 0) { 'Red' } else { 'Green' })

if ($failed -gt 0) {
    Write-Fail "  One or more services FAILED — see Maven output above."
    exit 1
}
exit 0