#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# minikube-start.sh — start the local Minikube cluster (profile "ecommerce")
# with ingress + metallb addons and wait until the ingress-nginx controller
# is Ready. Bash 3.2+ compatible. No secrets.
#
# Env defaults (override with flags):
#   PROFILE   ecommerce      --profile
#   CPUS      4              --cpus
#   MEMORY    8192           --memory
#   DRIVER    docker         --driver
# 
# Usage examples:
#   ./scripts/bootstrap/minikube-start.sh
#   ./scripts/bootstrap/minikube-start.sh --cpus 6 --memory 12288
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

PROFILE="${PROFILE:-ecommerce}"
CPUS="${CPUS:-4}"
MEMORY="${MEMORY:-8192}"
DRIVER="${DRIVER:-docker}"
K8S_VERSION="${K8S_VERSION:-}"
SKIP_ADDONS="${SKIP_ADDONS:-0}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --profile)  PROFILE="$2";        shift 2 ;;
        --cpus)     CPUS="$2";           shift 2 ;;
        --memory)   MEMORY="$2";         shift 2 ;;
        --driver)   DRIVER="$2";         shift 2 ;;
        --k8s-version) K8S_VERSION="$2"; shift 2 ;;
        --skip-addons) SKIP_ADDONS=1;    shift ;;
        --timeout)  TIMEOUT_SECONDS="$2"; shift 2 ;;
        -h|--help)
            echo "Usage: $0 [--profile NAME] [--cpus N] [--memory MB] [--driver NAME] [--k8s-version V] [--skip-addons] [--timeout S]"
            exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

step() { printf '==> %s\n' "$*"; }
ok()   { printf '    %s\n' "$*"; }
warn() { printf '    WARN: %s\n' "$*" >&2; }

for tool in minikube kubectl; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Required tool '$tool' not found on PATH. See scripts/README.md." >&2; exit 1; }
done

step "Starting Minikube profile '$PROFILE' (driver=$DRIVER, cpus=$CPUS, memory=${MEMORY}Mi)"

MINIKUBE_ARGS=(start --profile "$PROFILE" --driver "$DRIVER" --cpus "$CPUS" --memory "$MEMORY")
[[ -n "$K8S_VERSION" ]] && MINIKUBE_ARGS+=(--kubernetes-version "$K8S_VERSION")

minikube "${MINIKUBE_ARGS[@]}"

if [[ "$SKIP_ADDONS" -ne 1 ]]; then
    step "Enabling addons: ingress, metallb"
    minikube addons enable ingress --profile "$PROFILE" >/dev/null 2>&1 || true
    minikube addons enable metallb --profile "$PROFILE" >/dev/null 2>&1 || true
    ok "addons enabled (metallb needs an IP range — see note below)"
fi

step "Waiting for the cluster to be Ready (timeout ${TIMEOUT_SECONDS}s)"
DEADLINE=$(( $(date +%s) + TIMEOUT_SECONDS ))
READY=0
while [[ "$(date +%s)" -lt "$DEADLINE" ]]; do
    if minikube status --profile "$PROFILE" >/dev/null 2>&1; then READY=1; break; fi
    sleep 5
done
if [[ "$READY" -ne 1 ]]; then
    echo "Minikube profile '$PROFILE' did not become Ready within ${TIMEOUT_SECONDS}s." >&2
    echo "Run: minikube status -p $PROFILE" >&2
    exit 1
fi
ok "cluster is Ready"

step "Waiting for the ingress-nginx controller"
CONTROLLER_FOUND=0
if kubectl get deploy ingress-nginx-controller -n ingress-nginx >/dev/null 2>&1; then
    NS=ingress-nginx
    CONTROLLER_FOUND=1
elif kubectl get deploy ingress-nginx-controller -n kube-system >/dev/null 2>&1; then
    NS=kube-system
    CONTROLLER_FOUND=1
fi

if [[ "$CONTROLLER_FOUND" -eq 1 ]]; then
    kubectl wait --for=condition=available "deploy/ingress-nginx-controller" -n "$NS" --timeout="${TIMEOUT_SECONDS}s" >/dev/null 2>&1 \
        && ok "ingress-nginx controller is available (namespace $NS)" \
        || warn "ingress-nginx controller not available — check 'kubectl get pods -A'."
else
    warn "ingress addon controller not found yet; it may still be starting. Check: kubectl get pods -A"
fi

step "Local cluster ready"
ok "kubectl context: kubectl config use-context $PROFILE"
ok "Apply the platform:  scripts/deploy/apply-overlay.sh --env dev   (see scripts/README.md)"
ok "Bootstrap Argo CD:   scripts/deploy/bootstrap-argocd.sh"
warn "Metallb: assign an IP range matching your Docker network — 'minikube addons configure metallb' shows the range to use"
warn "Image tags: cluster/overlays/* reference 'acr.azurecr.io' placeholders — see services/README.md"