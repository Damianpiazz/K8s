#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# kind-start.sh — create a local Kind cluster (name "ecommerce") with the
# standard ingress-nginx provider setup and wait for the controller.
# Bash 3.2+ compatible. No secrets.
#
# Alternative to minikube — choose ONE. See kind-start.ps1 header for the
# rationale (repo GitOps path for ingress-nginx lives in cluster/base; this
# manifest is the local quickstart so the ingress works pre-Argo CD).
#
# Usage:
#   ./scripts/bootstrap/kind-start.sh [--cluster ecommerce] [--workers 1]
#                                     [--k8s-version v1.30.0] [--timeout 300]
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

CLUSTER_NAME="${CLUSTER_NAME:-ecommerce}"
WORKERS="${WORKERS:-1}"
K8S_VERSION="${K8S_VERSION:-}"
INGRESS_YAML_URL="${INGRESS_YAML_URL:-https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/kind/deploy.yaml}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --cluster)      CLUSTER_NAME="$2"; shift 2 ;;
        --workers)      WORKERS="$2";      shift 2 ;;
        --k8s-version)  K8S_VERSION="$2";  shift 2 ;;
        --ingress-url)  INGRESS_YAML_URL="$2"; shift 2 ;;
        --timeout)      TIMEOUT_SECONDS="$2"; shift 2 ;;
        -h|--help)
            echo "Usage: $0 [--cluster NAME] [--workers N] [--k8s-version V] [--ingress-url URL] [--timeout S]"
            exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

step() { printf '==> %s\n' "$*"; }
ok()   { printf '    %s\n' "$*"; }
warn() { printf '    WARN: %s\n' "$*" >&2; }

for tool in kind kubectl; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Required tool '$tool' not found on PATH. See scripts/README.md." >&2; exit 1; }
done

# ── Cluster config (kind is declarative) ─────────────────────────────────────
CONFIG_PATH="$(mktemp -t "kind-${CLUSTER_NAME}-config.XXXXXX.yaml")"
{
    printf 'kind: Cluster\n'
    printf 'apiVersion: kind.x-k8s.io/v1alpha4\n'
    printf 'name: %s\n' "$CLUSTER_NAME"
    printf 'nodes:\n'
    printf '%s\n' \
'- role: control-plane' \
'  kubeadmConfigPatches:' \
'    - |' \
'      kind: InitConfiguration' \
'      nodeRegistration:' \
'        kubeletExtraArgs:' \
'          node-labels: "ingress-ready=true"' \
'  extraPortMappings:' \
'    - containerPort: 80' \
'      hostPort: 80' \
'      protocol: TCP' \
'    - containerPort: 443' \
'      hostPort: 443' \
'      protocol: TCP'
    i=0
    while [[ $i -lt $WORKERS ]]; do
        printf '  - role: worker\n'
        i=$((i+1))
    done
    if [[ -n "$K8S_VERSION" ]]; then
        printf 'image: kindest/node:%s\n' "$K8S_VERSION"
    fi
} > "$CONFIG_PATH"

step "Creating kind cluster '$CLUSTER_NAME' (workers=$WORKERS)"
kind create cluster --name "$CLUSTER_NAME" --config "$CONFIG_PATH"
rm -f "$CONFIG_PATH"

step "Waiting for the cluster to be Ready (timeout ${TIMEOUT_SECONDS}s)"
DEADLINE=$(( $(date +%s) + TIMEOUT_SECONDS ))
READY=0
while [[ "$(date +%s)" -lt "$DEADLINE" ]]; do
    if kubectl cluster-info >/dev/null 2>&1; then READY=1; break; fi
    sleep 5
done
if [[ "$READY" -ne 1 ]]; then
    echo "Kind cluster '$CLUSTER_NAME' did not become Ready within ${TIMEOUT_SECONDS}s." >&2
    exit 1
fi
ok "cluster is Ready"

# ── ingress-nginx (official kind provider manifest) ──────────────────────────
step "Installing ingress-nginx (kind provider manifest)"
MANIFEST="$(mktemp -t "kind-${CLUSTER_NAME}-ingress-nginx.XXXXXX.yaml")"
if curl -fsSL --max-time 60 "$INGRESS_YAML_URL" -o "$MANIFEST"; then
    kubectl apply -f "$MANIFEST"
    rm -f "$MANIFEST"
else
    rm -f "$MANIFEST"
    warn "Could not fetch the kind ingress manifest: $INGRESS_YAML_URL"
    warn "Install ingress-nginx manually (see kubernetes.github.io/ingress-nginx/deploy) or bootstrap Argo CD to install the repo chart."
    exit 0
fi

step "Waiting for the ingress-nginx controller"
kubectl wait --namespace ingress-nginx --for=condition=ready pod \
    --selector=app.kubernetes.io/component=controller --timeout="${TIMEOUT_SECONDS}s" >/dev/null 2>&1 || true
kubectl -n ingress-nginx wait --for=condition=available deploy/ingress-nginx-controller \
    --timeout="${TIMEOUT_SECONDS}s" >/dev/null 2>&1 \
    && ok "ingress-nginx controller is available" \
    || warn "ingress-nginx controller not available — check 'kubectl get pods -n ingress-nginx'."

step "Kind cluster ready"
ok "kubectl context: kind-$CLUSTER_NAME"
ok "Ingress listens on host ports 80/443 — the api-gateway Ingress becomes reachable after the platform is applied."
ok "Apply the platform:  scripts/deploy/apply-overlay.sh --env dev"
ok "Bootstrap Argo CD:   scripts/deploy/bootstrap-argocd.sh"
warn "Kind has no LoadBalancer by default: use 'kubectl port-forward' for LoadBalancer-type services, or install MetalLB."