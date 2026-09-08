#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# apply-overlay.sh — apply a cluster overlay with pre-checks: kubectl present,
# overlay exists, kustomize renders cleanly. Bash 3.2+ compatible. No secrets.
#
# WARNING: 'latest' image tags are rejected by the Kyverno disallow-latest-tag
# policy in namespace ecommerce — pin tags first (deploy-cd-manual.ps1) or
# expect the apply to fail on those Pods.
#
# Usage:
#   ./scripts/deploy/apply-overlay.sh [--env dev|staging|prod] [--dry-run] [--skip-render]
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENV_NAME="${ENV_NAME:-dev}"
DRY_RUN="${DRY_RUN:-0}"
SKIP_RENDER="${SKIP_RENDER:-0}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --env)         ENV_NAME="$2"; shift 2 ;;
        --dry-run)     DRY_RUN=1; shift ;;
        --skip-render) SKIP_RENDER=1; shift ;;
        -h|--help)
            echo "Usage: $0 [--env dev|staging|prod] [--dry-run] [--skip-render]"
            exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

case "$ENV_NAME" in
    dev|staging|prod) ;;
    *) echo "Invalid env '$ENV_NAME' — use dev, staging or prod." >&2; exit 2 ;;
esac

command -v kubectl >/dev/null 2>&1 || { echo "Required tool 'kubectl' not found on PATH." >&2; exit 1; }

OVERLAY_DIR="$REPO_ROOT/cluster/overlays/$ENV_NAME"
[[ -f "$OVERLAY_DIR/kustomization.yaml" ]] || { echo "Overlay not found: $OVERLAY_DIR/kustomization.yaml" >&2; exit 1; }

step() { printf '==> %s\n' "$*"; }
ok()   { printf '    %s\n' "$*"; }
warn() { printf '    WARN: %s\n' "$*" >&2; }

if [[ "$SKIP_RENDER" -ne 1 ]]; then
    step "Render check: cluster/overlays/$ENV_NAME"
    if command -v kustomize >/dev/null 2>&1; then
        if ! kustomize build "$OVERLAY_DIR" >/dev/null 2>&1; then
            echo "kustomize build failed for cluster/overlays/$ENV_NAME. Fix the manifests first." >&2
            exit 1
        fi
    else
        warn "kustomize binary missing — using 'kubectl kustomize' fallback"
        if ! kubectl kustomize "$OVERLAY_DIR" >/dev/null 2>&1; then
            echo "kubectl kustomize failed for cluster/overlays/$ENV_NAME. Fix the manifests first." >&2
            exit 1
        fi
    fi
    ok "render OK"
fi

warn "namespace ecommerce enforces Kyverno 'disallow-latest-tag' — images still at :latest will be rejected."
warn "Pin tags first (scripts/azure/deploy-cd-manual.ps1) or expect the apply to fail on those Pods."

step "Applying cluster/overlays/$ENV_NAME to the current kubectl context"
if [[ "$DRY_RUN" -eq 1 ]]; then
    kubectl apply -k "$OVERLAY_DIR" --dry-run=client
else
    kubectl apply -k "$OVERLAY_DIR"
fi

step "Waiting for the ecommerce namespace rollouts (best effort)"
kubectl rollout status deployment -n ecommerce --timeout=180s >/dev/null 2>&1 \
    && ok "all ecommerce Deployments rolled out" \
    || warn "Some Deployments are not fully rolled out yet. Check: kubectl get pods -n ecommerce"

step "Done"
ok "Verify: kubectl get pods -n ecommerce"
ok "Ingress (after DNS/domain is set): kubectl get ingress -n ecommerce"