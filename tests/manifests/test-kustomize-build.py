#!/usr/bin/env python3
"""
test-kustomize-build.py — runs `kustomize build` (or `kubectl kustomize`) over
every kustomization in the repo:

    cluster/overlays/{dev,staging,prod}, cluster/base, observability, security,
    and every services/*/k8s/base + services/*/k8s/overlays/prod

Skips cleanly with a warning when neither kustomize nor kubectl is present.
Exits non-zero if any target fails to render.

Usage:
    python tests/manifests/test-kustomize-build.py [<repo-root>]
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TIMEOUT_SECONDS = 120


def collect_targets(root: Path) -> list[Path]:
    targets = [
        root / "cluster" / "base",
        root / "cluster" / "overlays" / "dev",
        root / "cluster" / "overlays" / "staging",
        root / "cluster" / "overlays" / "prod",
        root / "observability",
        root / "security",
    ]
    services_dir = root / "services"
    if services_dir.is_dir():
        for svc in sorted(p for p in services_dir.iterdir() if p.is_dir()):
            base = svc / "k8s" / "base"
            if (base / "kustomization.yaml").is_file():
                targets.append(base)
            prod = svc / "k8s" / "overlays" / "prod"
            if (prod / "kustomization.yaml").is_file():
                targets.append(prod)
    return [t for t in targets if (t / "kustomization.yaml").is_file()]


def main() -> int:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else REPO_ROOT

    # Resolve the render command: kustomize build <dir> | kubectl kustomize <dir>
    if shutil.which("kustomize"):
        cmd_base = ["kustomize", "build"]
        engine = "kustomize"
    elif shutil.which("kubectl"):
        cmd_base = ["kubectl", "kustomize"]
        engine = "kubectl kustomize (fallback)"
    else:
        print("WARNING: neither 'kustomize' nor 'kubectl' found on PATH — skipping kustomize build check.")
        print("Install kustomize (https://kubectl.docs.kubernetes.io/installation/kustomize/) to enable it.")
        print("KUSTOMIZE BUILD CHECK SKIPPED")
        return 0

    targets = collect_targets(root)
    print(f"Using engine: {engine}")
    print(f"Kustomization targets: {len(targets)}")

    failures = []
    for target in targets:
        rel = target.relative_to(root).as_posix()
        try:
            result = subprocess.run(
                cmd_base + [str(target)],
                capture_output=True,
                text=True,
                timeout=TIMEOUT_SECONDS,
            )
        except subprocess.TimeoutExpired:
            failures.append((rel, f"timed out after {TIMEOUT_SECONDS}s"))
            print(f"  TIMEOUT {rel}")
            continue
        if result.returncode == 0:
            print(f"  OK      {rel}")
        else:
            failures.append((rel, (result.stderr or result.stdout).strip().splitlines()[-1] if (result.stderr or result.stdout) else f"exit {result.returncode}"))
            print(f"  FAIL    {rel}")

    if failures:
        print("\nKUSTOMIZE BUILD CHECK FAILED:")
        for rel, err in failures:
            print(f"  - {rel}: {err}")
        return 1
    print("\nKUSTOMIZE BUILD CHECK PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())