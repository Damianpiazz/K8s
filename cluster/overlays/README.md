# cluster/overlays — Overlays de Kustomize por ambiente

Este directorio es la **capa de ambiente** del modelo GitOps (Fase 4).
Extiende `cluster/base` (el bootstrap de plataforma, Fase 3) con
configuración específica por ambiente para `dev`, `staging` y `prod`.

```
cluster/
├─ base/       # UNA definición de plataforma — namespaces, app-of-apps de
│              # Argo CD, ClusterIssuers, IngressClass, ClusterSecretStore,
│              # Kyverno, ejemplo de KEDA, stack de monitoreo
└─ overlays/   # TRES variaciones de ambiente — este directorio
    ├─ dev/       # playground de pruebas
    ├─ staging/   # validación pre-prod
    └─ prod/      # producción
```

## El modelo

Kustomize usa el patrón **base + overlay**:

- `cluster/base` tiene todo lo que es **idéntico entre ambientes** — los
  namespaces, las Applications de Argo CD, los recursos de plataforma con CRDs.
- Cada overlay referencía `../../base` como `resources`, y después **agrega**
  lo específico del ambiente y **parchea** lo que difiere.

### Qué cambia por ambiente

| Preocupación | Dónde | dev | staging | prod |
|---|---|---|---|---|
| Certificate issuer | `issuer-default.yaml` (recurso nuevo por overlay) | Let's Encrypt **staging** | Let's Encrypt **staging** | Let's Encrypt **production** |
| URL de Key Vault | `patches/secret-store-patch.yaml` (parchea `ClusterSecretStore/azure-keyvault` del base) | `kv-dev-ecommerce` | `kv-staging-ecommerce` | `kv-prod-ecommerce` |
| Config de ambiente | `env-config.yaml` (ConfigMap nuevo por overlay) | `ENVIRONMENT=dev`, `LOG_LEVEL=DEBUG` | `ENVIRONMENT=staging`, `LOG_LEVEL=INFO` | `ENVIRONMENT=prod`, `LOG_LEVEL=INFO` |
| Zona DNS (external-dns) | no se parchea acá — se setea en la zona de Azure DNS + `--domain-filter` en base | — | — | — |

### Por qué los issuers del base NO se parchean

`cluster/base` ya define los ClusterIssuers `selfsigned`,
`letsencrypt-staging` y `letsencrypt-prod` (ver
`cluster/base/cert-manager/cluster-issuer.yaml`). En vez de mutarlos, cada
overlay **agrega su propio** ClusterIssuer `letsencrypt-default` apuntando al
servidor ACME correcto para el ambiente.

Los servicios seleccionan el issuer declarativamente con la anotación:

```yaml
metadata:
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-default
```

Esto te da un nombre estable (`letsencrypt-default`) entre ambientes mientras
la CA real detrás cambia por overlay. Explícito y honesto — sin cirugía de
parches sobre el base.

### Por qué env-config es un recurso nuevo, no un parche

`ecommerce-env-config` no existe en base — los parches de kustomize solo
reemplazan campos en recursos **existentes**. Agregarlo como recurso plain
por overlay es la forma idiomática. Los servicios (Fase 5+) lo consumen via
`envFrom`:

```yaml
envFrom:
  - configMapRef:
      name: ecommerce-env-config
```

### Placeholders en env-config.yaml

Los valores de conexión a datos personales siguen los patrones FQDN de Azure
generados por `infra/terraform` (Fase 2) y son **placeholders** — reemplazá
cada valor `# REPLACE with terraform output` antes de un despliegue real:

| Clave | Ejemplo dev | Reemplazar con |
|---|---|---|
| `DB_HOST` | `pg-dev-ecommerce.postgres.database.azure.com` | `terraform output db_fqdn` |
| `REDIS_HOST` | `redis-dev-ecommerce.redis.cache.windows.net` | `terraform output redis_hostname` |
| `KAFKA_BOOTSTRAP` | `ns-dev-ecommerce.servicebus.windows.net:9093` | `terraform output event_hubs_kafka_endpoint` |

## Cómo usa Argo CD los overlays

El **app-of-apps** de Argo CD en `cluster/base` sincroniza la plataforma. Para
despliegue por ambiente, apuntá la Application de Argo CD de cada ambiente al
path del overlay en vez de al base:

```yaml
# Ejemplo — Application de prod (cluster/overlays/prod es el source)
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: ecommerce-platform-prod
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/<org>/<repo>.git
    targetRevision: main
    path: cluster/overlays/prod          # ← path del overlay, no del base
  destination:
    server: https://kubernetes.default.svc
    namespace: argocd
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

Un clúster puede alojar varios ambientes (los namespaces de dev + staging +
prod ya existen en base) apuntando diferentes Applications a diferentes paths
de overlay — o cada ambiente tiene su propio clúster y su propia Application
con el path de overlay correspondiente. Ambos son válidos; el plan asume
**un clúster por ambiente** (ver `docs/plan-ecommerce-k8s.md`), así que cada
instancia de Argo CD despliega exactamente un overlay.

### Preferí `kustomize build` sobre `kubectl apply -k`

Argo CD renderiza `kustomize build <path>` server-side. Verificación local:

```bash
kustomize build cluster/overlays/dev      # debe renderizar limpio
kustomize build cluster/overlays/staging
kustomize build cluster/overlays/prod
kubectl apply -k cluster/overlays/dev     # opcional, apply directo para testear
```

### Por qué un `kubectl apply -k` directo puede ser rechazado (Kyverno)

`cluster/base/kyverno` trae **dos políticas Enforce** que SÍ aplican al
namespace `ecommerce`:

- `disallow-latest-tag` — las imágenes NO deben usar el tag literal `latest`.
- `require-labels` — cada Pod debe llevar los labels `app:` y `env:`.

**Esto es intencional, no un bug.** Los manifiestos base de los servicios usan
`acr.azurecr.io/<svc>:latest` como default amigable para dev, pero el **flujo
de despliegue real nunca aplica `latest`**: el pipeline de CD
(`.github/workflows/cd.yml`) reescribe el `newTag` de cada overlay al SHA del
commit y Argo CD/`kubectl` aplican ese output renderizado. `latest` solo se
referencía antes de que el CD corra.

Consecuencias:

- ✅ `require-labels` — todos los servicios setean `app: <svc>` y los labels
  `env:` en el template del Pod (verificalo con el passthrough de
  `kubectl apply -k`). El VALOR de `env:` en base es siempre `production` —
  ver la nota del gap cosmético abajo.
- ⛔ Si corrés `kubectl apply -k cluster/overlays/dev` **antes** de que el CD
  reescriba los tags, la creación de Pods será **rechazada** por
  `disallow-latest-tag`. Eso es Kyverno haciendo su trabajo.
- 🔧 Para aplicar manualmente, o corré primero el flujo de CD, o fijá un tag
  explícito en el overlay antes de aplicar, p. ej.:

```bash
# después de un push real de imagen
kustomize edit set image acr.azurecr.io/catalog-svc=acr.azurecr.io/catalog-svc:1.0.0
```

## Lo que los overlays deliberadamente NO hacen

- **Parchear archivos `values.yaml` de Helm** (ingress-nginx,
  kube-prometheus-stack) — esos son inputs de charts consumidos por el
  multi-source de Argo CD o helmfile, no recursos de Kubernetes; kustomize no
  puede parchearlos sensatamente. Los valores de chart por ambiente van en los
  manifiestos de las Applications de Argo CD o en un repo de values por env.
- **Setear conteos de réplicas de servicios** — los servicios llegan en una
  fase posterior. El ejemplo de ScaledObject de KEDA del base ya cubre el
  autoscaling (`min=1, max=10`); los baselines de prod (p. ej.
  `replicas: 3`) van en los manifiestos de cada servicio.
- **Parchear `.github/` o terraform** — mantené la config de infra con la fase
  dueña.
- **Parchear el label `env:` del template del Pod** — los Deployments base
  hardcodean `env: production` en el template del Pod y los overlays no
  parchean ese label. La política `require-labels` de Kyverno solo requiere
  que el label exista (el valor no importa), así que los Pods no se rechazan.
  Para el valor correcto por ambiente (p. ej. `env: dev`) en los labels de Pod
  y métricas de Prometheus, un parche de overlay por servicio es una tarea de
  estudiante posterior.

## Auditoría de placeholders

| Archivo | Placeholder | Reemplazar con |
|---|---|---|
| `dev/issuer-default.yaml` | `admin@ecommerce.dev` | tu email de Let's Encrypt |
| `staging/issuer-default.yaml` | `admin@ecommerce.staging` | tu email de Let's Encrypt |
| `prod/issuer-default.yaml` | `admin@ecommerce.com` | tu email de Let's Encrypt |
| `*/patches/secret-store-patch.yaml` | nombre de vault `kv-{env}-ecommerce` | `terraform output keyvault_name` |
| `*/env-config.yaml` | FQDNs `pg-*`, `redis-*`, `ns-*` | salidas de terraform (tabla de arriba) |