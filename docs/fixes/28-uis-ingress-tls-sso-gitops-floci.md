# Fix 28 — UIs production-ready (ingress + TLS + SSO) y GitOps total en floci

## Fecha
2026-09-18

## Error / contexto observado

Cuatro deudas de la Fase 4 del plan de paridad floci ↔ Azure:

1. **Las UIs no eran accesibles.** ArgoCD y Grafana solo exponían Services
   ClusterIP: la única vía era `kubectl port-forward`. Sin ingress, sin TLS y
   sin DNS no hay "UI de producción": el operador no podía abrir ArgoCD ni
   Grafana desde el navegador.
2. **Sin SSO.** ArgoCD autenticaba solo con users locales (`admin` + BCrypt del
   Secret `argocd-secret`); Grafana solo con su password local. Keycloak ya
   corría con su realm, pero el realm-export.json no declaraba los clients
   `argocd` ni `grafana` — los flujos OIDC no existían en ningún entorno.
3. **floci sin GitOps total.** El overlay local seguía borrando las
   Applications de los operadores de plataforma (`ingress-nginx`,
   `cert-manager`, `external-secrets`, `kyverno`, `keda`, `kube-prometheus-stack`),
   las 4 ClusterPolicies de Kyverno y el ClusterIssuer `selfsigned`, y no
   existía ninguna Application que gestionara los servicios de negocio. El fix
   25 había revertido la exclusión solo para KPS; las demás quedaron obsoletas:
   en Azure los operadores los instala el app-of-apps, en local el bootstrap
   directo — dos mecanismos distintos para el mismo resultado, y el overlay
   local seguía usando el viejo.
4. **HPA sin re-escalado durable.** El fix 13 (nodo único) había borrado los 18
   HPA del overlay local como medida runtime. La paridad GitOps exige que el
   overlay local renderice los mismos HPA que Azure; la exclusión por nombre
   era una deuda de plan (estaba anotada como "fix 13 durable" pero en realidad
   solo sobrevivía la exclusión).

## Causa raíz

1. **No existía ninguna pieza de ingress/TLS para las UIs.** El plan (Fase 4)
   las contemplaba desde el inicio; el trabajo quedó pendiente porque el
   bootstrap de ArgoCD (fix 22) se resolvió primero y los ingresses no eran
   bloqueantes. El issuer por ambiente sí existía: local →
   `letsencrypt-default` (selfSigned desde `issuer-default.yaml`), Azure → LE
   staging/prod.
2. **El SSO se apoyaba en tres placeholders sin sincronizar, igual que los
   demás secrets de la casa:** el client `argocd` del realm, el Secret
   `oidc-argocd` (materializado en Azure por un ExternalSecret, Fase 5) y el
   `GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET` de Grafana. El valor común es
   `REPLACE_WITH_A_SECURE_CLIENT_SECRET`; sin ese contrato el flujo OIDC se
   rompe en runtime con 401 del token endpoint.
3. **La exclusión por nombre de Applications era pre-GitOps.** Se diseñó para
   el bootstrap directo (fix 22: "el local aplica componentes directo"); al
   decidir la paridad total (fix 25 para KPS) las otras 5 Applications y los
   recursos asociados quedaron en el estado intermedio: ni bootstrap puro ni
   GitOps total.
4. **`<domain>` nunca se sustituye en local.** El realm del base usa
   `redirectUris` con `https://api.<domain>/callback`; Keycloak rechaza `<`/`>`
   (URI inválida) y la importación aborta. En Azure el CD lo reemplaza en
   build; en local no hay CD, por eso el overlay local monta un
   realm-export.json propio con hosts `*.localhost` (RFC 1123 válidos).

## Fix aplicado

### 1) Ingress + TLS para ArgoCD y Grafana

- **`cluster/base/argocd/ingress.yaml`** (nuevo): Ingress `argocd` (ns
  `argocd`) con host `argocd.<domain>`, annotation
  `cert-manager.io/cluster-issuer: letsencrypt-default`, `ssl-redirect: "true"`
  y `backend-protocol: HTTP` (el pod corre `--insecure`, ver fix 22). TLS
  `secretName: argocd-tls`.
- **`observability/ingress-grafana.yaml`**: Ingress `grafana` (ns
  `observability`) con el mismo patrón: host `grafana.<domain>`, issuer
  `letsencrypt-default`, TLS `secretName: grafana-tls`, backend HTTP.
- **Overlay local**: los parches de hosts reescriben los 5 ingresses a
  `*.localhost` (`argocd`, `grafana`, `auth`, `api`, `www`) — el `secretName`
  del TLS no cambia; `issuer-default.yaml` (ClusterIssuer `letsencrypt-default`
  selfSigned) puebla `argocd-tls` / `auth-tls` / `grafana-tls`. Los
  ClusterIssuers ACME (`letsencrypt-staging`/`prod`) y el `selfsigned` del base
  siguen excluidos (nombres).
- **Overlays Azure**: mantienen los hosts placeholder `*.domain` (el CD
  sustituye la zona DNS real en build; external-dns crea los A records —
  Fase 5).

### 2) SSO OIDC contra Keycloak

- **Realm** (`services/auth/src/realm-export.json`): se agregan los clients
  `argocd` (confidential, redirect `https://<host>/auth/callback` — localhost
  en local) y `grafana` (confidential, redirect
  `https://<host>/login/generic_oauth`, webOrigins `https://<host>`), y el
  grupo `ecommerce-admins`. El overlay local monta su variante `*.localhost`
  en `auth-realm-configmap.yaml` (el realm del base con `<domain>` haría
  abortar la import en Azure — ver Fase 5 y las Notas).
- **Seguridad del realm**: el usuario `demo/demo123` del realm base
  (`services/auth/src/realm-export.json`) se eliminó — viajaba en la imagen a
  Azure y concedería admin de ArgoCD/Grafana con una password pública. Vive
  solo en el overlay local (`auth-realm-configmap.yaml`), que es el único
  entorno que lo necesita para el login monousuario.
- **`argocd-cm.yaml` por overlay** (local + dev/staging/prod): `url`,
  `oidc.config` (issuer `https://auth.<host>/realms/ecommerce`, `clientID:
  argocd`, `clientSecret: $oidc-argocd:clientSecret`, `requestedScopes:
  [openid, profile, email, groups]`). Local suma `oidc.tls.insecure.skip.verify:
  "true"` (issuer self-signed); Azure no lleva esa clave.
- **`argocd-rbac-cm.yaml` por overlay**: `policy.csv` mapea el claim `groups`
  → `role:admin` (`ecommerce-admins`); sin `policy.default` en ningún overlay
  (default-deny para usuarios sin SSO, incluyendo local — monousuario entra
  por SSO como `demo`). Azure no define default (solo SSO).
- **`oidc-argocd-secret.yaml` por overlay**: Secret placeholder en `argocd`
  (`clientSecret: REPLACE_WITH_A_SECURE_CLIENT_SECRET`). En Azure lo
  materializa un ExternalSecret (Fase 5); en local queda el placeholder.
- **Grafana**: `grafana-oauth-config.yaml` por overlay con las `GF_AUTH_*`
  (AUTH_URL con host de UI, TOKEN_URL/API_URL in-cluster
  `http://auth.ecommerce.svc.cluster.local:8080`, CLIENT_SECRET placeholder,
  `ROLE_ATTRIBUTE_PATH: contains(groups[*], 'ecommerce-admins')`,
  `GF_SERVER_ROOT_URL`). `values.yaml` de KPS solo declara el NOMBRE del CM:
  `grafana.envFromConfigMaps: [{name: grafana-oauth-config}]` — el subchart
  grafana 8.4.x exige **objetos** `{name: ...}` (ver `_pod.tpl`:
  `range .Values.envFromConfigMaps` + `.name`); una lista de strings renderiza
  `name: <no value>` en el envFrom del pod.
- **`services/auth/k8s/base/networkpolicy.yaml`**: dos allow blocks nuevos de
  **ingress hacia el pod de auth** — desde `argocd-server` (namespace argocd)
  y desde `grafana` (namespace observability), para los flujos server-side
  (token/API exchange). Sin restricción de puertos, consistente con el patrón
  del resto de allow blocks de la política.

### 3) floci GitOps total (app-of-apps + Applications)

- El overlay local **deja de borrar** las 6 Applications de plataforma
  (`ingress-nginx`, `cert-manager`, `external-secrets`, `kyverno`, `keda`,
  `kube-prometheus-stack`), las 4 ClusterPolicies de Kyverno y el ClusterIssuer
  `selfsigned` — se importan del base, igual que Azure.
- **`ecommerce-local.yaml`** (nuevo, vive en `cluster/overlays/local/` a
  propósito): Application que despliega el overlay local vía Argo CD
  (`repoURL` del repo, `targetRevision: main`, `path:
  cluster/overlays/local`, sync automático con prune + selfHeal). Vive fuera
  de `cluster/base/argocd/applications/` para que el app-of-apps de Azure
  (`ecommerce-apps`) no la adopte; a cambio, local sigue excluyendo
  `ecommerce-apps` y `ecommerce-{dev,staging,prod}`.
- **Los 18 HPA del base vuelven al render** (se elimina la exclusión del fix
  13 en el overlay local): el comentario de la sección documenta la paridad —
  con un nodo único el HPA escala entre min/max del base; la exclusión era
  pre-GitOps y queda obsoleta. (El fix 13 sigue siendo válido como
  procedimiento runtime si el nodo se satura.)
- Siguen excluidos por nombre (dependen de Azure/secrets reales):
  `ecommerce-{dev,staging,prod}`, `external-dns` (App + Deployment/RBAC),
  `example-external-secret`, ClusterSecretStore `azure-keyvault`, los
  ExternalSecrets (`keycloak-admin-credentials`,
  `grafana-admin-credentials`, `ecommerce-db-credentials`) y el ScaledObject
  `order-worker`.
- **`argocd-extra/kustomization.yaml`** (nuevo, standalone): suplemento que se
  aplica a mano tras el bootstrap de Argo CD — agrega hostAliases a
  `argocd-server` (`auth.localhost → 10.42.0.1`, el gateway de la pod CIDR de
  flannel donde escucha ingress-nginx 80/443) para que el intercambio
  server-side del OIDC resuelva el mismo host que el navegador. Es un
  kustomization aparte porque los manifests de install se aplican antes y no
  están en la acumulación del overlay main.

## Archivos afectados

- Ingress/TLS: `cluster/base/argocd/ingress.yaml` (nuevo),
  `observability/ingress-grafana.yaml` (nuevo), `observability/kustomization.yaml`
- SSO: `cluster/overlays/{local,dev,staging,prod}/{argocd-cm,argocd-rbac-cm,oidc-argocd-secret,grafana-oauth-config}.yaml`,
  `services/auth/src/realm-export.json` (clients `argocd`/`grafana`, grupo
  `ecommerce-admins`), `cluster/overlays/local/auth-realm-configmap.yaml`
  (realm local `*.localhost`), `services/auth/k8s/base/networkpolicy.yaml`
- Grafana: `cluster/base/monitoring/kube-prometheus-stack/values.yaml`
  (`envFromConfigMaps` → objeto `{name: grafana-oauth-config}`)
- GitOps floci: `cluster/overlays/local/kustomization.yaml` (se importan las 6
  Applications + 4 ClusterPolicies + ClusterIssuer `selfsigned` + 18 HPA;
  comentario Phase 4 GITOPS TOTAL; parches de hosts argocd/grafana/auth),
  `cluster/overlays/local/ecommerce-local.yaml` (nuevo),
  `cluster/overlays/local/argocd-extra/kustomization.yaml` (nuevo),
  `cluster/overlays/local/issuer-default.yaml`
- Docs: `docs/PLAN.md` (Fase 4 → ✅), `docs/fixes/README.md` (fila 28)

## Cómo verificar

Validación estática (sin cluster):

```powershell
# Suite completa (44 targets + wiring + env/images + contratos estáticos):
python tests/manifests/test-kustomize-build.py
#   -> KUSTOMIZE BUILD CHECK PASSED

# Render local con los 5 hosts *.localhost (y NINGÚN <domain>):
kubectl kustomize cluster/overlays/local |
  Select-String "host: (argocd|api|auth|www|grafana)\.localhost"   # 5

# Issuers en local: solo letsencrypt-default (selfSigned) + selfsigned:
kubectl kustomize cluster/overlays/local | Select-String "kind: ClusterIssuer" -Context 0,3

# Paridad: 18 HPAs, 18 ServiceMonitors, 4 ClusterPolicies, 7 Applications
# (6 operadores + ecommerce-local; sin ecommerce-apps/dev/staging/prod):
kubectl kustomize cluster/overlays/local | Select-String "kind: HorizontalPodAutoscaler"   # 18
kubectl kustomize cluster/overlays/local | Select-String "(name: ecommerce-|name: ingress-nginx|name: cert-manager|name: external-secrets|name: kyverno|name: keda|name: kube-prometheus-stack)" | Select-String "^\s*name:"

# SSO de ArgoCD en el render local:
kubectl kustomize cluster/overlays/local |
  Select-String "issuer: https://auth.localhost/realms/ecommerce|clientID: argocd|oidc.tls.insecure.skip.verify: \"true\"|url: https://argocd.localhost"
```

### LIVE VALIDATION CHECKLIST (a ejecutar en el floci con el usuario)

Orden de aplicación (los operadores PRIMERO, para que existan los CRDs):

1. `git push` de `main` (Argo CD sincroniza desde el repo, no desde el working
   tree — sin push el sync de `ecommerce-local` queda en error de repo).
2. Aplicar los operadores de plataforma (Applications → CRDs):
   `kubectl apply -f cluster/base/argocd/applications/{ingress-nginx,cert-manager,external-secrets,kyverno,keda,kube-prometheus-stack}.yaml`
   (o sync del app-of-apps si Argo CD ya está seteado).
3. `kubectl apply -k cluster/overlays/local`
4. `kubectl apply -k cluster/overlays/local/argocd-extra` (hostAliases de
   argocd-server).
5. Verificar la IP del nodo: `kubectl get nodes -o wide` → si la pod CIDR NO
   es 10.42.0.1, ajustar `argocd-extra/kustomization.yaml` antes del paso 4
   (TODO(F4) documentado en el archivo).
6. Health de los ingresses/certs: `kubectl get ingress -A`, `kubectl get
   certificates -A` (los Secrets TLS por ingress: `argocd-tls`, `auth-tls`,
   `grafana-tls` — nuevos — y `api-gateway-tls`, `frontend-tls` —
   preexistentes; patrón `<ingress>-tls`).
7. Keycloak: `kubectl port-forward svc/auth 8081:8080 -n ecommerce` →
   `curl -k https://auth.localhost/realms/ecommerce/.well-known/openid-configuration`
   (o directo si el DNS local resuelve).
8. SSO ArgoCD: abrir `https://argocd.localhost` → botón LOGIN VIA KEYCLOAK →
   `demo` (credenciales del realm local) → debe entrar con role admin.
9. Grafana: abrir `https://grafana.localhost` → LOGIN WITH KEYCLOAK →
   `demo` → debe entrar (grupo ecommerce-admins → Admin).
10. Dashboard RED con variable `$env = local`: confirmar que filtra métricas.

TODOs que dependen de Fase 5 (documentados, NO bloquean): sustitución de
`<domain>` (realm/ingresses/urls) en Azure (el CD no lo hace hoy y la import
aborta con `<`/`>`), siembra de Key Vaults, ExternalSecret `oidc-argocd` y
sync del placeholder común `REPLACE_WITH_A_SECURE_CLIENT_SECRET` (client
`argocd` del realm ↔ Secret `oidc-argocd` ↔
`GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET` de Grafana ↔ client `grafana`).

## Notas

- Los workloads de KPS (prometheus/grafana/alertmanager) NO están en el render
  de kustomize: el chart se instala en runtime vía el Application
  `kube-prometheus-stack` (multi-source, `$values` =
  `cluster/base/monitoring/kube-prometheus-stack/values.yaml` — solo ese
  archivo vive en el repo). Por eso `grafana.envFromConfigMaps` se valida
  contra el source del chart (contrato de objetos), no contra el render.
- El host patch de `auth` aparece dos veces en el overlay local (rewrite
  original de la sección api/auth/www + el nuevo bloque de UIs): la
  reescritura es idéntica e idempotente, no rompe nada — se dejó así para no
  tocar la sección histórica.
- `10.42.0.1` = gateway de la pod CIDR de flannel en k3s; ingress-nginx
  (LoadBalancer → svclb) escucha 80/443 ahí. `host.docker.internal` del data
  plane sigue sin relación con esto (fixes 15-17).
- El realm local (`auth-realm-configmap.yaml`) contiene el usuario `demo`:
  credenciales de demo del emulador, sin secretos reales — no commitear
  credenciales reales en los placeholders.