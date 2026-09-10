# ADR-0007: External Secrets Operator con Azure Key Vault

- **Estado**: aceptado
- **Fecha**: 2026-09-07
- **Decisores**: equipo de plataforma

## Contexto

Los secretos existen en toda la plataforma: credenciales de ACR para image
pulls, password de admin de Keycloak (ADR-0005), passwords de DB / claves
SASL de Event Hubs (ADR-0006), credenciales DNS de cert-manager. La regla del
repo es absoluta: **no hay secretos en Git** (`.gitignore` bloquea
`**/secret.yaml`, Gitleaks escanea cada PR en `ci.yml`). El clúster necesita
un mecanismo para materializar secretos desde un secret store real en
runtime.

## Decisión

- **External Secrets Operator** respalda el clúster:
  `cluster/base/external-secrets` trae el `ClusterSecretStore`
  (`azure-keyvault`, auth `ManagedIdentity` en el controlador de ESO) más un
  `ExternalSecret` de ejemplo.
- Cada ambiente apunta el store a su propio vault via un parche de overlay
  (`cluster/overlays/*/patches/secret-store-patch.yaml` →
  `kv-{dev,staging,prod}-ecommerce`), manteniendo las URLs de vault por
  ambiente.
- El camino de autenticación de producción está documentado como
  **Workload Identity** (user-assigned identity + federation) como la opción
  recomendada de AKS; `ManagedIdentity` es el baseline committed.
- Convención del equipo: crear un ExternalSecret cada vez que un Deployment
  necesite credenciales; los Kubernetes Secrets plain quedan solo para dev
  local (y están bloqueados por la política de Kyverno en Audit
  `security/secret-policies/disallow-plain-secrets.yaml`, que pasa a Enforce
  solo después de tuning — de otra forma bloquearía los propios Secrets de
  ESO).

## Consecuencias

- Rotación de secretos = rotar el objeto del vault + re-sync del controlador,
  sin redeploys ni arqueología de historial de git.
- Los placeholders permanecen en Git (`<kv-name>`, `<identity-client-id>`,
  `AZURE_TENANT_ID` en el ClusterSecretStore) — un estudiante debe
  completarlos después de que terraform aprovisione el vault;
  `cluster/base/README.md` los audita.
- ESO agrega los CRDs + controlador a la capa de plataforma; su Application
  debe estar Healthy antes de que los ExternalSecrets materialicen (orden de
  bootstrap).
- Sin secretos = demos más seguros (nada que filtrar en pantalla) al costo
  de un componente de plataforma más para explicar.

## Alternativas consideradas

- **Sealed Secrets (bitnami)**: encripta secretos dentro del repo — linda
  demo, pero el ciclo de vida de la clave de encriptación es manual y la
  integración nativa con Azure es más débil; el pipeline de la Fase 1 y el
  ADR-0006 ya asumen Azure Key Vault.
- **Vault (HashiCorp)**: engine de secretos completo, pero otro servidor
  stateful para operar; excesivo para 17 microservicios.
- **Secretos plain en Git**: descartado de plano — fallaría Gitleaks y los
  requisitos de seguridad del curso.