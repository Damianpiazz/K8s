# ADR-0007: External Secrets Operator with Azure Key Vault

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: platform team

## Context

Secrets exist everywhere in this platform: ACR credentials for image pulls,
Keycloak admin password (ADR-0005), DB passwords / Event Hubs SASL keys
(ADR-0006), cert-manager DNS credentials. The repo rule is absolute:
**no secrets in Git** (`.gitignore` blocks `**/secret.yaml`, Gitleaks scans
every PR in `ci.yml`). The cluster needs a mechanism to materialize secrets
from a real secret store at runtime.

## Decision

- **External Secrets Operator** backs the cluster: `cluster/base/external-secrets`
  ships the `ClusterSecretStore` (`azure-keyvault`, auth
  `ManagedIdentity` on the ESO controller) plus an `ExternalSecret` example.
- Every environment points the store at its own vault via an overlay patch
  (`cluster/overlays/*/patches/secret-store-patch.yaml` →
  `kv-{dev,staging,prod}-ecommerce`), keeping vault URLs per environment.
- The production authentication path is documented as **Workload Identity**
  (user-assigned identity + federation) as the recommended AKS option;
  `ManagedIdentity` is the committed baseline.
- Team convention: create an ExternalSecret whenever a Deployment needs
  credentials; plain Kubernetes Secrets stay only for local dev (and are
  blocked by the Audit-mode Kyverno policy
  `security/secret-policies/disallow-plain-secrets.yaml`, flipped to Enforce
  only after tuning — it would block ESO's own Secrets otherwise).

## Consequences

- Secret rotation = rotating the vault object + controller re-sync, no
  redeploys or git history archaeology.
- Placeholders remain in Git (`<kv-name>`, `<identity-client-id>`,
  `AZURE_TENANT_ID` in the ClusterSecretStore) — a student must fill them
  after terraform provisions the vault; `cluster/base/README.md` audits them.
- ESO adds the CRDs + controller to the platform layer; its Application must
  be healthy before ExternalSecrets materialize (bootstrap ordering).
- No secrets = safer demos (nothing to leak on screen) at the cost of one
  more platform component to explain.

## Alternatives considered

- **Sealed Secrets (bitnami)**: encrypts secrets into the repo — nice demo,
  but the encryption key lifecycle is manual and Azure-native integration is
  weaker; the Phase 1 pipeline and ADR-0006 already assume Azure Key Vault.
- **Vault (HashiCorp)**: full secret engine, but another stateful server to
  operate; overkill for 17 microservices.
- **Plain Secrets in Git**: rejected outright — would fail Gitleaks and
  the course's security requirements.