# cluster/base — Bootstrap GitOps de plataforma

Este directorio contiene la **capa de plataforma** del clúster e-commerce
(Fase 3 de [docs/plan-ecommerce-k8s.md](../../docs/plan-ecommerce-k8s.md)).
Levanta la infraestructura compartida que todo servicio necesita (la Fase 4
crea `services/` por encima).

## Arquitectura

Controlador GitOps: **Argo CD** (patrón app-of-apps), elegido porque el CI/CD
de la Fase 1 (`GITOPS == 'argocd'`) ya lo soporta — `cluster/base` queda
consistente con `.github/workflows/cd.yml`.

```
cluster/base/
├─ namespaces/            # ecommerce, platform, observability, security, data, argocd
├─ argocd/
│   ├─ namespace.yaml           # documentado solo para claridad (el namespace vive en namespaces.yaml)
│   ├─ install/                 # bootstrap de Argo CD de UNA VEZ (remote kustomize base)
│   ├─ app-of-apps.yaml         # Application raíz "ecommerce-apps"
│   └─ applications/            # 7 Applications hijas (Helm charts + git paths)
├─ cert-manager/          # ClusterIssuers (selfsigned + Let's Encrypt), ejemplo de cert
├─ ingress-nginx/         # valores Helm + IngressClass por defecto
├─ external-dns/          # manifiesto plain → proveedor de Azure DNS
├─ external-secrets/      # ClusterSecretStore (Azure Key Vault) + ejemplo de ExternalSecret
├─ kyverno/policies/      # 3 ClusterPolicies (labels, recursos, latest-tag)
├─ keda/                  # ejemplo de ScaledObject (Event Hubs Kafka)
├─ monitoring/            # valores kube-prometheus-stack, collector OTel, PrometheusRule
├─ kustomization.yaml     # agrega todo excepto el install de Argo CD
├─ helmfile.yaml          # bootstrap ALTERNATIVO (no-GitOps, solo documentado)
└─ README.md
```

Modelo: una Application raíz (`ecommerce-apps`) sincroniza las hijas en
`argocd/applications/`. Los componentes pesados en Helm (ingress-nginx,
cert-manager, external-secrets, kyverno, keda, kube-prometheus-stack) son
Applications basadas en chart; external-dns es una Application de git-path
porque el manifiesto plain y el chart entrarían en conflicto (dos Deployments
llamados `external-dns` en el mismo namespace). Los recursos respaldados por
CRDs (ClusterPolicy, ClusterIssuer, ClusterSecretStore, PrometheusRule,
ScaledObject) los aplica la kustomization de nivel superior *después* de que
los charts que proveen sus CRDs estén healthy — ver el orden de bootstrap
abajo.

## Bootstrap (clúster AKS nuevo)

Argo CD instala CRDs de forma asíncrona (cert-manager, kyverno,
external-secrets, keda, kube-prometheus-stack), y varios recursos a nivel de
clúster dependen de ellos (`no matches for kind: ClusterPolicy` es el síntoma
de orden faltante). La secuencia de abajo lo hace explícito:

```bash
# 1. Argo CD en sí — namespace, CRDs, controladores (remote kustomize base).
#    Si tu kubectl rechaza el base remoto, usá los manifiestos oficiales de
#    fallback:
#    kubectl apply -f https://raw.githubusercontent.com/argoproj/argo-cd/v2.12.3/manifests/install.yaml
kubectl apply -k cluster/base/argocd/install
kubectl -n argocd wait --for=condition=available deploy/argocd-server --timeout=300s

# 2. Bootstrap de las Applications (raíz + 7 hijas).
kubectl apply -f cluster/base/argocd/app-of-apps.yaml
kubectl apply -f cluster/base/argocd/applications/

# 3. Esperá hasta que las Applications basadas en charts estén Healthy — los
#    recursos del paso 4 necesitan sus CRDs (ClusterIssuer, ClusterPolicy,
#    ClusterSecretStore, PrometheusRule, ScaledObject).
kubectl get applications -n argocd   # repetí hasta que todas estén Healthy

# 4. Todo lo demás (los namespaces ya existen — el apply es idempotente, y las
#    Applications del paso 2 son adoptadas/reconciliadas por la raíz).
kubectl apply -k cluster/base
```

Desde acá, Argo CD es la fuente de verdad: los edits a `cluster/base` se
sincronizan automáticamente (`automated: prune + selfHeal` en cada
Application).

Chequeos de sanidad:

```bash
kustomize build cluster/base          # debe renderizar limpio en cualquier momento
kubectl get applications -n argocd    # todas deben estar Healthy / Synced
kubectl get clusterissuer             # selfsigned, letsencrypt-staging, letsencrypt-prod
kubectl get clusterpolicy             # require-labels, require-resources, disallow-latest-tag
```

### UI de Argo CD

```bash
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
kubectl port-forward -n argocd svc/argocd-server 8080:443
# abrí https://localhost:8080 → usuario: admin
```

## Alternativa: helmfile (no-GitOps)

`helmfile.yaml` instala los mismos componentes (más Argo CD) sin que Argo CD
los gestione — es la ruta de comparación/fallback, no la principal:

```bash
helmfile apply
kubectl apply -k cluster/base    # solo recursos respaldados por CRDs (saltarse
                                 # los CRs de Application está bien — son CRs de Argo CD)
```

NO mezcles helmfile con las Applications de Argo CD para el mismo componente:
external-dns está explicitamente mencionado (chart vs manifiesto git-path).

## Qué DEBES completar (placeholders)

| Dónde | Placeholder | Reemplazar con |
|---|---|---|
| cada Application de Argo CD | `https://github.com/<org>/<repo>.git` | la URL de tu repo (y `targetRevision` si no es `main`) |
| `argocd/install/kustomization.yaml` | `?ref=v2.12.3` | el release tag de Argo CD que elijas |
| `cert-manager/cluster-issuer.yaml` | `<email>` | el email de tu cuenta de Let's Encrypt |
| `cert-manager/certificates.yaml` | `<domain>` | tu dominio real (descomentar primero) |
| `external-dns/external-dns-azure.yaml` | `<domain>`, `<subscription-id>`, `<dns-zone-resource-group>`, `<service-principal-client-id>`, `<service-principal-client-secret>` | zona Azure DNS + service principal; `AZURE_TENANT_ID` |
| `external-secrets/cluster-secret-store.yaml` | `<kv-name>`, `<identity-client-id>`, `AZURE_TENANT_ID` | tu Key Vault (salida de infra/terraform) y la managed identity de ESO |
| `external-secrets/example-external-secret.yaml` | secreto de vault `db-password` | cualquier secreto de vault para exponer como `ecommerce-db-credentials` |
| `keda/scaledobject-example.yaml` | `<event-hubs-ns>` + nombres de env `EVENT_HUBS_SASL_USERNAME/PASSWORD` en el Deployment `order-worker` | tu namespace de Event Hubs y el env del deployment |
| `monitoring/kube-prometheus-stack/values.yaml` | `<grafana-admin-password>` | un password de admin real (o adminPasswordSecret via external-secrets) |
| todos los campos `targetRevision` / `version:` | comentarios `# bump me` | chequeá los repos de charts para las últimas versiones estables |

## Notas y trade-offs conocidos

- **Orden de bootstrap**: el paso 4 depende de que los charts del paso 3
  hayan instalado sus CRDs; el orden del README evita errores de `no matches
  for kind`. Dentro de Argo CD, las apps de charts son independientes — la
  cadena de dependencia real la impone la secuencia de kubectl.
- **Las políticas de Kyverno apuntan a namespaces de apps**: `cert-manager`,
  `ingress-nginx`, `external-dns`, `external-secrets`, `keda`,
  `observability`, `platform`, `argocd`, `kyverno`, `kube-system` están
  excluidos via `namespaceSelector` (los charts setean
  `app.kubernetes.io/name`, no `app`). `require-labels` y
  `disallow-latest-tag` corren en `Enforce`; `require-resources` corre en
  `Audit` hasta que cada workload declare recursos (darlo vuelta cuando esté
  listo).
- **El collector OTel** es plain-manifest (sin Application hija): lo aplica la
  kustomization del bootstrap y solo se gestiona el drift en la ruta raíz.
  Promoverlo a Application es una preocupación de la Fase 4 junto con
  `cluster/overlays/`.
- **Argo CD se instala solo en el bootstrap** — no se gestiona a sí mismo (la
  auto-gestión necesitaría un paso de bootstrap antes de que Argo CD exista).
  El paso 1 es ese paso de una vez sola.
- **`certificates.yaml` está solo comentado** por diseño: los certificados
  wildcard necesitan un solver DNS-01 (Azure DNS), que los issuers actuales no
  proveen. Descomentá el archivo, cambiá el issuer a dns01/azuredns, y agregalo
  al `kustomization.yaml`.