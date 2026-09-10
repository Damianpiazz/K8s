# Runbook: Despliegue end-to-end (camino feliz)

Desde una laptop limpia hasta un ambiente funcionando. Cada paso numerado
nombra el script/config exacto que lo hace, así que esto funciona también como
guion de demo para el examen. Elegí el destino: **local (minikube/kind)**,
**dev/staging**, o **prod** (con costo, necesita Azure).

## Prerrequisitos (cualquier camino)

1. **Tooling** — `scripts/README.md` tiene la matriz completa con líneas de
   `winget`: `git`, `kubectl`, `python` (3.12+ con `pip install pyyaml`),
   `kustomize` o `kubectl` (fallback de kustomize), `docker`, `mvn` (solo para
   `run-unit-tests.ps1`), `az` + `terraform` (solo para el camino cloud),
   `minikube`/`kind` + CLI de `argocd` (`winget install Argoproj.ArgoCD`).
2. **Repo** clonado; placeholders documentados en `scripts/README.md`:
   `acr.azurecr.io`, `<acr>`, `rg-…`, `aks-…`, `api.<domain>`,
   `<kv-name>`, valores de suscripción/tfvars.
3. **Sanity**: correr los cuatro chequeos de `tests/README.md` — deben pasar
   todos antes de tocar un clúster:
   ```bash
   python tests/manifests/test-yaml-parse.py
   python tests/manifests/test-kustomize-build.py     # necesita kustomize/kubectl
   python tests/manifests/test-service-contract.py
   python tests/manifests/test-overlay-wiring.py
   ```

## Camino A — Clúster local (minikube o kind)

```powershell
# 1. Clúster (elegí UNO):
.\scripts\bootstrap\minikube-start.ps1        # o
.\scripts\bootstrap\kind-start.ps1

# 2. Renderizá y validá el overlay que quieras (¿kustomize sin binario? fallback con kubectl kustomize):
.\scripts\bootstrap\kustomize-render.ps1 -Environment dev -OutDir "$env:TEMP\ecommerce-render"

# 3. Aplicá la capa de clúster/plataforma (namespaces, issuers, observability, security):
kubectl apply -k cluster/base

# 4. Opción A — camino GitOps con Argo CD:
.\scripts\deploy\bootstrap-argocd.ps1          # instala Argo CD + imprime el password de admin
.\scripts\deploy\sync-argocd-app.ps1           # sincroniza ecommerce-apps (o -AppName <app>)

# 4'. Opción B — apply directo (igual lo rechaza Kyverno para imágenes :latest — ver abajo):
.\scripts\deploy\apply-overlay.ps1 -Environment dev -DryRun

# 5. Verificá:
kubectl get pods -n ecommerce
kubectl get svc -n ecommerce api-gateway
kubectl port-forward -n ecommerce svc/api-gateway 8080:8080 &  # o el host del ingress
```

> En clústeres locales no hay ACR **ni Azure DNS** — las imágenes quedan en
> los valores placeholder documentados, así que los Pods reales no van a
> arrancar (ImagePullBackOff, ver runbook de troubleshooting). Para una demo
> local con *contenedores arriba* tenés que usar imágenes que vos construiste
> (`docker build` + `kind load docker-image` / minikube `--image`), o correr
> el camino cloud de abajo. El `disallow-latest-tag` de Kyverno también
> rechaza `:latest` — fijá un tag (un tag de build local funciona; eso es
> exactamente lo que hace `cd.yml` en cloud).

## Camino B — Azure dev/staging (cloud, bajo costo)

```powershell
# 0. Auth de Azure + contexto (acepta salidas de terraform o nombres fallback):
.\scripts\azure\az-login.ps1 -Environment dev

# 1. Infra: clúster + ACR + datos gestionados (desde infra/terraform/envs/dev):
terraform -chdir=infra/terraform/envs/dev init
terraform -chdir=infra/terraform/envs/dev apply -auto-approve

# 2. Apuntá los placeholders del ConfigMap/ExternalSecret a valores reales
#    (DB_HOST, REDIS_HOST, KAFKA_BOOTSTRAP, kv-<env> …) — lo hacen los tfvars +
#    el parche de secret-store (ADR-0006/0007). Commiteá el overlay resultante.

# 3. Pusheá imágenes y dejá que el CD reescriba newTag (esto es lo que hace el CD de GitHub):
.\scripts\azure\deploy-cd-manual.ps1 -Environment dev -Service catalog-svc -Acr <acr>
#    (sin -Service = los 17; imprime los comandos de git commit pendientes —
#    commiteá el rewrite de newTag para que Argo CD converja)

# 4. Bootstrap de Argo CD y sync:
.\scripts\deploy\bootstrap-argocd.ps1
.\scripts\deploy\sync-argocd-app.ps1 -AppName ecommerce-dev -Server <argo-cd-server>

# 5. Chequeá TLS + dashboards (ADR-0008):
kubectl get certificate -n ecommerce          # listo después de que external-dns propagó
kubectl -n observability port-forward svc/kube-prometheus-stack-grafana 3000:80
```

## Camino C — Prod (subir SKUs, DNS real)

1. Igual que el Camino B con `-Environment prod`; prod usa el parche
   `k8s/overlays/prod` de cada servicio (más réplicas/recursos) — validado por
   `test-overlay-wiring.py`.
2. El issuer de prod es Let's Encrypt **prod** — ver ADR-0001/0003 y el
   `issuer-default.yaml` por overlay de ambiente.
3. Importante: `kubectl apply -k cluster/base` **no** despliega servicios —
   Argo CD es el dueño de eso (ADR-0002). Nunca aplicar la capa de servicios
   con kubectl de forma manual en prod.

## Checklist de verificación post-deploy (cualquier camino)

- [ ] `kubectl get pods -n ecommerce` — todos Running/Ready (CrashLoop →
      runbook de troubleshooting)
- [ ] HPA: `kubectl get hpa -n ecommerce` — no `<unknown>`
- [ ] ServiceMonitors cableados: `kubectl get servicemonitor -n observability`
      — 17 entradas, targets de Prometheus UP
- [ ] Dashboards: ConfigMaps sidecar de Grafana cargados, `service-sli` muestra datos
- [ ] Certificate + host del ingress alcanzable sobre HTTPS
- [ ] El dashboard de Eureka lista todas las réplicas (`discovery-service:8761`)
- [ ] `argocd app get ecommerce-<env>` — Synced, Healthy

Ver `runbook-index.md` para los otros runbooks.