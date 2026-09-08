# returns-svc — Return merchandise authorization (RMA) REST API

In-memory RMA store (cart-svc style) with a **visible, documented approval
rule** so the demo is explainable. Config keys align with `config-service`
`returns-svc.yml`.

## Auto-approval rule

A return is created as **`APPROVED`** when *all* of these hold:

1. `reason` is on the allow-list — `returns.auto-approvable-reasons`
   (`defective, wrong-item, not-as-described`);
2. it arrives within the return window — `returns.return-window-days` (30);
3. `quantity` ≤ `returns.max-quantity-auto-approve` (10).

Otherwise the RMA is created as **`PENDING_REVIEW`** and an operator decides.

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| POST | `/api/returns` | create — body `{"orderId": "12", "productId": 3, "reason": "defective", "quantity": 1}` → 201 (rule above) |
| GET | `/api/returns/{id}` | single RMA (404 if unknown) |
| GET | `/api/returns/order/{orderId}` | RMAs of one order (newest first) |
| POST | `/api/returns/{id}/approve` | operator approval (409 if already rejected) |
| POST | `/api/returns/{id}/reject` | operator rejection (409 if already approved) |

## Run locally

```bash
cd services/returns-svc
mvn spring-boot:run          # starts on :8095
```

```bash
curl -X POST localhost:8095/api/returns \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"12","productId":3,"reason":"defective","quantity":1}'
curl -X POST localhost:8095/api/returns/2/approve
curl localhost:8095/api/returns/order/12
```

## Production design

- **Store**: move RMAs to a database (shared, durable, audit-trail friendly).
- **Policy as code**: keep the rule in one place (ideally a rules engine /
  workflow such as Azure Logic Apps) so approvals are consistent across
  channels; add evidence capture (photos) and refund orchestration on approval.

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `returns-svc:8095` |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from api-gateway; egress DNS + peers + 443 |
| `k8s/overlays/prod/` | 2 replicas, bigger resources (⚠ in-memory note) |

## Wiring (one-time, per env)

Add `../../../services/returns-svc/k8s/{base|overlays/prod}` to the
`resources:` list of `cluster/overlays/{env}/kustomization.yaml` (base for
dev/staging, prod overlay for prod) — the `images:` entry is pre-listed so CD
tags it from day one.

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (kustomization files + deployment).
2. In-memory store → database-backed RMAs for production.