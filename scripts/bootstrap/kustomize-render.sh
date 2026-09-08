#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# kustomize-render.sh — render a Kustomize overlay (dev/staging/prod) into a
# single multi-doc YAML file for inspection, using `kustomize build` or the
# `kubectl kustomize` fallback. Bash 3.2+ compatible. No secrets.
#
# Usage:
#   ./scripts/bootstrap/kustomize-render.sh [--env dev|staging|prod] [--out DIR]
# Default output: ${TMPDIR:-/tmp}/ecommerce-render/<env>/rendered.yaml
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

ENV_NAME="${ENV_NAME:-dev}"
OUT_DIR="${OUT_DIR:-${TMPDIR:-/tmp}/ecommerce-render}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --env) ENV_NAME="$2"; shift 2 ;;
        --out) OUT_DIR="$2";  shift 2 ;;
        -h|--help)
            echo "Usage: $0 [--env dev|staging|prod] [--out DIR]"
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

USE_KUBECTL=0
if command -v kustomize >/dev/null 2>&1; then
    RENDER_CMD=(kustomize build)
else
    # kubectl ships a built-in kustomize (kubectl kustomize <dir>).
    RENDER_CMD=(kubectl kustomize)
    USE_KUBECTL=1
fi

TARGET_DIR="$OUT_DIR/$ENV_NAME"
mkdir -p "$TARGET_DIR"
OUT_FILE="$TARGET_DIR/rendered.yaml"

echo "==> Rendering cluster/overlays/$ENV_NAME"
if [[ "$USE_KUBECTL" -eq 1 ]]; then
    echo "    using fallback: kubectl kustomize (kustomize binary not found)"
fi

"${RENDER_CMD[@]}" "$OVERLAY_DIR" > "$OUT_FILE"
DOCS=$(grep -c '^---' "$OUT_FILE" || true)
echo "    OK — rendered $ENV_NAME overlay ($DOCS documents)"
echo "    Output: $OUT_FILE"
echo "    Inspect with: kubectl diff -f $OUT_FILE (optional, needs cluster)"