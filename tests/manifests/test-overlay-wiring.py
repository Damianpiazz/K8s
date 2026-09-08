#!/usr/bin/env python3
"""
test-overlay-wiring.py — verifies the environment overlay wiring contract:

    - every resources[] entry under cluster/overlays/<env>/ resolves to an
      existing kustomization (the ../../base trees, the service k8s trees,
      observability/, security/) or a plain YAML file in the overlay
    - the images[] list covers ALL services (name == newName == acr.azurecr.io/<svc>)
      so the CD pipeline (cd.yml) can rewrite newTag for every service
    - dev/staging reference each service's k8s/base; prod references
      k8s/overlays/prod
    - dev/staging/prod all reference the Phase 8 observability + security layers

Exits non-zero if any env fails.

Usage:
    python tests/manifests/test-overlay-wiring.py [<repo-root>]
"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("ERROR: PyYAML is required. Install it with:  python -m pip install pyyaml")
    sys.exit(1)

REPO_ROOT = Path(__file__).resolve().parents[2]
ENVS = ["dev", "staging", "prod"]


def load_yaml(path: Path):
    with path.open("r", encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def service_dirs(root: Path) -> list[str]:
    services_dir = root / "services"
    if not services_dir.is_dir():
        return []
    return sorted(p.name for p in services_dir.iterdir() if p.is_dir())


def main() -> int:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else REPO_ROOT
    services = service_dirs(root)
    overlays = root / "cluster" / "overlays"

    print(f"Services to cover in images[]: {len(services)}")
    print(f"{'ENV':<10} {'RESULT':<8} DETAIL")
    print("-" * 80)

    total_failures = 0
    for env in ENVS:
        env_dir = overlays / env
        kust_file = env_dir / "kustomization.yaml"
        problems = []
        if not kust_file.is_file():
            problems.append(f"{env}: missing kustomization.yaml")
            print(f"{env:<10} FAIL    {problems[0]}")
            total_failures += 1
            continue

        kust = load_yaml(kust_file) or {}

        # 1. resources resolve to a kustomization (directory) OR a plain YAML
        #    file (e.g. issuer-default.yaml, env-config.yaml live in the overlay)
        resources = kust.get("resources") or []
        if not resources:
            problems.append(f"{env}: no resources[] entries")
        for res in resources:
            target = (env_dir / res).resolve()
            if target.is_dir():
                if not (target / "kustomization.yaml").is_file():
                    problems.append(f"{env}: resources dir '{res}' is missing kustomization.yaml")
            elif not target.is_file():
                problems.append(f"{env}: resources entry '{res}' resolves to neither a kustomization dir nor a YAML file")

        # 2. images[] covers every service with the CD contract (name==newName)
        images = kust.get("images") or []
        image_new_names = {i.get("newName") for i in images}
        expected_names = {f"acr.azurecr.io/{svc}" for svc in services}
        missing_images = sorted(expected_names - image_new_names)
        if missing_images:
            problems.append(f"{env}: images[] missing entries for: {', '.join(missing_images)}")
        for i in images:
            if i.get("name") != i.get("newName"):
                problems.append(f"{env}: images entry '{i.get('name')}' has name != newName (CD rewrites by matching newName)")

        # 3. dev/staging use base, prod uses overlays/prod (service paths)
        service_resources = [r for r in resources if "/services/" in r or r.startswith("../../../services/")]
        for res in service_resources:
            if env == "prod":
                if "/k8s/base" in res:
                    problems.append(f"{env}: service resource '{res}' must point at k8s/overlays/prod, not k8s/base")
                if "/k8s/overlays/prod" not in res:
                    problems.append(f"{env}: service resource '{res}' does not point at k8s/overlays/prod")
            else:
                if "/k8s/overlays/prod" in res:
                    problems.append(f"{env}: service resource '{res}' must point at k8s/base (only prod uses the prod overlay)")

        # 4. observability + security wired in every env (Phase 8)
        for layer in ("../../../observability", "../../../security"):
            if layer not in resources:
                problems.append(f"{env}: missing resources entry '{layer}' (Phase 8 layer)")

        if problems:
            total_failures += len(problems)
            print(f"{env:<10} FAIL    {problems[0]}")
            for extra in problems[1:]:
                print(f"{'':<10} {'':<8} {extra}")
        else:
            print(f"{env:<10} PASS    {len(resources)} resources, {len(images)} images, base/prod wiring correct")

    print("-" * 80)
    if total_failures:
        print(f"OVERLAY WIRING CHECK FAILED — {total_failures} issue(s)")
        print("Symptom cheat-sheet: missing image entry -> cd.yml skips that service's tag bump;")
        print("base-vs-prod mismatch -> wrong overlay rendered for the env.")
        return 1
    print("OVERLAY WIRING CHECK PASSED — dev/staging/prod are wired consistently")
    return 0


if __name__ == "__main__":
    sys.exit(main())