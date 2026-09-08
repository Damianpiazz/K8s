#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# sync-argocd-app.sh — sync an Argo CD Application with prune via the argocd
# CLI. Bash 3.2+ compatible. No secrets.
#
# Manual equivalent of the CD pipeline's Argo CD job (cd.yml). Requires a
# logged-in argocd CLI.
#
# Usage:
#   ./scripts/deploy/sync-argocd-app.sh [--app ecommerce-prod] [--no-prune]
#                                       [--server argocd.example.com:443]
#                                       [--timeout 300s]
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

APP_NAME="${APP_NAME:-ecommerce-apps}"
NO_PRUNE="${NO_PRUNE:-0}"
SERVER="${SERVER:-}"
TIMEOUT="${TIMEOUT:-300s}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --app)      APP_NAME="$2"; shift 2 ;;
        --no-prune) NO_PRUNE=1;    shift ;;
        --server)   SERVER="$2";   shift 2 ;;
        --timeout)  TIMEOUT="$2";  shift 2 ;;
        -h|--help)
            echo "Usage: $0 [--app NAME] [--no-prune] [--server URL] [--timeout DURATION]"
            exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

command -v argocd >/dev/null 2>&1 || {
    echo "Required tool 'argocd' (CLI) not found on PATH." >&2
    echo "Install: https://argo-cd.readthedocs.io/en/stable/cli_installation/" >&2
    exit 1
}

SERVER_ARGS=()
[[ -n "$SERVER" ]] && SERVER_ARGS=(--server "$SERVER")

echo "==> Syncing Argo CD Application '$APP_NAME'"

SYNC_ARGS=(app sync "$APP_NAME")
[[ "$NO_PRUNE" -ne 1 ]] && SYNC_ARGS+=(--prune)
SYNC_ARGS+=(--timeout "$TIMEOUT")
SYNC_ARGS+=("${SERVER_ARGS[@]}")

argocd "${SYNC_ARGS[@]}"

echo "==> Application status"
argocd app get "$APP_NAME" "${SERVER_ARGS[@]}"
echo "    If the app shows OutOfSync, sync again or check the app's source path (cluster/overlays/<env>)."