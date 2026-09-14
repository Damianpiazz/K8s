# 10 — Despliegue completo: paso a paso

Guía de extremo a extremo para desplegar la plataforma (infraestructura,
imágenes, clúster y aplicación) con los comandos del repo.

## Qué vamos a desplegar

```
Terraform (Azure) ──► AKS + ACR + Postgres + Redis + Event Hubs + DNS
        │
        ▼
Imágenes docker ──► services/*/Dockerfile → ACR (por CI o manual)
        │
        ▼
Manifiestos ──► cluster/overlays/<env> (kustomize) → Argo CD o kubectl apply
```

Hay **dos decisiones** en el camino:

| Decisión | Opción A | Opción B |
|---|---|---|
| **Imágenes** | CI automático (`cd.yml` al pushear a `main`) | Manual (`deploy-cd-manual.ps1`) |
| **Aplicar manifiestos** | Argo CD (GitOps, `GITOPS=argocd`) | `kubectl apply -k` directo (`GITOPS=kustomize`) |

Esta guía sigue el camino **manual + kubectl** (el más simple para una demo);
al final se indican las variantes con Argo CD.

---

## 0. Requisitos previos

| Herramienta | Verificar |
|---|---|
| Azure CLI | `az version` |
| Terraform | `terraform version` |
| kubectl | `kubectl version --client` |
| Docker | `docker version` |
| git | `git --version` |

Clonar el repo:

```powershell
git clone https://github.com/Damianpiazz/K8s.git
cd K8s
```

---

## 1. Crear la infraestructura con Terraform

Cada ambiente tiene su carpeta: `infra/terraform/envs/{dev,staging,prod}`.

### 1.1 Login de Azure

```powershell
az login
az account set --subscription <SUBSCRIPTION-ID>
```

> `${SUBSCRIPTION-ID}` se obtiene con `az account list -o table`.

### 1.2 Preparar el backend remoto de estado

Antes de `terraform init` hay que crear (una sola vez) el storage account que
guarda el estado. Con el script del repo o a mano:

```powershell
az storage account create -n tfecommerce01 -g rg-ecommerce-backend -l eastus --sku Standard_LRS
az storage container create --name tfstate --account-name tfecommerce01
```

> O ejecutar terraform con el estado **local** para una demo rápida: eliminar
> temporalmente el bloque `backend "azurerm" {}` de `infra/terraform/envs/<env>/backend.tf`.

### 1.3 Init + plan + apply

```powershell
cd infra/terraform/envs/dev

terraform init `
  -backend-config="storage_account_name=tfecommerce01" `
  -backend-config="container_name=tfstate" `
  -backend-config="key=dev/ecommerce.terraform.tfstate" `
  -backend-config="access_key=<STORAGE-ACCOUNT-ACCESS-KEY>"

terraform plan
terraform apply
```

> Al terminar, guardar los outputs: `terraform output` imprime
> `resource_group_name`, `cluster_name`, `acr_name`, etc. Se usan en el paso 2.

### 1.4 Repetir por ambiente

Repetir en `envs/staging` y `envs/prod` (cambiando `dev` por el ambiente en el
`key` del backend) cuando haga falta.

---

## 2. Conectar kubectl al clúster

Con los outputs de Terraform (o los nombres reales del RG/clúster):

```powershell
az aks get-credentials --resource-group <RESOURCE_GROUP> --name <AKS_NAME> --overwrite-existing
kubectl config current-context
```

Atajo con el script del repo (lee los nombres desde `terraform output`):

```powershell
.\scripts\azure\az-login.ps1 -Env dev -UseTerraformOutputs
```

---

## 3. Build y push de imágenes (opcional si usás CI)

### 3a. Automático (recomendado)

Pushear a `main` dispara `cd.yml`, que buildea las 18 imágenes y las sube a
ACR. No hace falta correr nada local:

```powershell
git add .
git commit -m "feat: <descripcion>"
git push origin main
```

### 3b. Manual (demo sin CI)

El script emula exactamente el contrato del CD (imagen
`<acr>.azurecr.io/<svc>` + tag = SHA del commit):

```powershell
# Login al ACR
az acr login --name <ACR_NAME>

# Build + push de todos los servicios del overlay, tag = commit actual
.\scripts\azure\deploy-cd-manual.ps1 -Env dev -Acr <ACR_NAME>

# Solo un servicio, con tag explícito:
.\scripts\azure\deploy-cd-manual.ps1 -Env dev -Acr <ACR_NAME> -Service catalog-svc -Tag v1.0.0
```

> El script **no commitea** el cambio de `newTag` en
> `cluster/overlays/dev/kustomization.yaml`. Revisarlo y commitearlo:
>
> ```powershell
> git diff cluster/overlays/dev/kustomization.yaml
> git add cluster/overlays/dev/kustomization.yaml
> git commit -m "chore(dev): bump image tags"
> ```

---

## 4. Aplicar la plataforma y los servicios

### 4a. Con kubectl + kustomize (camino simple)

Primero el bootstrap de la plataforma base (namespaces, Kyverno,
external-secrets, monitoring, ingress-nginx, etc.):

```powershell
.\scripts\deploy\apply-overlay.ps1 -Env dev
```

> Internamente hace `kubectl apply -k cluster/overlays/dev`, que incluye:
> `cluster/base` + config de ambiente + los 18 servicios + `observability/` +
> `security/`.

Alternativa equivalente a mano:

```powershell
kubectl apply -k cluster/overlays/dev
```

Esperar a que los deployments estén listos:

```powershell
kubectl rollout status deployment -n ecommerce --timeout=300s
kubectl get pods -n ecommerce -o wide
```

### 4b. Con Argo CD (GitOps)

1. **Instalar Argo CD** (un script hace todo: install, wait, password, port-forward):

   ```powershell
   .\scripts\deploy\bootstrap-argocd.ps1
   ```

   La consola queda en `https://localhost:8080` (usuario `admin`, password: la
   que imprime el script; es `argocd-initial-admin-secret`).

2. **Aplicar el App of Apps** (declara las Applications hijas: Argo CD en sí,
   ingress-nginx, cert-manager, external-dns, external-secrets, kyverno,
   kube-prometheus-stack, keda y las aplicaciones de ecommerce por ambiente):

   ```powershell
   kubectl apply -f cluster/base/argocd/app-of-apps.yaml
   kubectl apply -f cluster/base/argocd/applications/
   kubectl get applications -n argocd   # esperar estado Healthy/Synced
   ```

3. **Sincronizar el ambiente** (manual o vía el action de GitHub):

   ```powershell
   .\scripts\deploy\sync-argocd-app.ps1 -AppName ecommerce-dev
   ```

   En CI el job `configure-argocd` de `cd.yml` hace `argocd app sync
   ecommerce-<env> --prune` cuando `GITOPS=argocd`.

---

## 5. Configurar secretos (una sola vez)

Los secretos viven en **Azure Key Vault** y se sincronizan con
**external-secrets** al clúster:

```powershell
# 1. Ver el ClusterSecretStore configurado (apunta al Key Vault)
kubectl get clustersecretstore -o yaml

# 2. Verificar que el parche del Key Vault del ambiente esté aplicado
#    (cluster/overlays/<env>/patches/secret-store-patch.yaml)

# 3. Crear el ejemplo de ExternalSecret que trae el repo
kubectl apply -f cluster/base/external-secrets/example-external-secret.yaml
kubectl get secrets -n ecommerce
```

> Credenciales obligatorias para producción: las de Keycloak
> (`KEYCLOAK_ADMIN_PASSWORD`) y las de la DB (`db-password`). Ver
> `services/auth/README.md` y `cluster/base/external-secrets/`.

---

## 6. Verificar el despliegue

```powershell
# Estado general
kubectl get nodes
kubectl get namespaces
kubectl get pods -A

# Aplicación en ecommerce
kubectl get pods,svc -n ecommerce
kubectl get ingress -n ecommerce

# Probar un servicio (port-forward al gateway)
kubectl port-forward svc/api-gateway -n ecommerce 8080:8080
# en otro terminal:
curl http://localhost:8080/actuator/health

# Logs si algo falla
kubectl logs -n ecommerce deploy/<servicio> --tail=100
kubectl describe pod -n ecommerce <pod-name>
```

---

## 7. Tipos de fallo comunes

| Síntoma | Causa probable | Fix |
|---|---|---|
| `ErrImagePull` / `ImagePullBackOff` | tag `:latest` o imagen no subida a ACR | Correr `deploy-cd-manual.ps1` o el CD; Kyverno rechaza `:latest` |
| Pod rechazado por Kyverno | falta label `app`/`env` o imagen `:latest` | `kubectl describe pod` para ver el mensaje de la policy |
| Pod rechazado por PSA | falta `seccompProfile` en el deployment | Ver `security/README.md` (agregar `seccompProfile`) |
| Service no resuelve DNS interno | NetworkPolicy de egress bloquea | Ver `security/network-policies/README.md` |
| `terraform init` falla | backend remoto sin configurar | Configurar storage account o usar estado local |
| Aplicación sin datos | H2 en memoria (demo) | Reiniciar seedea; para persistenica real usar Azure Postgres |

---

## Resumen rápido (camino manual completo)

```powershell
# 1. Infra
az login
az account set --subscription <SUBSCRIPTION-ID>
cd infra/terraform/envs/dev
terraform init
terraform plan
terraform apply

# 2. kubectl al clúster
.\scripts\azure\az-login.ps1 -Env dev -UseTerraformOutputs

# 3. Imágenes
az acr login --name <ACR_NAME>
.\scripts\azure\deploy-cd-manual.ps1 -Env dev -Acr <ACR_NAME>
git add cluster/overlays/dev/kustomization.yaml
git commit -m "chore(dev): bump image tags"

# 4. Aplicar
.\scripts\deploy\apply-overlay.ps1 -Env dev

# 5. Verificar
kubectl rollout status deployment -n ecommerce --timeout=300s
kubectl get pods -n ecommerce
```

Ver también: [cluster/overlays/README.md](../../cluster/overlays/README.md),
[scripts/README.md](../../scripts/README.md),
[infra/terraform/README.md](../../infra/terraform/README.md).