#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# bootstrap-argocd.sh — install Argo CD, wait for the server, print the
# initial admin password, optionally open a port-forward to the UI.
# Bash 3.2+ compatible. No secrets.
#
# Mirrors step 1 of the bootstrap order in cluster/base/README.md. Newer
# kubectl versions reject remote kustomize bases — the fallback installs the
# official manifest from GitHub.
#
# Usage:
#   ./scripts/deploy/bootstrap-argocd.sh [--no-port-forward] [--version v2.12.3]
#                                        [--port 8080] [--timeout 300]
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

NO_PORT_FORWARD="${NO_PORT_FORWARD:-0}"
VERSION="${VERSION:-v2.12.3}"
LOCAL_PORT="${LOCAL_PORT:-8080}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-port-forward) NO_PORT_FORWARD=1; shift ;;
        --version)         VERSION="$2";      shift 2 ;;
        --port)            LOCAL_PORT="$2";   shift 2 ;;
        --timeout)         TIMEOUT_SECONDS="$2"; shift 2 ;;
        -h|--help)
            echo "Usage: $0 [--no-port-forward] [--version TAG] [--port N] [--timeout S]"
            exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

command -v kubectl >/dev/null 2>&1 || { echo "Required tool 'kubectl' not found on PATH." >&2; exit 1; }

step() { printf '==> %s\n' "$*"; }
ok()   { printf '    %s\n' "$*"; }
warn() { printf '    WARN: %s\n' "$*" >&2; }

# ── 1. Install Argo CD ───────────────────────────────────────────────────────
step "Installing Argo CD ($VERSION) into namespace 'argocd'"
kubectl create namespace argocd >/dev/null 2>&1 || true   # idempotent

if ! kubectl apply -k "$REPO_ROOT/cluster/base/argocd/install" >/dev/null 2>&1; then
    warn "kubectl apply -k cluster/base/argocd/install failed (remote base may be rejected) — falling back to the official install.yaml"
    kubectl apply -n argocd -f "https://raw.githubusercontent.com/argoproj/argo-cd/$VERSION/manifests/install.yaml"
fi

# ── 2. Wait for the server ───────────────────────────────────────────────────
step "Waiting for argocd-server (timeout ${TIMEOUT_SECONDS}s)"
kubectl -n argocd wait --for=condition=available deploy/argocd-server --timeout="${TIMEOUT_SECONDS}s"
ok "argocd-server is available"

# ── 3. Initial admin password ────────────────────────────────────────────────
step "Initial admin credentials"
B64="$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' 2>/dev/null || true)"
if [[ -n "$B64" ]]; then
    PASSWORD="$(printf '%s' "$B64" | base64 --decode 2>/dev/null || true)"
    ok "user: admin"
    ok "password (CLUSTER-SCOPED — change it after first login): $PASSWORD"
else
    warn "Could not read argocd-initial-admin-secret. Run:"
    warn "  kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d"
fi

# ── 4. (Optional) port-forward to the UI ─────────────────────────────────────
if [[ "$NO_PORT_FORWARD" -ne 1 ]]; then
    step "Opening port-forward to the Argo CD UI"
    ok "UI: https://localhost:${LOCAL_PORT}  (user: admin, password above)"
    warn "Keep this script running OR start the forward yourself:"
    warn "  kubectl -n argocd port-forward svc/argocd-server ${LOCAL_PORT}:443"
    kubectl -n argocd port-forward svc/argocd-server "${LOCAL_PORT}:443"
fi

# ── 5. Next steps ────────────────────────────────────────────────────────────
step "Next steps (bootstrap order from cluster/base/README.md)"
ok "1. kubectl apply -f cluster/base/argocd/app-of-apps.yaml"
ok "2. kubectl apply -f cluster/base/argocd/applications/"
ok "3. kubectl get applications -n argocd   # wait until Healthy"
ok "4. kubectl apply -k cluster/base"
ok "Or use: scripts/deploy/apply-overlay.sh --env dev  (renders + applies the whole overlay)"