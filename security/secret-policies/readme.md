# security/secret-policies — secrets as code: policy + guidance

## How secrets REALLY work in this platform (verified)

1. **Azure Key Vault** holds the credentials (the cluster is on AKS).
2. **External Secrets Operator (ESO)** syncs them into Kubernetes Secrets via
   the `azure-keyvault` **ClusterSecretStore**
   (`cluster/base/external-secrets/cluster-secret-store.yaml`, authType
   ManagedIdentity or WorkloadIdentity).
3. Services consume the generated `Secret` via the usual `envFrom.secretRef`
   style (or volume mount). See the working example
   (`cluster/base/external-secrets/example-external-secret.yaml`) which creates
   `ecommerce-db-credentials/db-password` from Key Vault.

**Rule: no credential is ever written by hand in this repo.** That is what
`disallow-plain-secrets.yaml` enforces (in Audit mode by default).

## What this directory contains

| File | Kind | Mode | Purpose |
|---|---|---|---|
| `disallow-plain-secrets.yaml` | Kyverno ClusterPolicy | **Audit** (default) | Flags Opaque Secrets whose key names look like credentials (password/token/api_key/...) created by hand in the `ecommerce` namespace. |
| `readme.md` (this file) | doc | — | Integration + tuning instructions. |

### Why Audit and not Enforce (deliberate deviation from the brief)

The brief offered an Enforce policy but explicitly said "students must tune
it". I judged **Enforce too invasive as-shipped** for three verified reasons:

1. **ESO collision**: ExternalSecrets creates real Secrets with key names like
   `db-password` → the policy's own target keys. In Enforce, with
   `creationPolicy: Owner` in `example-external-secret.yaml`, the controller's
   Secret would be **rejected at admission and the platform breaks**.
2. **Repo precedent**: `require-resources` (cluster/base/kyverno) ships as
   Audit until every workload conforms — same conservative rule.
3. **Training context**: the regex key list is a starting point; a student must
   tune it to their real key inventory before it can safely block.

Available control loop for students: watch policy reports
(`kubectl get policyreport -n ecommerce`) → tune → uncomment the
`app.kubernetes.io/managed-by: external-secrets-operator` exclude → flip
`validationFailureAction: Enforce` → watch admission. Document the flip in your
TP report — that IS the deliverable.

## Interaction with the Phase 3 platform pieces

- **ClusterSecretStore / examples** (`cluster/base/external-secrets/`): source
  of truth for where credentials come from. This directory only *guards* the
  result.
- **Kyverno** (`cluster/base/kyverno/policies/`): the existing three policies
  target Pods; this one targets Secrets — complementary, no overlap.
- **`security/README.md`**: PSA + Kyverno interplay (admission chain order).

## Tuning checklist before Enforce

1. `kubectl get secrets -n ecommerce -o json | jq -r '.items[].data | keys[]'`
   — inventory real key names; extend the regex base list (e.g. `client_secret`,
   `connection_string`) or narrow it.
2. Uncomment the exclude block for ESO-managed Secrets (verify the actual label
   ESO sets on your version: `kubectl get secret ecommerce-db-credentials -n
   ecommerce -o jsonpath='{.metadata.labels}'`).
3. Switch `validationFailureAction: Enforce` in a **dev/staging** namespace
   first (namespaceSelector currently pins `ecommerce` — widen per env).
4. Re-run the CD pipeline; if any controller-generated Secret is blocked, the
   audit loop shows exactly which key matched — tune, don't disable.