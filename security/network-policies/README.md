# security/network-policies — namespace-level safety net

## What is here

| File | What it does |
|---|---|
| `default-deny-all.yaml` | Namespace-wide default-deny (ingress + egress) for `ecommerce`: every pod without a per-service NetworkPolicy is denied both directions. |
| `kustomization.yaml` | Aggregates the above for the `security/` tree. |

## What is intentionally NOT here — `allow-kube-system-dns.yaml` (SKIPPED)

The original brief proposed an ecommerce-wide egress rule to `kube-dns`
(53/tcp+udp) "so existing per-service policies still work". **Verified
redundant — every per-service NetworkPolicy already grants DNS egress.** Example
(`services/catalog-svc/k8s/base/networkpolicy.yaml`):

```yaml
egress:
  - to:
      - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } }
        podSelector: { matchLabels: { k8s-app: kube-dns } }
      ports:
        - { port: 53, protocol: UDP }
        - { port: 53, protocol: TCP }
```

and the services are built from the **exact same layout** (services/README.md),
so adding a separate `allow-kube-system-dns` would only duplicate those rules.
The default-deny safety net does not break DNS for services with a per-service
policy (their egress rules still apply via union semantics).

**If a future workload in `ecommerce` needs DNS but has no per-service policy**,
it must define its own egress (or an explicit `allow-kube-system-dns`
namespace-wide rule can be added here then). This matches the "one service =
one NetworkPolicy" philosophy of the repo.

## Interaction with existing per-service policies

- Union semantics: covered pods keep their full per-service allow-lists.
- Uncovered pods: total isolation — no ingress, no egress (including DNS, so
  they cannot even resolve peers until someone writes their policy).

## Caveats (pre-existing, not introduced by this file)

1. **kubelet probes** — readiness/liveness HTTP probes originate from the node;
   a strict ingress policy CAN block them (documented Kubernetes limitation).
   The per-service policies already have this shape, so the safety net does not
   make it worse. If probes break on your CNI, allow the node CIDR explicitly
   in the per-service ingress.
2. **In-cluster service-to-service traffic** — the per-service policies allow
   ecommerce peers via `namespaceSelector: ecommerce` +
   `app.kubernetes.io/part-of: ecommerce-platform` (catalog-svc example). New
   services MUST replicate that egress or they cannot reach their peers.

## Verified against

- `services/catalog-svc/k8s/base/networkpolicy.yaml` (DNS egress + peer egress + observability ingress)
- `services/README.md` (uniform layout across all 17 services)
- `cluster/base/kyverno/policies/require-labels.yaml` (namespace exclusions — chart namespaces not enforced)