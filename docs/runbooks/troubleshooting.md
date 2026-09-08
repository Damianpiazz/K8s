# Runbook: Troubleshooting common issues

Operating guide for the e-commerce platform (see `../adr/` for why the pieces
exist). Each entry: **symptom → likely cause → diagnosis → fix**. Commands
assume you are on the right context (`az-login.ps1`, `kubectl config
use-context minikube`, etc.).

## Index

| Issue | Quick fix |
|---|---|
| CrashLoopBackOff | `kubectl -n ecommerce logs deploy/<svc> --tail=50` — env/config wiring |
| ImagePullBackOff | ACR placeholder `acr.azurecr.io`, missing imagePullSecrets, private registry |
| TLS cert issues | cert-manager issuer staging vs prod, DNS propagation, rate limits |
| Kyverno rejects `latest` | run CD/deploy-cd-manual to pin a real tag |
| PSA restricted rejects Pods | add/check `seccompProfile`, runAsNonRoot, capabilities |
| Argo CD OutOfSync | repo drift, automated policy off, bad source path |
| HPA not scaling | metrics-server, resource requests set, target utilization |
| Eureka not registering | discovery URL/config, network policy egress, timing |

---

## 1. CrashLoopBackOff

**Symptoms**: Pod restarts; `kubectl get pods -n ecommerce` shows
CrashLoopBackOff; Restarts counter climbing.

**Diagnosis**:
```bash
kubectl -n ecommerce describe pod <pod>
kubectl -n ecommerce logs <pod> --tail=50 --previous   # last crash's output
kubectl -n ecommerce get events --sort-by=.lastTimestamp | tail
```

**Likely causes & fixes**:
- **Env/config wiring**: service Pods pull `ecommerce-env-config` and
  `<svc>-config` ConfigMaps via `envFrom`. A missing key (e.g. `DB_HOST` for
  a service needing a datasource) makes Spring fail fast.
  Fix: `kubectl -n ecommerce get configmap ecommerce-env-config -o yaml`;
  check the overlay `env-config.yaml` values.
- **Eureka/config reachable?** Services register on startup — if
  `discovery-service` or `config-service` is down, clients retry/exit.
  Fix: deploy infrastructure services first (overlay order).
- **Probes too strict**: liveness `initialDelaySeconds: 40` assumes slow
  JVM boot; on a 1-CPU node bump the delay or the failureThreshold.
- **Persistence/init errors**: managed Postgres rejects the service user —
  verify credentials come from external-secrets (ADR-0007), not drift.

## 2. ImagePullBackOff

**Symptoms**: Pod stuck ImagePullBackOff / ErrImagePull.

**Diagnosis**:
```bash
kubectl -n ecommerce describe pod <pod> | Select-String -Pattern 'Image|Events'
# or, PowerShell 5.1:
kubectl -n ecommerce describe pod <pod>
```

**Likely causes & fixes**:
- **ACR placeholder**: image is still `acr.azurecr.io/<svc>:latest` and no
  such registry exists. Fix: `scripts/azure/deploy-cd-manual.ps1 -Acr <ACR>` +
  update overlay `newName` once (`services/README.md` placeholders table).
- **Private registry pull**: AKS needs `imagePullSecrets` or ACR Attached
  Configuration (AKS `--attach-acr`); locally, kind/minikube need
  `docker login` ≤ registry mirror or `imagePullSecrets`.
- **Tag disappears**: CD rewrote `newTag` to a SHA that was never pushed
  (manual build skipped). Verify ACR: `az acr repository show-tags -n <acr>
  --repository <svc>` and re-pin.

## 3. TLS certificate issues (cert-manager)

**Symptom**: ingress shows default/fake cert; browser warning; 
`kubectl get certificate -A` stuck.

**Diagnosis**:
```bash
kubectl -n ecommerce describe certificate api-gateway-tls
kubectl get challenges.acme.cert-manager.io -A
kubectl -n cert-manager get events --sort-by=.lastTimestamp | tail
```

**Likely causes & fixes**:
- **Environment issuer**: dev/staging overlays use Let's Encrypt **staging**
  (fake certs by design) — verify which `letsencrypt-default` is active:
  `kubectl get clusterissuer letsencrypt-default -o yaml`. Real certs only on
  prod (ADR-0001, overlay README).
- **DNS not propagated / external-dns not syncing**: external-dns must create
  the A record for `api.<domain>`; check `kubectl logs -n external-dns
  deploy/external-dns --tail=50` and the DNS zone in Azure.
- **ACME rate limits**: too many orders against the prod endpoint —
  Certificate/Order stuck. Fix: fix DNS first, wait for the backoff window,
  or use staging while testing.
- **Missing email on issuer**: `issuer-default.yaml` placeholders
  (`admin@ecommerce.*`) must be replaced.

## 4. Kyverno rejecting `latest` tags

**Symptom**: `kubectl apply -k cluster/overlays/dev` succeeds but Pods never
appear; Kyverno events say `disallow-latest-tag`.

**Diagnosis**:
```bash
kubectl get events -A | Select-String -Pattern 'kyverno|latest'
kubectl -n kyverno logs -l app.kubernetes.io/name=kyverno --tail=20
```

**Fix (by design, not a bug)**: `cluster/base/kyverno` Enforces
disallow-latest-tag in namespace `ecommerce`. Run the real pipeline
(`cd.yml`) or `scripts/azure/deploy-cd-manual.ps1` to rewrite image tags to
the commit SHA before applying; see `cluster/overlays/README.md` →
"Why direct kubectl apply -k may be rejected".

## 5. PSA restricted rejects Pods

**Symptom**: Pods stuck `Pending`/`CreateContainerConfigError`; events say
`Forbidden: violates PodSecurity "restricted:latest"`.

**Diagnosis**:
```bash
kubectl -n ecommerce get pod <pod> -o yaml | Select-String -Pattern 'security'
kubectl get ns ecommerce -o yaml | Select-String -Pattern 'pod-security'
```

**Fix**: the deployment must satisfy restricted: `runAsNonRoot: true`,
`runAsUser` set (1000), `allowPrivilegeEscalation: false`, dropped caps,
`seccompProfile.type: RuntimeDefault`. `tests/manifests/test-service-contract.py`
checks all 17 services. Chart workloads (helm) using tools namespaces stay
audit-only (ADR-0009) — do not force restricted on `observability` etc. yet.

## 6. Argo CD OutOfSync / stuck sync

**Symptoms**: UI shows OutOfSync forever; sync fails; resources deleted.

**Diagnosis**:
```bash
argocd app get ecommerce-<env>
argocd app diff ecommerce-<env>
kubectl -n argocd get applications.argoproj.io ecommerce-<env> -o yaml
```

**Likely causes & fixes**:
- **Drift**: someone applied manifests with plain kubectl — `selfHeal: true`
  reverts them (that is the point). Fix: edit Git, not the cluster.
- **Bad source path**: Application points at `cluster/overlays/<env>` that
  no longer exists / `targetRevision` ≠ branch. Fix the Application manifest.
- **CRD mismatch**: chart Application installed while a resource references a
  missing CRD (`no matches for kind: ClusterPolicy`). Fix: follow the
  bootstrap order (`cluster/base/README.md`) — wait for the chart apps
  Healthy, then apply the kustomization.
- **Prune blocked**: namespaced resources out of the Application's scope.
  Check `syncOptions` (`CreateNamespace=true`, `PruneLast`).
- Manual status without the CLI:
  ```bash
  kubectl -n argocd get app ecommerce-<env> -o jsonpath='{.status.health.status}{" "}{.status.sync.status}'
  kubectl -n argocd annotate app ecommerce-<env> argocd.argoproj.io/refresh=hard
  ```

## 7. HPA not scaling

**Symptoms**: CPU high, no replica growth; `kubectl get hpa -n ecommerce`.

**Diagnosis**:
```bash
kubectl -n ecommerce describe hpa <svc>
kubectl top pods -n ecommerce          # metrics-server data
kubectl -n kube-system get deploy metrics-server
```

**Likely causes & fixes**:
- **No resource requests**: HPA cannot compute utilization without `requests`
  — the base deployments set them (100m/256Mi) and prod raises them; a
  missing request silently disables scaling.
- **Metrics-server absent** (minikube addon `metrics-server` not enabled) or
  metrics not flowing: `kubectl top nodes` empty → enable metrics-server.
- **Target too low/high**: base HPA target (check `hpa.yaml`) — tune CPU
  target per service; KEDA's order-worker scales on Event Hubs lag
  (`lagThreshold: 10`) with `minReplicaCount: 1`, `maxReplicaCount: 10`.
- **Single replica + stabilization**: scale-down has a
  `stabilizationWindowSeconds` (KEDA 300s) — expected hysteresis, not a bug.

## 8. Eureka not registering

**Symptoms**: service up and healthy but absent from the Eureka dashboard;
gateway 503 "no instances available".

**Diagnosis**:
```bash
kubectl -n ecommerce logs deploy/<svc> --tail=50 | Select-String -Pattern 'eureka|discovery'
kubectl -n ecommerce get svc discovery-service
kubectl -n ecommerce get networkpolicy  # check egress rules
```

**Likely causes & fixes**:
- **Config URL wrong**: Spring Cloud discovery URL must point at
  `http://discovery-service:8761/eureka/` (service DNS inside ecommerce).
- **Network policy egress**: each service policy allows ecommerce peers via
  `app.kubernetes.io/part-of: ecommerce-platform` — if a new service's policy
  copies an older one without that rule, registration traffic is silently
  dropped by default-deny. Fix the policy, not the app.
- **Startup order/timing**: Eureka re-registers on heartbeat; give services
  `initialDelaySeconds` headroom or check application.yml registry
  fetch/registration intervals.
- **ServiceAccount token automation**: `automountServiceAccountToken: false`
  is intended; unrelated to Eureka but a common red herring.

---

See `runbook-index.md` for the full runbook list and
`deploy-end-to-end.md` for the happy-path deployment walkthrough.