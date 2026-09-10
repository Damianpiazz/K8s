# security/ — Seguridad-como-código a nivel de app (Fase 8)

La **capa de seguridad de aplicación** para la plataforma e-commerce. Se
construye sobre el bootstrap de plataforma de la Fase 3 (`cluster/base/`) —
namespaces, políticas de Kyverno, external-secrets, ServiceAccounts hardcodeados
y NetworkPolicies por servicio ya existen ahí y son **inputs read-only** de
esta capa.

| Preocupación | Plataforma Fase 3 (existe) | Capa de app Fase 8 (este directorio) |
|---|---|---|
| Política de admisión (labels/imágenes/recursos) | ClusterPolicies de Kyverno (`cluster/base/kyverno/policies/`) | `secret-policies/disallow-plain-secrets.yaml` (Audit) |
| Seguridad de Pods | — (nada) | `pod-security/pod-security-labels.yaml` (PSA restricted, matriz de modos) |
| Red | NetworkPolicies por servicio (`services/*/k8s/base/networkpolicy.yaml`) | `network-policies/default-deny-all.yaml` red de seguridad |
| RBAC | ServiceAccounts por servicio (`automountServiceAccountToken: false`) | `rbac/`: Roles/Bindings ecommerce-reader + ecommerce-admin, `sa-tokens.md` |
| Secretos | ClusterSecretStore de external-secrets + ejemplo (`cluster/base/external-secrets/`) | `secret-policies/`: guard contra secretos escritos a mano |

## Enforced vs advisory

| Capa | ¿Enforced? | Notas |
|---|---|---|
| PSA `restricted` en `ecommerce` + `data` | **SÍ** (modo enforce) | **Blocker conocido**: los Deployments de servicios no tienen `seccompProfile` — ver abajo. |
| PSA `restricted` en `observability` / `security` / `platform` | **NO** (audit + warn) | kube-prometheus-stack + workloads de tooling todavía no cumplen restricted. Promover después de verificar cada chart. |
| default-deny-all en `ecommerce` | **SÍ** (NetworkPolicy) | Semántica de unión: las políticas por servicio siguen funcionando. |
| ecommerce-reader / ecommerce-admin | **SÍ** (RBAC) | Grupos `platform-developers` / `platform-team` — ajustalos a tus grupos de AAD. |
| disallow-plain-secrets | **Audit** (advisory) | Pasarlo a Enforce DESPUÉS del tuning (ver `secret-policies/readme.md`) — Enforce-as-shipped bloquearía los Secrets de external-secrets (clave `db-password`). |

## Interacción PSA restricted ↔ Kyverno

Cadena de admisión para un Pod en `ecommerce` (aplicada en orden):

1. **PSA (built-in, sin webhooks)**: rechaza Pods que violan el perfil
   `restricted` del namespace. Este es el GATE DURO.
2. **Kyverno (webhook de admisión dinámico)**: enforcea `require-labels`
   (app+env), `disallow-latest-tag`, `require-resources` (Audit) en los mismos
   Pods.

Consecuencia de diseño: el `require-labels` de Kyverno ya EXCLUYE los
namespaces de plataforma (`observability`, `platform`, ...) porque los
workloads de charts usan `app.kubernetes.io/name` en vez del label plain
`app`. A PSA no le importan los labels — pero como PSA está en restricted
**solo en ecommerce/data**, los dos mecanismos se alinean aproximadamente:
los tenants reciben ambos gates, los namespaces de tooling reciben solo los
gates de label/imagen de Kyverno. Esto es intencional.

## Blockers conocidos antes de que pase `enforce=restricted` (VERIFICADO)

Cada Deployment de servicio (`services/{catalog,order}-svc/k8s/base/deployment.yaml`,
verificado en dos) setea:

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 1000
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: false
  capabilities: { drop: [ALL] }
```

Eso es compatible con restricted **excepto por un campo faltante**:
`seccompProfile`. Desde Kubernetes 1.27 el perfil `restricted` requiere
`seccompProfile: {type: RuntimeDefault}` (o la anotación alpha legacy), así que
**los deployments actuales son RECHAZADOS por enforce=restricted**. Fix
requerido (2 líneas por servicio, en `services/*/k8s/base/deployment.yaml` —
fuera de scope para esta capa read-only):

```yaml
securityContext:
  seccompProfile: { type: RuntimeDefault }   # ← agregar; mantener el resto igual
```

Aplicá `security/pod-security` **después** de agregar esto a los deployments
de servicios. Si no podés tocar los deployments, mantené `ecommerce` en
audit+warn hasta que puedas.

Notas secundarias:
- `readOnlyRootFilesystem: false` está PERMITIDO por restricted (solo `true`
  no es requerido); los servicios escriben `/tmp` intencionalmente para el
  Tomcat embebido.
- El namespace `data`: los charts estilo bitnami (postgres/redis/kafka, Fase 4)
  default a non-root — verificá los settings de seccomp del chart antes de
  confiar en `enforce` ahí también.

## Wiring en GitOps (para el orquestador — NO edites cluster/ vos mismo)

Agregá a **cada** `cluster/overlays/{dev,staging,prod}/kustomization.yaml`
dentro de la lista `resources:` existente:

```yaml
  # ── Capa de app de seguridad (Fase 8) ──
  - ../../../security
```

Nota de orden: `security/` contiene solo CRs (labels de Namespace,
NetworkPolicy, RBAC, ClusterPolicy) — no tiene dependencia dura de que los
manifiestos de servicios estén presentes primero, pero para el mejor efecto de
demo desplegarlo DESPUÉS de que los servicios estén arriba (el default-deny
entonces tiene huecos que cerrar, y los warnings de PSA son visibles).

## Verificado contra

- `cluster/base/namespaces/namespaces.yaml` (los 5 namespaces de plataforma + ecommerce)
- `services/*/k8s/base/{deployment,serviceaccount,networkpolicy}.yaml` (datos de PSA, automount de SA, egress DNS)
- `cluster/base/kyverno/policies/*` (exclusiones de namespaces, precedente de Audit)
- `cluster/base/external-secrets/*` (nombres de claves de ESO → racional del modo Audit)
- `docs/plan-ecommerce-k8s.md` (§4.6 visión de seguridad, §8 requisito de PSA restricted)