#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# validate-manifests.sh — validate every manifest in the repo:
#   1. python YAML parse of all .yaml/.yml files (tests/manifests/test-yaml-parse.py)
#   2. kustomize build over cluster/, services/, observability/ and security/
#      (kubectl kustomize fallback when the binary is missing)
# Bash 3.2+ compatible. No secrets. Exit code non-zero on any failure.
#
# Usage:
#   ./scripts/tests/validate-manifests.sh [--skip-kustomize]
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SKIP_KUSTOMIZE="${SKIP_KUSTOMIZE:-0}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-kustomize) SKIP_KUSTOMIZE=1; shift ;;
        -h|--help)
            echo "Usage: $0 [--skip-kustomize]"
            exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

step() { printf '==> %s\n' "$*"; }
ok()   { printf '    %s\n' "$*"; }
fail() { printf '    %s\n' "$*" >&2; }
warn() { printf '    WARN: %s\n' "$*" >&2; }

FAILED=0

# ── 1. YAML parse ────────────────────────────────────────────────────────────
if command -v python >/dev/null 2>&1; then
    step "YAML parse check (python tests/manifests/test-yaml-parse.py)"
    if python "$REPO_ROOT/tests/manifests/test-yaml-parse.py"; then
        ok "YAML parse check passed"
    else
        fail "YAML parse check FAILED."
        FAILED=1
    fi
else
    fail "python not found on PATH — cannot run the YAML parse check."
    FAILED=1
fi

# ── 2. kustomize build ───────────────────────────────────────────────────────
if [[ "$SKIP_KUSTOMIZE" -ne 1 ]]; then
    TARGETS=(cluster/base cluster/overlays/dev cluster/overlays/staging cluster/overlays/prod observability security)
    for svc in "$REPO_ROOT"/services/*/; do
        NAME="$(basename "$svc")"
        [[ -f "$svc/k8s/base/kustomization.yaml" ]] && TARGETS+=("services/$NAME/k8s/base")
        [[ -f "$svc/k8s/overlays/prod/kustomization.yaml" ]] && TARGETS+=("services/$NAME/k8s/overlays/prod")
    done

    if command -v kustomize >/dev/null 2>&1; then
        step "Kustomize build check across ${#TARGETS[@]} targets (kustomize build)"
        for t in "${TARGETS[@]}"; do
            if kustomize build "$REPO_ROOT/$t" >/dev/null 2>&1; then
                ok "  OK   $t"
            else
                fail "  FAIL $t  (kustomize build)"
                FAILED=1
            fi
        done
    elif command -v kubectl >/dev/null 2>&1; then
        warn "kustomize binary missing — using 'kubectl kustomize' fallback"
        step "Kustomize build check across ${#TARGETS[@]} targets (kubectl kustomize)"
        for t in "${TARGETS[@]}"; do
            if kubectl kustomize "$REPO_ROOT/$t" >/dev/null 2>&1; then
                ok "  OK   $t"
            else
                fail "  FAIL $t  (kubectl kustomize)"
                FAILED=1
            fi
        done
    else
        warn "Neither kustomize nor kubectl found — kustomize build check SKIPPED (install either one)."
    fi
else
    warn "Kustomize build check skipped (--skip-kustomize)"
fi

step "Manifest validation"
if [[ "$FAILED" -ne 0 ]]; then
    fail "VALIDATION FAILED — fix the reported issues and re-run."
    exit 1
fi
ok "All manifest checks passed."
exit 0