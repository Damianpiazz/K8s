#!/usr/bin/env python3
"""
test-service-contract.py — verifies the uniform service layout contract
documented in services/README.md for every services/<svc>/:

    - Dockerfile exists
    - k8s/base contains deployment.yaml, service.yaml, hpa.yaml, pdb.yaml,
      serviceaccount.yaml, networkpolicy.yaml, kustomization.yaml
    - kustomization.yaml declares the image entry: name == newName and
      newName matches the pattern acr.azurecr.io/<svc>
    - deployment pod template carries the labels `app` and `env`
    - container securityContext: runAsNonRoot + seccompProfile (PSA
      restricted compatibility, security/README.md)
    - a probe against /actuator/health exists (liveness or readiness)
    - the service exposes a Service named <svc> on a named `http` port
      (the ServiceMonitor contract, observability/README.md)

Prints a per-service pass/fail table; exits non-zero if any check fails.

Usage:
    python tests/manifests/test-service-contract.py [<repo-root>]
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

BASE_REQUIRED = [
    "deployment.yaml",
    "service.yaml",
    "hpa.yaml",
    "pdb.yaml",
    "serviceaccount.yaml",
    "networkpolicy.yaml",
    "kustomization.yaml",
]


def load_yaml(path: Path):
    with path.open("r", encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def check_service(root: Path, svc_dir: Path) -> list[str]:
    """Returns a list of (one-line) failure descriptions; empty = pass."""
    svc = svc_dir.name
    failures = []
    base = svc_dir / "k8s" / "base"

    def missing(what: str) -> None:
        failures.append(f"{svc}: missing {what}")

    # 1. Dockerfile
    if not (svc_dir / "Dockerfile").is_file():
        missing("Dockerfile")

    # 2. k8s/base required manifests
    if not base.is_dir():
        missing("k8s/base directory")
        return failures
    for name in BASE_REQUIRED:
        if not (base / name).is_file():
            missing(f"k8s/base/{name}")

    # 3. kustomization image entry: name == newName == acr.azurecr.io/<svc>
    kust_path = base / "kustomization.yaml"
    if kust_path.is_file():
        kust = load_yaml(kust_path) or {}
        images = kust.get("images") or []
        expected = f"acr.azurecr.io/{svc}"
        matching = [i for i in images if i.get("name") == expected and i.get("newName") == expected]
        if not matching:
            failures.append(
                f"{svc}: kustomization images entry missing name==newName=={expected} "
                "(CD contract: cd.yml matches newName to rewrite newTag)"
            )

    # 4. deployment: labels app + env in pod template, securityContext,
    #    /actuator/health probe, image pattern
    dep_path = base / "deployment.yaml"
    if dep_path.is_file():
        dep = load_yaml(dep_path) or {}
        spec = dep.get("spec") or {}
        template = spec.get("template") or {}
        tmeta = template.get("metadata") or {}
        labels = tmeta.get("labels") or {}
        for label in ("app", "env"):
            if label not in labels:
                failures.append(f"{svc}: deployment pod template missing label '{label}'")
        if labels.get("app") != svc:
            failures.append(f"{svc}: deployment pod label 'app' should equal '{svc}' (found '{labels.get('app')}')")

        containers = (template.get("spec") or {}).get("containers") or []
        for container in containers:
            cname = container.get("name", "?")
            # image pattern
            image = container.get("image") or ""
            if not image.startswith(f"acr.azurecr.io/{svc}:"):
                failures.append(f"{svc}: container '{cname}' image '{image}' does not match acr.azurecr.io/{svc}:*")
            # securityContext: runAsNonRoot + seccompProfile (PSA restricted)
            sc = container.get("securityContext") or {}
            if sc.get("runAsNonRoot") is not True:
                failures.append(f"{svc}: container '{cname}' securityContext.runAsNonRoot must be true")
            seccomp = sc.get("seccompProfile") or {}
            if seccomp.get("type") != "RuntimeDefault":
                failures.append(f"{svc}: container '{cname}' securityContext.seccompProfile.type must be RuntimeDefault")
            # probe on a health endpoint. Two app families exist:
            #   - Spring Boot services expose /actuator/health (actuator)
            #   - auth (Keycloak/Quarkus) exposes /health/live + /health/ready
            probes = []
            for probe_name in ("livenessProbe", "readinessProbe"):
                probe = container.get(probe_name) or {}
                http_get = probe.get("httpGet") or {}
                path = http_get.get("path", "")
                if path.startswith("/actuator/health") or path.startswith("/health"):
                    probes.append(probe_name)
            if not probes:
                failures.append(
                    f"{svc}: container '{cname}' has no health probe "
                    "(expected /actuator/health for Spring Boot, /health/* for Keycloak)"
                )
    # 5. service: named http port (ServiceMonitor contract)
    svc_path = base / "service.yaml"
    if svc_path.is_file():
        s = load_yaml(svc_path) or {}
        if s.get("metadata", {}).get("name") != svc:
            failures.append(f"{svc}: Service name should be '{svc}'")
        ports = (s.get("spec") or {}).get("ports") or []
        if not any(p.get("name") == "http" for p in ports):
            failures.append(f"{svc}: Service has no port named 'http' (ServiceMonitor contract)")

    return failures


def main() -> int:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else REPO_ROOT
    services_dir = root / "services"
    svcs = sorted(p for p in services_dir.iterdir() if p.is_dir()) if services_dir.is_dir() else []

    print(f"Services found: {len(svcs)}")
    print(f"{'SERVICE':<22} {'RESULT':<8} DETAIL")
    print("-" * 80)

    total_failures = 0
    for svc_dir in svcs:
        svc = svc_dir.name
        problems = check_service(root, svc_dir)
        if problems:
            total_failures += len(problems)
            print(f"{svc:<22} FAIL    {problems[0]}")
            for extra in problems[1:]:
                print(f"{'':<22} {'':<8} {extra}")
        else:
            print(f"{svc:<22} PASS")

    print("-" * 80)
    if total_failures:
        print(f"SERVICE CONTRACT CHECK FAILED — {total_failures} issue(s) across {len(svcs)} service(s)")
        print("Run the per-service checks described in services/README.md; fix the contract, not the test.")
        return 1
    print(f"SERVICE CONTRACT CHECK PASSED — all {len(svcs)} services match the layout contract")
    return 0


if __name__ == "__main__":
    sys.exit(main())