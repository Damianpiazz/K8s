#!/usr/bin/env python3
"""
test-kustomize-build.py — runs `kustomize build` (or `kubectl kustomize`) over
every kustomization in the repo:

    cluster/base, cluster/overlays/{dev,staging,prod,local}, observability,
    security, and every services/*/k8s/base + services/*/k8s/overlays/prod

Skips cleanly with a warning when neither kustomize nor kubectl is present.
Exits non-zero if any target fails to render.

Additionally, for the full overlays (dev/staging/prod/local) it runs a wiring
assertion: every ServiceMonitor `endpoints[].port` must resolve to a named
port on the selected Service, and that Service's `targetPort` (when named)
must exist as a containerPort on the selected Deployment. Mismatches fail
loudly. The wiring check needs PyYAML; if it is not installed the check is
skipped with a warning (render checks still run).

Scope note: the wiring check is STRUCTURAL (names resolve end-to-end), not
semantic — it cannot know whether an app actually serves a metric on that
port/path. Runtime contracts (e.g., Keycloak serving /metrics) are verified
by scraping, not by this script.

Usage:
    python tests/manifests/test-kustomize-build.py [<repo-root>]
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TIMEOUT_SECONDS = 120

# Overlays that render ServiceMonitors together with their Services and
# Deployments, so the SM wiring assertion is meaningful there.
WIRING_TARGETS = {
    "cluster/overlays/dev",
    "cluster/overlays/staging",
    "cluster/overlays/prod",
    "cluster/overlays/local",
}

# Same overlays, with the env value each one must sign (fix 27): the base no
# longer hardcodes `env: production`; each overlay patches the real value on
# pod templates + the ecommerce Namespace, and no image may reference
# `:latest` after the render.
ENV_TARGETS = {
    "cluster/overlays/dev": "dev",
    "cluster/overlays/staging": "staging",
    "cluster/overlays/prod": "production",
    "cluster/overlays/local": "local",
}

try:
    import yaml
except ImportError:  # pragma: no cover - environment dependent
    yaml = None


def collect_targets(root: Path) -> list[Path]:
    targets = [
        root / "cluster" / "base",
        root / "cluster" / "overlays" / "dev",
        root / "cluster" / "overlays" / "staging",
        root / "cluster" / "overlays" / "prod",
        root / "cluster" / "overlays" / "local",
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


def _match_labels(selector: dict, labels: dict) -> bool:
    return all(labels.get(k) == v for k, v in selector.items())


def check_sm_wiring(docs: list, label: str) -> list[str]:
    """Assert every ServiceMonitor endpoint resolves to a real named port.

    Chain under test (Kubernetes contract):
        ServiceMonitor.spec.endpoints[].port (name)
            -> Service.spec.ports[].name
            -> Service.spec.ports[].targetPort (name)
            -> Deployment containerPort name

    Returns a list of human-readable errors (empty == all wired).
    """
    kinds: dict[str, list[dict]] = {}
    for doc in docs:
        if not isinstance(doc, dict) or "kind" not in doc:
            continue
        kinds.setdefault(doc["kind"], []).append(doc)

    errors: list[str] = []
    services = kinds.get("Service", [])
    deployments = kinds.get("Deployment", [])

    for sm in sorted(kinds.get("ServiceMonitor", []), key=lambda s: s["metadata"]["name"]):
        sm_name = sm["metadata"]["name"]
        sm_ns = sm["metadata"].get("namespace", "default")
        namespace_selector = sm.get("spec", {}).get("namespaceSelector", {}).get("matchNames")
        allowed_ns = namespace_selector or [sm_ns]
        selector = sm.get("spec", {}).get("selector", {}).get("matchLabels", {})

        matched = [
            svc
            for svc in services
            if svc["metadata"].get("namespace", "default") in allowed_ns
            and _match_labels(selector, svc.get("spec", {}).get("selector", {}))
        ]
        if not matched:
            errors.append(
                f"{label}: ServiceMonitor {sm_name} - no Service matches selector {selector} "
                f"in namespaces {allowed_ns}"
            )
            continue

        svc = matched[0]
        svc_name = svc["metadata"]["name"]
        svc_ns = svc["metadata"].get("namespace", "default")
        svc_ports = {p.get("name"): p for p in svc.get("spec", {}).get("ports", [])}
        for endpoint in sm.get("spec", {}).get("endpoints", []):
            port_name = endpoint.get("port")
            if not port_name or port_name not in svc_ports:
                errors.append(
                    f"{label}: ServiceMonitor {sm_name} - endpoint port '{port_name}' "
                    f"is not a named port of Service {svc_ns}/{svc_name}"
                )
                continue
            target = svc_ports[port_name].get("targetPort")
            if isinstance(target, str):
                svc_selector = svc.get("spec", {}).get("selector", {})
                deployment = next(
                    (
                        dep
                        for dep in deployments
                        if dep["metadata"].get("namespace", "default") == svc_ns
                        and dep.get("spec", {}).get("selector", {}).get("matchLabels", {}) == svc_selector
                    ),
                    None,
                )
                if deployment is None:
                    errors.append(
                        f"{label}: ServiceMonitor {sm_name} - no Deployment with pod selector "
                        f"{svc_selector} for Service {svc_ns}/{svc_name}"
                    )
                    continue
                container_ports = {
                    cp.get("name")
                    for container in deployment["spec"]["template"]["spec"].get("containers", [])
                    for cp in container.get("ports", [])
                }
                if target not in container_ports:
                    errors.append(
                        f"{label}: ServiceMonitor {sm_name} - Service {svc_ns}/{svc_name} "
                        f"targetPort '{target}' missing as containerPort in Deployment "
                        f"{deployment['metadata']['name']}"
                    )
    return errors


def check_env_and_images(docs: list, label: str, expected_env: str) -> list[str]:
    """Fix 27: per-environment `env` label + no `:latest` / no placeholder leaks.

    Asserts for a full overlay render:
      - every ecommerce Deployment pod template carries env == expected_env;
      - the ecommerce Namespace carries env == expected_env;
      - no container image references `:latest`;
      - the Grafana ExternalSecret exists in dev/staging/prod and does NOT in
        local (which ships its own placeholder Secret instead).
    """
    errors: list[str] = []
    kinds: dict[str, list[dict]] = {}
    for doc in docs:
        if isinstance(doc, dict) and "kind" in doc:
            kinds.setdefault(doc["kind"], []).append(doc)

    for dep in kinds.get("Deployment", []):
        tpl_labels = dep.get("spec", {}).get("template", {}).get("metadata", {}).get("labels", {})
        if tpl_labels.get("app.kubernetes.io/part-of") != "ecommerce-platform":
            continue
        if tpl_labels.get("env") != expected_env:
            errors.append(
                f"{label}: Deployment {dep['metadata'].get('name')} template label env = "
                f"{tpl_labels.get('env')!r} (expected {expected_env!r})"
            )
        for container in dep["spec"]["template"]["spec"].get("containers", []):
            img = container.get("image", "")
            if img.endswith(":latest"):
                errors.append(
                    f"{label}: Deployment {dep['metadata'].get('name')} uses image {img} (:latest)"
                )

    for ns in kinds.get("Namespace", []):
        if ns["metadata"]["name"] == "ecommerce":
            ns_labels = ns.get("metadata", {}).get("labels", {})
            if ns_labels.get("env") != expected_env:
                errors.append(
                    f"{label}: Namespace ecommerce env = {ns_labels.get('env')!r} "
                    f"(expected {expected_env!r})"
                )

    es_names = {es["metadata"]["name"] for es in kinds.get("ExternalSecret", [])}
    secrets = {
        (s["metadata"].get("namespace", "default"), s["metadata"]["name"])
        for s in kinds.get("Secret", [])
    }
    if label.endswith("/local"):
        if "grafana-admin-credentials" in es_names:
            errors.append(f"{label}: ExternalSecret grafana-admin-credentials must NOT exist in local")
        if ("observability", "grafana-admin-credentials") not in secrets:
            errors.append(f"{label}: Secret observability/grafana-admin-credentials (local fallback) missing")
    elif "grafana-admin-credentials" not in es_names:
        errors.append(f"{label}: ExternalSecret grafana-admin-credentials missing in {label}")

    return errors


def check_static_contracts(root: Path) -> list[str]:
    """Checks that do not need a render (helm values / alert annotations)."""
    errors: list[str] = []
    values = root / "cluster" / "base" / "monitoring" / "kube-prometheus-stack" / "values.yaml"
    if values.is_file():
        text = values.read_text(encoding="utf-8")
        if "<grafana-admin-password>" in text:
            errors.append("values.yaml still contains the <grafana-admin-password> placeholder")
        if "existingSecret: grafana-admin-credentials" not in text:
            errors.append("values.yaml does not wire grafana.admin.existingSecret")
        # Fix 28: the grafana subchart (8.4.x) ranges over envFromConfigMaps and
        # reads `.name` on each entry — plain strings render "name: <no value>".
        if "envFromConfigMaps:" in text and "- name: grafana-oauth-config" not in text:
            errors.append("values.yaml grafana.envFromConfigMaps must use object form (- name: grafana-oauth-config)")
        if re.search(r"(?m)^\s*-\s*grafana-oauth-config\s*$", text):
            errors.append("values.yaml grafana.envFromConfigMaps contains a plain string (chart 8.4.x requires objects)")
        # Fase 5 (fix 29): the grafana pod reads the OAuth client secret from the
        # grafana-oauth-credentials Secret (placeholder local, ExternalSecret in
        # Azure) via envValueFrom — the ConfigMaps no longer carry the key.
        if "GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET" not in text:
            errors.append("values.yaml must wire grafana.envValueFrom.GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET")
        if "name: grafana-oauth-credentials" not in text:
            errors.append("values.yaml envValueFrom must reference the grafana-oauth-credentials Secret")
    alerts_dir = root / "observability" / "alerts"
    if alerts_dir.is_dir():
        for alert in alerts_dir.glob("*.yaml"):
            if "example.com" in alert.read_text(encoding="utf-8"):
                errors.append(f"{alert.name} still references an example.com runbook URL")
    return errors


def _zone(label: str) -> str:
    """DNS zone per overlay (fix 29): localhost for the emulator, otherwise
    <zone>.ecommerce.example.com (dev/staging) or ecommerce.example.com (prod)."""
    if label.endswith("/local"):
        return "localhost"
    for env in ("dev", "staging"):
        if f"/{env}" in label:
            return f"{env}.ecommerce.example.com"
    if "/prod" in label:
        return "ecommerce.example.com"
    return "localhost"


def check_phase4_gitops_sso(docs: list, label: str) -> list[str]:
    """Fix 28: UI ingress/TLS/SSO + floci GitOps total.

    Local overlay asserts (GitOps parity):
      - the 6 platform Applications render (imported from base) plus the new
        ecommerce-local Application; ecommerce-apps/ecommerce-{dev,staging,prod}
        and external-dns Applications stay excluded;
      - 18 HPAs, 4 ClusterPolicies, and ONLY the ClusterIssuers
        {letsencrypt-default, selfsigned};
      - argocd-cm SSO (url, issuer, clientID, $oidc-argocd secret ref,
        local-only oidc.tls.insecure.skip.verify=true);
      - the local realm (auth-realm-local) carries clients ecommerce-web /
        ecommerce-api / argocd / grafana and group ecommerce-admins;
      - grafana-oauth-config points AUTH_URL at the UI host and TOKEN/API at
        the in-cluster auth service, with the shared client-secret placeholder;
      - every Ingress TLS secret is one of argocd-tls/auth-tls/grafana-tls and
        no host carries the <domain> placeholder.

    Azure overlays assert the mirror: hosts keep argocd.<domain> etc., no
    .localhost hosts, no TLS skip-verify key.
    """
    local = label.endswith("/local")
    errors: list[str] = []
    kinds: dict[str, list[dict]] = {}
    for doc in docs:
        if isinstance(doc, dict) and "kind" in doc:
            kinds.setdefault(doc["kind"], []).append(doc)

    # --- Applications (local: GitOps total / app-of-apps parity) ---
    if local:
        app_names = {
            a["metadata"]["name"]
            for a in kinds.get("Application", [])
            if a.get("metadata", {}).get("namespace") == "argocd"
        }
        platform = {"ingress-nginx", "cert-manager", "external-secrets", "kyverno", "keda", "kube-prometheus-stack"}
        missing = platform - app_names
        if missing:
            errors.append(f"{label}: platform Applications missing: {sorted(missing)}")
        if "ecommerce-local" not in app_names:
            errors.append(f"{label}: Application ecommerce-local missing")
        banned = app_names & {"ecommerce-apps", "ecommerce-dev", "ecommerce-staging", "ecommerce-prod", "external-dns"}
        if banned:
            errors.append(f"{label}: excluded Applications present: {sorted(banned)}")

    # --- Parity counts / issuers (local only) ---
    if local:
        hpa_count = len(kinds.get("HorizontalPodAutoscaler", []))
        if hpa_count != 18:
            errors.append(f"{label}: expected 18 HPAs (GitOps parity), got {hpa_count}")
        policy_count = len(kinds.get("ClusterPolicy", []))
        if policy_count != 4:
            errors.append(f"{label}: expected 4 ClusterPolicies, got {policy_count}")
        issuers = {i["metadata"]["name"] for i in kinds.get("ClusterIssuer", [])}
        if issuers != {"letsencrypt-default", "selfsigned"}:
            errors.append(f"{label}: unexpected ClusterIssuer set: {sorted(issuers)}")

    # --- Argo CD SSO ConfigMap ---
    for cm in kinds.get("ConfigMap", []):
        if cm["metadata"]["name"] == "argocd-cm" and cm["metadata"].get("namespace") == "argocd":
            data = cm.get("data", {})
            expected_url = "https://argocd.localhost" if local else f"https://argocd.{_zone(label)}"
            if data.get("url") != expected_url:
                errors.append(f"{label}: argocd-cm url = {data.get('url')!r} (expected {expected_url})")
            if local and data.get("oidc.tls.insecure.skip.verify") != "true":
                errors.append(f"{label}: argocd-cm must set oidc.tls.insecure.skip.verify=true (local only)")
            if not local and "oidc.tls.insecure.skip.verify" in data:
                errors.append(f"{label}: argocd-cm must NOT skip TLS verify in Azure")
            oidc = data.get("oidc.config", "")
            for token in ("issuer: https://auth.", "clientID: argocd", "clientSecret: $oidc-argocd:clientSecret", "requestedScopes"):
                if token not in oidc:
                    errors.append(f"{label}: argocd-cm oidc.config missing {token!r}")

    # --- Local realm: SSO clients + admin group ---
    if local:
        for cm in kinds.get("ConfigMap", []):
            if cm["metadata"]["name"] == "auth-realm-local":
                try:
                    realm = json.loads(cm["data"]["realm-export.json"])
                except Exception as exc:
                    errors.append(f"{label}: auth-realm-local realm JSON unparseable: {exc}")
                    realm = None
                if realm is not None:
                    clients = {c.get("clientId") for c in realm.get("clients", [])}
                    for want in ("ecommerce-web", "ecommerce-api", "argocd", "grafana"):
                        if want not in clients:
                            errors.append(f"{label}: realm client {want} missing")
                    groups = {g.get("name") for g in realm.get("groups", [])}
                    if "ecommerce-admins" not in groups:
                        errors.append(f"{label}: realm group ecommerce-admins missing")

    # --- Grafana OAuth ConfigMap ---
    for cm in kinds.get("ConfigMap", []):
        if cm["metadata"]["name"] == "grafana-oauth-config":
            data = cm.get("data", {})
            auth_url = data.get("GF_AUTH_GENERIC_OAUTH_AUTH_URL", "")
            host = "auth.localhost" if local else f"auth.{_zone(label)}"
            if host not in auth_url:
                errors.append(f"{label}: grafana AUTH_URL must reference {host} (got {auth_url!r})")
            for key in ("GF_AUTH_GENERIC_OAUTH_TOKEN_URL", "GF_AUTH_GENERIC_OAUTH_API_URL"):
                if "http://auth.ecommerce.svc.cluster.local:8080" not in data.get(key, ""):
                    errors.append(f"{label}: grafana {key} must be the in-cluster auth service")
            # Fase 5 (fix 29): the client secret moved OUT of the ConfigMap into
            # the Secret grafana-oauth-credentials (placeholder local,
            # ExternalSecret in Azure) injected via grafana.envValueFrom.
            if "GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET" in data:
                errors.append(f"{label}: grafana-oauth-config must NOT carry GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET (Fase 5)")

    # --- Ingress hosts + TLS ---
    zone = _zone(label)
    for ing in kinds.get("Ingress", []):
        name = ing["metadata"]["name"]
        hosts = [r.get("host") or "" for r in ing.get("spec", {}).get("rules", [])]
        for h in hosts:
            if not h.endswith("." + zone):
                errors.append(f"{label}: Ingress {name} host {h!r} must end with .{zone} (Fase 5)")
        if local:
            # Every ingress terminates TLS with the <ingress-name>-tls secret
            # (argocd-tls, auth-tls, grafana-tls, api-gateway-tls, frontend-tls).
            for tls in ing.get("spec", {}).get("tls", []):
                if tls.get("secretName") != f"{name}-tls":
                    errors.append(f"{label}: Ingress {name} TLS secret {tls.get('secretName')!r} (expected {name}-tls)")

    return errors


def check_phase5_azure_ready(docs: list, label: str) -> list[str]:
    """Fase 5 (fix 29): Azure desplegable end-to-end.

    Azure overlays assert:
      - ExternalSecrets reales: oidc-argocd (ns argocd), grafana-oauth-credentials
        (ns observability, key GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET) y
        event-hubs-credentials (ns ecommerce, keys sasl-username/sasl-password);
      - SASL envs (EVENT_HUBS_SASL_* + SPRING_KAFKA_PROPERTIES_SASL_*) en los
        deployments checkout-svc y notification-svc;
      - realm por overlay (auth-realm-<env>) con los 4 clients y redirectUris
        sin <domain>;
      - ClusterSecretStore azure-keyvault con authType ManagedIdentity y
        vaultUrl kv-<env>-ecommerce-*;
      - env-config con FQDNs Azure + KAFKA_SECURITY_PROTOCOL=SASL_SSL y los
        switches Kafka en true;
      - ScaledObject order-worker con brokers reales (sin <event-hubs-ns>).

    Local overlay asserts the mirror: Secret placeholder
    grafana-oauth-credentials, sin ExternalSecret event-hubs-credentials, sin
    envs SASL, sin ScaledObject.
    """
    local = label.endswith("/local")
    errors: list[str] = []
    kinds: dict[str, list[dict]] = {}
    for doc in docs:
        if isinstance(doc, dict) and "kind" in doc:
            kinds.setdefault(doc["kind"], []).append(doc)
    env = "prod" if _zone(label) == "ecommerce.example.com" else _zone(label).split(".")[0]

    # --- Grafana OAuth client secret: placeholder Secret (local) / ES (Azure) ---
    secrets = {s["metadata"]["name"] for s in kinds.get("Secret", [])}
    es = {e["metadata"]["name"]: e for e in kinds.get("ExternalSecret", [])}
    if local:
        if "grafana-oauth-credentials" not in secrets:
            errors.append(f"{label}: Secret grafana-oauth-credentials missing (local placeholder)")
        else:
            for s in kinds.get("Secret", []):
                if s["metadata"]["name"] == "grafana-oauth-credentials":
                    data = s.get("stringData", {}) or s.get("data", {})
                    if "GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET" not in data:
                        errors.append(f"{label}: Secret grafana-oauth-credentials missing the client-secret key")
    else:
        if "grafana-oauth-credentials" not in es:
            errors.append(f"{label}: ExternalSecret grafana-oauth-credentials missing")
        else:
            keys = {d.get("secretKey") for d in es["grafana-oauth-credentials"].get("spec", {}).get("data", [])}
            if "GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET" not in keys:
                errors.append(f"{label}: grafana-oauth-credentials ES must expose GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET")
        if "oidc-argocd" not in es:
            errors.append(f"{label}: ExternalSecret oidc-argocd missing (Azure overlays)")
        elif es["oidc-argocd"].get("metadata", {}).get("namespace") != "argocd":
            errors.append(f"{label}: oidc-argocd ES must be in the argocd namespace")

    # --- Event Hubs credentials: base ES in Azure, excluded locally ---
    ehe = es.get("event-hubs-credentials")
    if local:
        if ehe is not None:
            errors.append(f"{label}: ExternalSecret event-hubs-credentials must be excluded in local")
    else:
        if ehe is None or ehe.get("metadata", {}).get("namespace") != "ecommerce":
            errors.append(f"{label}: ExternalSecret event-hubs-credentials (ns ecommerce) missing")
        else:
            keys = {d.get("secretKey") for d in ehe.get("spec", {}).get("data", [])}
            if keys != {"sasl-username", "sasl-password"}:
                errors.append(f"{label}: event-hubs-credentials ES must expose sasl-username/sasl-password")

    # --- SASL envs: ONLY in Azure deployments ---
    sasl = {"EVENT_HUBS_SASL_USERNAME", "EVENT_HUBS_SASL_PASSWORD", "SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG", "SPRING_KAFKA_PROPERTIES_SASL_MECHANISM"}
    for dep in kinds.get("Deployment", []):
        name = dep["metadata"]["name"]
        if name not in ("checkout-svc", "notification-svc"):
            continue
        containers = dep.get("spec", {}).get("template", {}).get("spec", {}).get("containers", [])
        envs = {e.get("name") for c in containers for e in c.get("env", [])}
        if local:
            if sasl & envs:
                errors.append(f"{label}: {name} must NOT carry SASL envs in local (PLAINTEXT)")
        else:
            missing = sasl - envs
            if missing:
                errors.append(f"{label}: {name} missing SASL envs: {sorted(missing)}")

    # --- Per-env realm (Azure): clients + redirectUris sin <domain> ---
    if not local:
        realm_cm = None
        for cm in kinds.get("ConfigMap", []):
            if cm["metadata"]["name"] == f"auth-realm-{env}":
                realm_cm = cm
        if realm_cm is None:
            errors.append(f"{label}: ConfigMap auth-realm-{env} missing")
        else:
            try:
                realm = json.loads(realm_cm["data"]["realm-export.json"])
            except Exception as exc:
                errors.append(f"{label}: auth-realm-{env} JSON unparseable: {exc}")
                realm = None
            if realm is not None:
                clients = {c.get("clientId") for c in realm.get("clients", [])}
                for want in ("ecommerce-web", "ecommerce-api", "argocd", "grafana"):
                    if want not in clients:
                        errors.append(f"{label}: realm client {want} missing")
                for c in realm.get("clients", []):
                    for uri in c.get("redirectUris", []):
                        if "<domain>" in uri:
                            errors.append(f"{label}: realm client {c.get('clientId')} still has <domain> redirectUri")

    # --- ClusterSecretStore: ManagedIdentity + per-env vault (Azure) ---
    for store in kinds.get("ClusterSecretStore", []):
        if store["metadata"]["name"] != "azure-keyvault" or local:
            continue
        prov = store.get("spec", {}).get("provider", {}).get("azure", {})
        if prov.get("authType") != "ManagedIdentity":
            errors.append(f"{label}: azure-keyvault must use authType ManagedIdentity")
        vault = prov.get("vaultUrl", "")
        if f"kv-{env}-ecommerce-" not in vault:
            errors.append(f"{label}: azure-keyvault vaultUrl must match kv-{env}-ecommerce-* (got {vault!r})")

    # --- env-config: Azure FQDNs + Kafka flags ---
    for cm in kinds.get("ConfigMap", []):
        if cm["metadata"]["name"] != "ecommerce-env-config":
            continue
        data = cm.get("data", {})
        if not local:
            if not data.get("KAFKA_BOOTSTRAP", "").startswith(f"eh-{env}-ecommerce-"):
                errors.append(f"{label}: KAFKA_BOOTSTRAP must start with eh-{env}-ecommerce- (got {data.get('KAFKA_BOOTSTRAP')!r})")
            if data.get("KAFKA_SECURITY_PROTOCOL") != "SASL_SSL":
                errors.append(f"{label}: KAFKA_SECURITY_PROTOCOL must be SASL_SSL in Azure")
            if data.get("CHECKOUT_EVENTS_KAFKA_ENABLED") != "true":
                errors.append(f"{label}: CHECKOUT_EVENTS_KAFKA_ENABLED must be true in Azure")
            if data.get("NOTIFICATIONS_KAFKA_ENABLED") != "true":
                errors.append(f"{label}: NOTIFICATIONS_KAFKA_ENABLED must be true in Azure")
            if not data.get("DB_HOST", "").startswith(f"psql-{env}-ecommerce-"):
                errors.append(f"{label}: DB_HOST must start with psql-{env}-ecommerce-")
            if not data.get("REDIS_HOST", "").startswith(f"redis-{env}-ecommerce-"):
                errors.append(f"{label}: REDIS_HOST must start with redis-{env}-ecommerce-")

    # --- ScaledObject order-worker: real brokers (Azure) / excluded (local) ---
    sos = [s for s in kinds.get("ScaledObject", []) if s.get("metadata", {}).get("name") == "order-worker"]
    if local:
        if sos:
            errors.append(f"{label}: ScaledObject order-worker must be excluded in local")
    else:
        if not sos:
            errors.append(f"{label}: ScaledObject order-worker missing (brokers patch)")
        else:
            brokers = sos[0].get("spec", {}).get("triggers", [{}])[0].get("metadata", {}).get("brokers", "")
            if "<event-hubs-ns>" in brokers or "<domain>" in brokers:
                errors.append(f"{label}: ScaledObject brokers still carry the base placeholder (got {brokers!r})")
            elif not brokers.startswith(f"eh-{env}-ecommerce-"):
                # <suffix> se ajusta al tfvars real (contrato documentado, igual
                # que los FQDNs de env-config).
                errors.append(f"{label}: ScaledObject brokers must start with eh-{env}-ecommerce- (got {brokers!r})")

    return errors


SERVICES_WITH_DB = {
    "catalog-svc": "catalog",
    "order-svc": "orders",
    "inventory-svc": "inventory",
    "payment-svc": "payments",
}
SPRING_DATASOURCE_ENVS = {
    "SPRING_DATASOURCE_URL",
    "SPRING_DATASOURCE_DRIVER_CLASS_NAME",
    "SPRING_DATASOURCE_USERNAME",
    "SPRING_DATASOURCE_PASSWORD",
    "SPRING_JPA_HIBERNATE_DDL_AUTO",
}


def check_phase6_parity_fina(docs: list, label: str) -> list[str]:
    """Fase 6 (fix 30): paridad fina Postgres + Keycloak productivo.

    Common contract (local y Azure):
      - los 4 services con datasource (catalog/order/inventory/payment) llevan
        SPRING_DATASOURCE_* apuntando a POSTGRES (nunca jdbc:h2 en el render)
        con credenciales desde el Secret db-credentials;
      - db-credentials: ExternalSecret→Key Vault en Azure / Secret placeholder
        plano en local (sin ExternalSecret).

    Local overlay asserts:
      - sidecar postgres:16-alpine POR POD con la DB propia del servicio
        (127.0.0.1:5432/<db>) — reemplaza el postgres del emulador (fix 30);
      - auth (Keycloak) sigue en start-dev + H2 (documentado en fix 30: no hay
        postgres gestionado en floci).

    Azure overlays assert:
      - SIN sidecar: el datasource apunta al Flexible Server del entorno vía
        ${DB_HOST}:${DB_PORT} (env-config);
      - auth en modo produccion: kc.sh start + --proxy-headers=xforwarded +
        --db=postgres + KC_DB_URL/USERNAME/PASSWORD (server compartido);
      - sin start-dev en ningun render Azure.
    """
    local = label.endswith("/local")
    errors: list[str] = []
    kinds: dict[str, list[dict]] = {}
    for doc in docs:
        if isinstance(doc, dict) and "kind" in doc:
            kinds.setdefault(doc["kind"], []).append(doc)
    zone = _zone(label)
    env = "prod" if zone == "ecommerce.example.com" else zone.split(".")[0]

    deps = {d["metadata"]["name"]: d for d in kinds.get("Deployment", [])}

    # --- Contrato comun: datasource postgres en los 4 services ---
    for svc, db in SERVICES_WITH_DB.items():
        dep = deps.get(svc)
        if dep is None:
            errors.append(f"{label}: Deployment {svc} missing")
            continue
        containers = dep.get("spec", {}).get("template", {}).get("spec", {}).get("containers", [])
        app = next((c for c in containers if c.get("name") == svc), None)
        if app is None:
            errors.append(f"{label}: {svc} container missing")
            continue
        envs = {e.get("name"): e for e in app.get("env", [])}
        missing = SPRING_DATASOURCE_ENVS - set(envs)
        if missing:
            errors.append(f"{label}: {svc} missing datasource envs: {sorted(missing)}")
            continue
        url = envs["SPRING_DATASOURCE_URL"].get("value", "")
        driver = envs["SPRING_DATASOURCE_DRIVER_CLASS_NAME"].get("value", "")
        if driver != "org.postgresql.Driver":
            errors.append(f"{label}: {svc} driver must be org.postgresql.Driver (got {driver!r})")
        if not url.startswith("jdbc:postgresql://"):
            errors.append(f"{label}: {svc} datasource URL must be postgres (got {url!r})")
        if not url.endswith(f"/{db}"):
            errors.append(f"{label}: {svc} datasource URL must end with /{db} (got {url!r})")
        if local and url != f"jdbc:postgresql://127.0.0.1:5432/{db}":
            errors.append(f"{label}: {svc} local URL must be jdbc:postgresql://127.0.0.1:5432/{db} (got {url!r})")
        if not local and url != f"jdbc:postgresql://${{DB_HOST}}:${{DB_PORT}}/{db}":
            errors.append(f"{label}: {svc} Azure URL must use ${{DB_HOST}}:${{DB_PORT}} (got {url!r})")
        for key in ("SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"):
            ref = envs[key].get("valueFrom", {}).get("secretKeyRef", {})
            if ref.get("name") != "db-credentials":
                errors.append(f"{label}: {svc} {key} must come from Secret db-credentials")
        # Sidecar: SOLO local
        sidecar = next((c for c in containers if c.get("name") == "postgres"), None)
        if local:
            if sidecar is None:
                errors.append(f"{label}: {svc} must run the postgres sidecar in local")
            else:
                if sidecar.get("image") != "postgres:16-alpine":
                    errors.append(f"{label}: {svc} sidecar image must be postgres:16-alpine")
                penv = {e.get("name"): e.get("value") for e in sidecar.get("env", [])}
                if penv.get("POSTGRES_DB") != db:
                    errors.append(f"{label}: {svc} sidecar POSTGRES_DB must be {db} (got {penv.get('POSTGRES_DB')!r})")
        elif sidecar is not None:
            errors.append(f"{label}: {svc} must NOT run a postgres sidecar in Azure (managed server)")

    # --- db-credentials: ES→KV (Azure) / Secret plano (local) ---
    es = {e["metadata"]["name"]: e for e in kinds.get("ExternalSecret", [])}
    secrets = {s["metadata"]["name"]: s for s in kinds.get("Secret", [])}
    if local:
        if "db-credentials" in es:
            errors.append(f"{label}: ExternalSecret db-credentials must be excluded in local")
        sec = secrets.get("db-credentials")
        if sec is None:
            errors.append(f"{label}: Secret db-credentials missing (local placeholder)")
        else:
            data = sec.get("stringData", {}) or sec.get("data", {})
            if not {"username", "password"} <= set(data):
                errors.append(f"{label}: Secret db-credentials must carry username/password keys")
    else:
        if "db-credentials" not in es:
            errors.append(f"{label}: ExternalSecret db-credentials missing (Azure)")
        else:
            spec = es["db-credentials"].get("spec", {})
            if spec.get("secretStoreRef", {}).get("name") != "azure-keyvault":
                errors.append(f"{label}: db-credentials ES must target ClusterSecretStore azure-keyvault")
            remotes = {d.get("secretKey"): d.get("remoteRef", {}).get("key") for d in spec.get("data", [])}
            if remotes.get("username") != "db-username" or remotes.get("password") != "db-password":
                errors.append(f"{label}: db-credentials ES must map username←db-username / password←db-password (got {remotes!r})")
        if "db-credentials" in secrets:
            errors.append(f"{label}: Secret db-credentials must NOT exist in Azure (lo materializa el ES)")

    # --- Keycloak: start (Azure) / start-dev (local) ---
    auth = deps.get("auth")
    if auth is not None:
        containers = auth.get("spec", {}).get("template", {}).get("spec", {}).get("containers", [])
        ac = next((c for c in containers if c.get("name") == "auth"), None)
        if ac is not None:
            args = ac.get("args", [])
            aenv = {e.get("name"): e for e in ac.get("env", [])}
            if local:
                if "start-dev" not in args:
                    errors.append(f"{label}: auth must keep start-dev in local (H2 embebido documentado)")
                for want in ("--import-realm", "--health-enabled=true", "--metrics-enabled=true"):
                    if want not in args:
                        errors.append(f"{label}: auth local missing arg {want} (fix 25/30: scrape /metrics activo)")
            else:
                if not args or args[0] != "start":
                    errors.append(f"{label}: auth must run kc.sh start in Azure (got args {args!r})")
                for want in ("--proxy-headers=xforwarded", "--db=postgres", "--import-realm", "--health-enabled=true", "--metrics-enabled=true"):
                    if want not in args:
                        errors.append(f"{label}: auth missing arg {want}")
                host = aenv.get("KC_HOSTNAME", {}).get("value", "")
                if host != f"https://auth.{zone}":
                    errors.append(f"{label}: KC_HOSTNAME must be https://auth.{zone} (got {host!r})")
                dburl = aenv.get("KC_DB_URL", {}).get("value", "")
                if not dburl.startswith(f"jdbc:postgresql://psql-{env}-ecommerce-") or not dburl.endswith(":5432/keycloak"):
                    errors.append(f"{label}: KC_DB_URL must point to psql-{env}-ecommerce-*:5432/keycloak (got {dburl!r})")
                for key in ("KC_DB_USERNAME", "KC_DB_PASSWORD"):
                    ref = aenv.get(key, {}).get("valueFrom", {}).get("secretKeyRef", {})
                    if ref.get("name") != "db-credentials":
                        errors.append(f"{label}: auth {key} must come from Secret db-credentials")

    # --- Nunca jdbc:h2 ni start-dev en renders completos ---
    blob = "".join(doc.get("data", {}).get("realm-export.json", "") for doc in kinds.get("ConfigMap", []) if "realm-export.json" in doc.get("data", {}))
    for doc in docs:
        if isinstance(doc, dict):
            blob += yaml.safe_dump(doc, sort_keys=False) if yaml is not None else str(doc)
    if "jdbc:h2" in blob:
        errors.append(f"{label}: jdbc:h2 must not appear anywhere in the render (base CMs limpios, fix 30)")
    if not local and "start-dev" in blob:
        errors.append(f"{label}: start-dev must not appear in Azure renders")

    return errors


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
    if yaml is None:
        print("WARNING: PyYAML not installed — ServiceMonitor wiring + env/image assertions skipped (render checks still run).")

    failures = []

    static_errors = check_static_contracts(root)
    for err in static_errors:
        failures.append(("static", err))
        print(f"  FAIL    static: {err}")
    if not static_errors:
        print("  OK      static contracts (values.yaml / alert runbooks)")
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
            if rel in WIRING_TARGETS and yaml is not None:
                try:
                    docs = list(yaml.safe_load_all(result.stdout))
                except yaml.YAMLError as exc:
                    failures.append((rel, f"wiring check: could not parse rendered YAML: {exc}"))
                    print(f"  FAIL    {rel} (wiring: parse error)")
                    continue
                wiring_errors = check_sm_wiring(docs, rel)
                if wiring_errors:
                    failures.extend((rel, err) for err in wiring_errors)
                    print(f"  FAIL    {rel} (wiring: {len(wiring_errors)} error(s))")
                if rel in ENV_TARGETS:
                    env_errors = check_env_and_images(docs, rel, ENV_TARGETS[rel])
                    if env_errors:
                        failures.extend((rel, err) for err in env_errors)
                        print(f"  FAIL    {rel} (env/images: {len(env_errors)} error(s))")
                if rel in WIRING_TARGETS:
                    f4_errors = check_phase4_gitops_sso(docs, rel)
                    if f4_errors:
                        failures.extend((rel, err) for err in f4_errors)
                        print(f"  FAIL    {rel} (phase4: {len(f4_errors)} error(s))")
                    f5_errors = check_phase5_azure_ready(docs, rel)
                    if f5_errors:
                        failures.extend((rel, err) for err in f5_errors)
                        print(f"  FAIL    {rel} (phase5: {len(f5_errors)} error(s))")
                    f6_errors = check_phase6_parity_fina(docs, rel)
                    if f6_errors:
                        failures.extend((rel, err) for err in f6_errors)
                        print(f"  FAIL    {rel} (phase6: {len(f6_errors)} error(s))")
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