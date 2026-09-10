# auth — Proveedor de identidad Keycloak (realm: ecommerce)

Identidad OIDC/OAuth2 para toda la plataforma. Trae una imagen custom que es la
distribución stock de Keycloak 24 más el realm `ecommerce` empaquetado —
Keycloak nunca se buildea desde source.

## Contenido del realm (src/realm-export.json)

| Ítem | Valor |
|---|---|
| Realm | `ecommerce` |
| Client `ecommerce-web` | público (SPA), PKCE S256, redirects localhost + `api.<domain>` |
| Client `ecommerce-api` | confidencial (client-secret), service account para M2M, validación JWT via JWKS |
| Roles del realm | `admin`, `user` |
| Usuario demo | `demo` / `demo123` (rol `user`) |

## Correrlo localmente

```bash
cd services/auth
docker build -t auth:local .
docker run --rm -p 8080:8080 -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin auth:local
```

- Consola de admin: `http://localhost:8080/admin` (`admin` / `admin`)
- Config well-known del realm: `http://localhost:8080/realms/ecommerce/.well-known/openid-configuration`

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes `/health/live` + `/health/ready`, env de secretos |
| `k8s/base/service.yaml` | ClusterIP `auth:8080` |
| `k8s/base/ingress.yaml` | entrypoint externo `auth.<domain>` (nginx + letsencrypt-default) — los endpoints de token/realm de Keycloak deben ser alcanzables desde afuera |
| `k8s/base/admin-credentials.yaml` | credenciales de admin **placeholder** — ¡reemplázalas! (gitignored via `**/admin-credentials.yaml`; la fuente real de credenciales es el ExternalSecret de Azure Key Vault, el ClusterSecretStore `azure-keyvault` de `cluster/base/external-secrets` + el `external-secret.yaml` de este dir) |
| `k8s/base/external-secret.yaml` | camino de producción: Azure Key Vault → Secret |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→3 |
| `k8s/base/networkpolicy.yaml` | ingress desde ingress-nginx + api-gateway |
| `k8s/overlays/prod/` | 2 réplicas, resources más grandes |

## Crear el realm via CLI (alternativa al import JSON)

```bash
# dentro del contenedor corriendo
/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 \
  --realm master --user admin --password <admin-password>
/opt/keycloak/bin/kcadm.sh create realms -s realm=ecommerce -s enabled=true
/opt/keycloak/bin/kcadm.sh create clients -r ecommerce \
  -s clientId=ecommerce-web -s publicClient=true -s 'redirectUris=["http://localhost:*"]'
```

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (archivos de kustomization + deployment).
2. `REPLACE_WITH_A_SECURE_CLIENT_SECRET` en `src/realm-export.json`.
3. el Secret `keycloak-admin-credentials` (o habilitá `external-secret.yaml` +
   poné `keycloak-admin-username` / `keycloak-admin-password` en Azure Key Vault).
4. redirects/Web origins de `api.<domain>` en `src/realm-export.json` si tu SPA
   llama a Keycloak a través del dominio del gateway.

> NOTA: HA de prod requiere `kc.sh start` (no `start-dev`) con una DB real y el
> stack de cache de Kubernetes — ver los comentarios del Dockerfile.