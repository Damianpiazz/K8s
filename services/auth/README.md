# auth — Keycloak identity provider (realm: ecommerce)

OIDC/OAuth2 identity for the whole platform. Ships a custom image that's the
stock Keycloak 24 distribution plus the bundled `ecommerce` realm — Keycloak is
never built from source.

## Realm contents (src/realm-export.json)

| Item | Value |
|---|---|
| Realm | `ecommerce` |
| Client `ecommerce-web` | public (SPA), PKCE S256, redirects localhost + `api.<domain>` |
| Client `ecommerce-api` | confidential (client-secret), service account for M2M, JWT validation via JWKS |
| Realm roles | `admin`, `user` |
| Demo user | `demo` / `demo123` (role `user`) |

## Run locally

```bash
cd services/auth
docker build -t auth:local .
docker run --rm -p 8080:8080 -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin auth:local
```

- Admin console: `http://localhost:8080/admin` (`admin` / `admin`)
- Realm well-known config: `http://localhost:8080/realms/ecommerce/.well-known/openid-configuration`

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes `/health/live` + `/health/ready`, secret env |
| `k8s/base/service.yaml` | ClusterIP `auth:8080` |
| `k8s/base/ingress.yaml` | external entrypoint `auth.<domain>` (nginx + letsencrypt-default) — Keycloak token/realm endpoints must be reachable from outside |
| `k8s/base/admin-credentials.yaml` | **placeholder** admin credentials — replace! (gitignored via `**/admin-credentials.yaml`; the real credential source is the ExternalSecret from Azure Key Vault, `cluster/base/external-secrets` ClusterSecretStore `azure-keyvault` + this dir's `external-secret.yaml`) |
| `k8s/base/external-secret.yaml` | production path: Azure Key Vault → Secret |
| `k8s/base/hpa.yaml` | CPU 70%, 1→3 replicas |
| `k8s/base/networkpolicy.yaml` | ingress from ingress-nginx + api-gateway |
| `k8s/overlays/prod/` | 2 replicas, bigger resources |

## Creating the realm via CLI (alternative to the JSON import)

```bash
# inside the running container
/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 \
  --realm master --user admin --password <admin-password>
/opt/keycloak/bin/kcadm.sh create realms -s realm=ecommerce -s enabled=true
/opt/keycloak/bin/kcadm.sh create clients -r ecommerce \
  -s clientId=ecommerce-web -s publicClient=true -s 'redirectUris=["http://localhost:*"]'
```

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (kustomization + deployment files).
2. `REPLACE_WITH_A_SECURE_CLIENT_SECRET` in `src/realm-export.json`.
3. the `keycloak-admin-credentials` Secret (or enable `external-secret.yaml` +
   put `keycloak-admin-username` / `keycloak-admin-password` in Azure Key Vault).
4. `api.<domain>` redirects/Web origins in `src/realm-export.json` if your SPA
   calls Keycloak through the gateway domain.

> NOTE: prod HA requires `kc.sh start` (not `start-dev`) with a real DB and the
> Kubernetes cache stack — see the Dockerfile comments.