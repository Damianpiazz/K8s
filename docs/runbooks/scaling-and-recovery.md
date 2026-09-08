# Runbook: Scaling and recovery

How the platform grows, how it survives a node dying, and how to roll back
when something ships badly. Reference model: **HPA/KEDA for elasticity,
PDB for safe drains, Argo CD for rollback, managed data for durability**
(ADRs 0002, 0006, 0008).

## 1. Horizontal scaling — what ships with the platform

| Mechanism | Where | Purpose |
|---|---|---|
| **HPA** | `services/<svc>/k8s/base/hpa.yaml` (all 17) | CPU-based replica scaling per service |
| **KEDA** | `cluster/base/keda/scaledobject-example.yaml` | Event-driven scaling — order-worker scales on Event Hubs (Kafka) lag |
| **PDB** | `services/<svc>/k8s/base/pdb.yaml` (all 17) | Guarantees N replicas during voluntary disruption (node drain, upgrades) |
| **Node pool autoscaler** | `infra/terraform/envs/*/terraform.tfvars` (AKS path) | Cluster-level node elasticity |

### Operate HPA

```bash
kubectl -n ecommerce get hpa,deploy
kubectl -n ecommerce describe hpa catalog-svc        # utilization, conditions
kubectl -n ecommerce get pod -l app=catalog-svc -o wide
```

- Scaling needs `requests` on containers (base manifests set them) —
  HPA reports `<unknown>/<unknown>` when requests are missing
  (troubleshooting runbook, §7).
- Tune with the overlay resource patch for prod; demo scale-up:
  `kubectl -n ecommerce run -it --rm load --image=busybox -- sh -c "while true; do wget -qO- http://catalog-svc:8081/actuator/health >/dev/null; done"`.

### KEDA (order-worker example)

```bash
kubectl -n ecommerce get scaledobject,scaledjob
kubectl -n ecommerce describe hpa order-worker     # KEDA backs this HPA
```

- Triggers on Azure Event Hubs lag (`lagThreshold: 10`,
  `minReplicaCount: 1`, `maxReplicaCount: 10`); scale-down has a 300s
  stabilization window by default (expected).
- `cooldownPeriod`/`pollingInterval` live in the ScaledObject — tune after
  load testing.

## 2. Node drain / voluntary disruption (PDB in action)

```bash
kubectl drain <node> --ignore-daemonsets --delete-emptydir-data
kubectl get pdb -n ecommerce     # ALLOWED DISRUPTIONS should stay >= 1
kubectl uncordon <node>
```

- With PDB `minAvailable: 1` (or 50%) the eviction API **blocks** the drain
  when only 1 replica would remain — you'll see a drain hang; that's the
  PDB working, not a bug.
- Workers first, then control-plane (minikube/kind local: `minikube stop` is
  the whole story).

## 3. Crash / node failure (involuntary disruption)

- A dead node = Pods reschedule on survivors (DaemonSet metrics-server,
  kube-proxy re-run automatically).
- Pods that referenced local data come back empty — all app state lives in
  the **managed data plane** (ADR-0006), so this is a non-event for data.
- Watch for: node NotReady > 5m → `kubectl describe node <node>` →
  kubelet/containerd logs via SSH/console; out-of-disk → cleanup images
  `docker system prune` (or AKS spot bumps).
- Eureka re-registers on heartbeats (ADR-0004): after a big drain, give it
  2× the registration interval before judging the dashboard.

## 4. Managed data — backups and recovery

| Service | Backup mechanism | Restore |
|---|---|---|
| Postgres Flexible Server | Azure **automated backups** (7-day retention default in tfvars) | Portal/CLI: `az postgres flexible-server restore` → point PITR |
| Redis Cache | AOF persistence (Basic SKU limitations — see below) | `az redis` export/import (RDB blob) |
| Event Hubs | None persistent (stream); replay via consumer offsets | Re-run consumers from a stored checkpoint |

- Demo-PITR:
  ```bash
  az postgres flexible-server restore \
    -n pg-dev-ecommerce-restored --source-server pg-dev-ecommerce \
    -g rg-ecommerce-dev --restore-time <ISO-8601>
  ```
- **Known Basic-SKU trade-off** (ADR-0006): Azure Cache Basic has no SLA and
  no persistence guarantee — acceptable for dev; prod tfvars must use
  Standard/Premium and document the RPO choice in the README.
- Event Hubs is high-watermark: restore = rewind the consumer group
  checkpoint, not data resurrection — designed that way (orders live in
  Postgres).

## 5. Rollback (GitOps-style, ADR-0002)

Git is the source of truth; the fastest safe rollback is a **revert commit**
(never `kubectl delete` — selfHeal would re-create it):

```bash
git log --oneline -10                        # find the bad commit (e.g. abc1234)
git revert abc1234                            # new commit undoing the change
git push origin <branch>                      # CD runs again; Argo CD selfHeals
argocd app wait ecommerce-<env> --sync        # or watch `argocd app get`
```

- Image-tag rollback specifically: the newTag rewrite **is a commit** in the
  overlay — `git revert` the "bump catalog-svc to SHA" commit and the
  pipeline re-syncs the previous image.
- If Argo CD itself is broken: `kubectl -n argocd rollout restart deploy/argocd-server`,
  or `bootstrap-argocd.ps1` re-runs (idempotent).
- Local no-Argo path: `git checkout <good-commit> -- cluster/overlays/dev &&
  .\scripts\deploy\apply-overlay.ps1 -Environment dev`.

### Post-rollback checklist

- [ ] `argocd app get ecommerce-<env>` → Synced + Healthy
- [ ] Pod images confirm rollback: `kubectl -n ecommerce get deploy -o
      jsonpath='{range .items[*]}{.metadata.name}{" -> "}{.spec.template.spec.containers[0].image}{"\n"}{end}'`
- [ ] Dashboards back to baseline (service-sli, error rate)

See `runbook-index.md` for the full list; `troubleshooting.md` covers the
symptom-level half of these flows.