# scripts/ — Helpers operacionales (Fase 9)

Utilidades locales para la plataforma e-commerce: bootstrap de clúster,
despliegues por ambiente, login de Azure, operaciones de Argo CD y validación
de manifiestos/tests. El **camino primario** del curso es PowerShell en
Windows (todos los archivos `.ps1`); los gemelos `.sh` son equivalentes para
Linux/macOS para cualquiera que trabaje fuera de Windows.

> Todo lo de acá es un **wrapper de conveniencia alrededor de comandos ya
> documentados en el repo** (`cluster/base/README.md`,
> `cluster/overlays/README.md`, `.github/workflows/*.yml`). El pipeline de
> despliegue real sigue siendo GitHub Actions (`cd.yml`); los scripts le
> permiten a un humano hacer los mismos pasos a mano.

## Matriz de scripts

| Script | Qué hace | Requiere |
|---|---|---|
| `bootstrap/minikube-start.ps1` / `.sh` | Arranca Minikube (profile `ecommerce`, driver docker, cpus/memory), habilita los addons `ingress` + `metallb`, espera al clúster + ingress-nginx Ready | minikube, kubectl, Docker |
| `bootstrap/kind-start.ps1` / `.sh` | Crea un clúster Kind (`ecommerce`) con el nodo ingress-ready + mapeos de host port 80/443, instala el manifiesto oficial de ingress-nginx de kind, espera por él | kind, kubectl, Docker |
| `bootstrap/kustomize-render.ps1` / `.sh` | Renderiza `cluster/overlays/<env>` a un YAML multi-doc único (`kustomize build`, fallback `kubectl kustomize`) para inspección — el mismo render que hace Argo CD | kubectl (kustomize opcional) |
| `deploy/apply-overlay.ps1` / `.sh` | Pre-chequeos (kubectl presente, overlay existe, kustomize renderiza limpio) y después `kubectl apply -k cluster/overlays/<env>`; avisa sobre el gate `disallow-latest-tag` de Kyverno | kubectl, (kustomize opcional) |
| `deploy/bootstrap-argocd.ps1` / `.sh` | Instala Argo CD (`cluster/base/argocd/install`, fallback con manifiesto oficial), espera a `argocd-server`, imprime `argocd-initial-admin-secret`, abre port-forward a la UI, imprime los pasos restantes del bootstrap | kubectl |
| `deploy/sync-argocd-app.ps1` / `.sh` | `argocd app sync <name> --prune` + estado — gemelo manual del job de Argo CD del CD | CLI de argocd |
| `azure/az-login.ps1` / `.sh` | `az login` → `az account set` (suscripción por parámetro) → `az aks get-credentials`; `-UseTerraformOutputs` opcional lee el nombre de RG/clúster de `terraform output` | CLI de az (+ kubectl, terraform opcional) |
| `azure/deploy-cd-manual.ps1` | Fallback manual de `cd.yml`: `az acr login`, `docker build`+`push` por servicio, reescribe `newTag` en el overlay matcheando `newName == <acr>.azurecr.io/<svc>` (mismo contrato que el paso yq de cd.yml) | CLI de az, docker, git |
| `tests/run-unit-tests.ps1` / `.sh` | `mvn -B test` por servicio (fallback `mvnw`), reporte de pass/fail por servicio, exit no-cero en falla | Java 17, Maven |
| `tests/validate-manifests.ps1` / `.sh` | Chequeo de parseo YAML (todos los `.yaml`/`.yml` via python) + `kustomize build` en `cluster/` + `services/` + `observability/` + `security/` | python + pyyaml, (kustomize o kubectl opcional) |

## Requerimientos

| Tool | Usado por | Instalar (Windows) |
|---|---|---|
| `kubectl` | todos los scripts de deploy/apply/render | `winget install Kubernetes.kubectl` o via Docker Desktop |
| `minikube` | minikube-start | `winget install minikube` |
| `kind` | kind-start | `winget install kind` (o `go install sigs.k8s.io/kind@latest`) |
| `kustomize` | render/apply/validate (opcional — fallback `kubectl kustomize`) | `winget install kustomize` |
| `argocd` (CLI) | sync-argocd-app | ver <https://argo-cd.readthedocs.io/en/stable/cli_installation/> |
| `az` (Azure CLI) | az-login, deploy-cd-manual | `winget install Microsoft.AzureCLI` |
| `docker` | drivers de minikube/kind, deploy-cd-manual | Docker Desktop |
| `python` + `pyyaml` | chequeos python de tests/ | `python -m pip install pyyaml` |
| Java 17 + Maven | run-unit-tests | `winget install Oracle.JDK.17` / zip de Maven en PATH (o agregá `mvnw`) |
| `terraform` | az-login `-UseTerraformOutputs` (opcional) | `winget install Hashicorp.Terraform` |

## Paths consistentes en los ambientes

Todos los scripts resuelven la raíz del repo relativa a su propia ubicación
(`$PSScriptRoot` / `$(dirname "$0")`), así que funcionan desde **cualquier**
directorio de trabajo. El output renderizado va al temp dir del OS por
defecto — nunca al repo (`build/` y similares no están git-ignored a
propósito).

## Placeholders que DEBES reemplazar

| Placeholder | Dónde | Qué poner |
|---|---|---|
| `acr.azurecr.io` (los scripts usan `-Acr acr` por defecto) | `deploy-cd-manual.ps1 -Acr` y el `newName` de cada `cluster/overlays/*/kustomization.yaml` | tu ACR login server (matcheá el `newName` del overlay) |
| `rg-ecommerce-<env>-PLACEHOLDER` / `aks-ecommerce-<env>-PLACEHOLDER` | defaults de `az-login.ps1/.sh` | nombre real de RG / clúster — o usá `-UseTerraformOutputs` |
| `-SubscriptionId <ID>` | `az-login.ps1/.sh` | output de `az account list -o table` |
| `api.<domain>` (en manifiestos) | no es placeholder de script — host del ingress | tu zona DNS (external-dns) |
| password de `argocd-initial-admin-secret` | lo imprime `bootstrap-argocd` | cambiarlo en el primer login |

## Notas

- **PowerShell primero**: los `.ps1` son las implementaciones de referencia
  (curso de Windows). Los `.sh` los reflejan 1:1 con sintaxis bash 3.2+ —
  reportá drift como bug.
- **Sin secretos**: los scripts nunca leen ni escriben credenciales; llaman
  `az login` / `docker login` / `argocd login` interactivamente. Los nombres de
  secretos de GitHub del pipeline cloud (`ACR_PASSWORD`,
  `ARGOCD_AUTH_PASSWORD`, …) NO van en ninguno de estos scripts.
- **Gate de Kyverno**: `cluster/base/kyverno` enforcea `disallow-latest-tag`
  en `ecommerce`. Los applies manuales con imágenes `:latest` se rechazan —
  corré `deploy-cd-manual.ps1` (o el pipeline de CD) para fijar tags reales
  primero.
- **Wiring de CI**: `tests/validate-manifests.ps1` y los chequeos python de
  `tests/manifests/` se superponen con los jobs de lint de `ci.yml`. Nada de
  acá edita `.github/` — cablearlos al CI es un cambio posterior, deliberado.