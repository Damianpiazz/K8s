#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# run-unit-tests.sh — run `mvn -B test` per service and report pass/fail.
# Bash 3.2+ compatible. No secrets. Exit code non-zero on any failure.
#
# Local equivalent of the ci.yml "Test <service>" matrix job.
#
# Usage:
#   ./scripts/tests/run-unit-tests.sh [--service NAME] [--skip NAME[,NAME...]]
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SERVICE="${SERVICE:-}"
SKIP_LIST="${SKIP_LIST:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --service) SERVICE="$2"; shift 2 ;;
        --skip)    SKIP_LIST="$2"; shift 2 ;;
        -h|--help)
            echo "Usage: $0 [--service NAME] [--skip NAME[,NAME...]]"
            exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

# Locate Maven: mvn on PATH, else mvnw at the repo root.
MVN="$(command -v mvn 2>/dev/null || true)"
if [[ -z "$MVN" ]]; then
    if [[ -x "$REPO_ROOT/mvnw" ]]; then MVN="$REPO_ROOT/mvnw";
    elif [[ -f "$REPO_ROOT/mvnw" ]]; then MVN="bash $REPO_ROOT/mvnw";
    fi
fi
if [[ -z "$MVN" ]]; then
    echo "Neither 'mvn' nor a Maven wrapper (mvnw) was found. Install Maven or add a wrapper. See scripts/README.md." >&2
    exit 1
fi
printf '==> Using Maven: %s\n' "$MVN"

# Discover services (directories under services/).
ALL_SERVICES=()
for dir in "$REPO_ROOT"/services/*/; do
    [[ -d "$dir" ]] || continue
    ALL_SERVICES+=("$(basename "$dir")")
done

if [[ -n "$SERVICE" ]]; then
    ALL_SERVICES=("$SERVICE")
fi

# Apply --skip list (comma-separated).
IFS=',' read -r -a SKIP_ARRAY <<< "$SKIP_LIST"

PASS=0; FAIL=0; SKIP=0

for svc in "${ALL_SERVICES[@]}"; do
    # skip?
    SKIPPED=0
    for s in "${SKIP_ARRAY[@]}"; do
        [[ -n "$s" && "$s" == "$svc" ]] && SKIPPED=1
    done
    if [[ "$SKIPPED" -eq 1 ]]; then
        printf '    SKIP   %s (excluded)\n' "$svc"
        SKIP=$((SKIP+1))
        continue
    fi

    POM="$REPO_ROOT/services/$svc/pom.xml"
    if [[ ! -f "$POM" ]]; then
        printf '    SKIP   %s (no pom.xml)\n' "$svc"
        SKIP=$((SKIP+1))
        continue
    fi

    printf '==> Running tests for %s  (mvn -B test --file services/%s/pom.xml)\n' "$svc" "$svc"
    if $MVN -B test --file "$POM"; then
        printf '    PASS   %s\n' "$svc"
        PASS=$((PASS+1))
    else
        printf '    FAIL   %s\n' "$svc"
        FAIL=$((FAIL+1))
    fi
done

printf '\n==> Summary: %s passed, %s failed, %s skipped (of %s selected)\n' \
    "$PASS" "$FAIL" "$SKIP" "${#ALL_SERVICES[@]}"

[[ "$FAIL" -eq 0 ]] || { echo "One or more services FAILED — see Maven output above." >&2; exit 1; }
exit 0