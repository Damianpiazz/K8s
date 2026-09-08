#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# az-login.sh — az login, select a subscription, fetch the AKS kubeconfig.
# Bash 3.2+ compatible. No secrets.
#
# Wraps the manual steps of the CD pipeline's "Azure login" + "Set AKS
# context" jobs (cd.yml).
#
# Usage:
#   ./scripts/azure/az-login.sh [--env dev|staging|prod] [--subscription ID]
#                               [--rg NAME] [--cluster NAME] [--terraform-outputs]
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENV_NAME="${ENV_NAME:-dev}"
SUBSCRIPTION_ID="${SUBSCRIPTION_ID:-}"
RESOURCE_GROUP="${RESOURCE_GROUP:-}"
CLUSTER_NAME="${CLUSTER_NAME:-}"
USE_TERRAFORM_OUTPUTS="${USE_TERRAFORM_OUTPUTS:-0}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --env)               ENV_NAME="$2"; shift 2 ;;
        --subscription)      SUBSCRIPTION_ID="$2"; shift 2 ;;
        --rg)                RESOURCE_GROUP="$2";  shift 2 ;;
        --cluster)           CLUSTER_NAME="$2";    shift 2 ;;
        --terraform-outputs) USE_TERRAFORM_OUTPUTS=1; shift ;;
        -h|--help)
            echo "Usage: $0 [--env dev|staging|prod] [--subscription ID] [--rg NAME] [--cluster NAME] [--terraform-outputs]"
            exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

case "$ENV_NAME" in
    dev|staging|prod) ;;
    *) echo "Invalid env '$ENV_NAME' — use dev, staging or prod." >&2; exit 2 ;;
esac

command -v az >/dev/null 2>&1 || { echo "Required tool 'az' (Azure CLI) not found on PATH." >&2; exit 1; }

step() { printf '==> %s\n' "$*"; }
ok()   { printf '    %s\n' "$*"; }
warn() { printf '    WARN: %s\n' "$*" >&2; }

# Env-derived defaults — REPLACE with the real names once terraform has
# provisioned the environment (terraform output resource_group_name).
[[ -n "$RESOURCE_GROUP" ]] || RESOURCE_GROUP="rg-ecommerce-${ENV_NAME}-PLACEHOLDER"
[[ -n "$CLUSTER_NAME" ]]   || CLUSTER_NAME="aks-ecommerce-${ENV_NAME}-PLACEHOLDER"

# Optional: pull identifiers from terraform output (authoritative).
if [[ "$USE_TERRAFORM_OUTPUTS" -eq 1 ]]; then
    TF_DIR="$REPO_ROOT/infra/terraform/envs/$ENV_NAME"
    if ! command -v terraform >/dev/null 2>&1; then
        warn "terraform not found — continuing with parameter/default values"
    elif [[ ! -d "$TF_DIR" ]]; then
        warn "terraform env dir not found ($TF_DIR) — continuing with parameter/default values"
    else
        RG_TF="$(terraform -chdir="$TF_DIR" output -raw resource_group_name 2>/dev/null || true)"
        CN_TF="$(terraform -chdir="$TF_DIR" output -raw cluster_name 2>/dev/null || true)"
        [[ -n "$RG_TF" ]] && { RESOURCE_GROUP="$RG_TF"; ok "resource group (from terraform): $RESOURCE_GROUP"; }
        [[ -n "$CN_TF" ]] && { CLUSTER_NAME="$CN_TF";   ok "cluster name (from terraform): $CLUSTER_NAME"; }
    fi
fi

# ── 1. Login ─────────────────────────────────────────────────────────────────
step "Logging in to Azure"
az login

# ── 2. Select subscription ───────────────────────────────────────────────────
if [[ -n "$SUBSCRIPTION_ID" ]]; then
    step "Selecting subscription $SUBSCRIPTION_ID"
    az account set --subscription "$SUBSCRIPTION_ID"
    ok "active subscription: $SUBSCRIPTION_ID"
else
    warn "No --subscription provided — using your default subscription:"
    az account show --query "{name:name, id:id}" -o table
fi

# ── 3. Fetch the AKS kubeconfig ──────────────────────────────────────────────
step "Fetching AKS credentials (RG=$RESOURCE_GROUP, cluster=$CLUSTER_NAME)"
if [[ "$RESOURCE_GROUP" == *PLACEHOLDER* ]]; then
    warn "Resource group name still contains PLACEHOLDER — pass --rg/--cluster or --terraform-outputs."
fi
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing

# ── 4. Sanity ────────────────────────────────────────────────────────────────
kubectl config current-context
step "Done — context ready"
ok "Apply the platform:   scripts/deploy/apply-overlay.sh --env $ENV_NAME"
ok "Bootstrap Argo CD:    scripts/deploy/bootstrap-argocd.sh"
ok "Manual CD fallback:   scripts/azure/deploy-cd-manual.ps1 -Env $ENV_NAME  (PowerShell)"