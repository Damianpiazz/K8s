# E-commerce Microservices on Kubernetes (Azure)

Plataforma de microservicios e-commerce desplegable en **Azure Kubernetes Service (AKS)** usando Terraform (IaC), Argo CD (GitOps) y CI/CD automatizado con GitHub Actions.

Proyecto práctico universitario: infraestructura como código, GitOps, microservicios, observabilidad y seguridad — todo aplicado a un dominio real de e-commerce.

---

## Arquitectura

```
Browser → Load Balancer → Ingress → API Gateway → Microservicios → Data Layer
                                     ↕                    ↕
                                   Eureka             PostgreSQL / MongoDB
                                   Config             Redis / Kafka
                                     ↕
                                  Argo CD ← Git (este repo)
```

Arquitectura completa con diagrama Mermaid: [docs/plan-ecommerce-k8s.md](docs/plan-ecommerce-k8s.md)

---

## Estructura del repositorio

```
├── .github/workflows/      # CI/CD — GitHub Actions
├── .gitlab-ci.yml          # CI/CD — GitLab CI (alternativa)
├── infra/terraform/        # IaC: módulos por cloud (AKS, networking, DBs)
├── cluster/                # GitOps: base + overlays por entorno
├── services/               # Microservicios (app + k8s/base + k8s/overlays)
├── data/                   # Data layer: Postgres, Redis, Kafka
├── observability/          # Dashboards, alertas, OTEL
├── security/               # RBAC, NetworkPolicies, PSA
├── scripts/                # Utilidades: seed, migraciones, lint
├── docs/                   # Documentación del proyecto
├── tests/                  # Tests contract, e2e, load
├── CODEOWNERS              # Ownership por área
└── README.md
```

---

## Requisitos previos

| Herramienta | Uso |
|---|---|
| [Azure CLI (`az`)](https://learn.microsoft.com/cli/azure/install-azure-cli) | Autenticación y gestión de AKS/ACR |
| [Terraform](https://www.terraform.io/downloads) | Infraestructura como código |
| [kubectl](https://kubernetes.io/docs/tasks/tools/) | Interacción con el clúster |
| [Helm](https://helm.sh/docs/intro/install/) | Charts de componentes de plataforma |
| [Docker](https://docs.docker.com/get-docker/) | Build de imágenes de microservicios |
| Suscripción de Azure | Recursos: AKS, ACR, PostgreSQL, Redis, etc. |

---

## Despliegue rápido

```bash
# 1. Infraestructura (Terraform → AKS + ACR)
cd infra/terraform/envs/dev
terraform init && terraform apply

# 2. Configurar kubectl
az aks get-credentials --resource-group <RG> --name <AKS-NAME>

# 3. Instalar Argo CD
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# 4. Aplicar manifests del clúster
kubectl apply -k cluster/overlays/dev

# 5. Acceso a Argo CD UI
kubectl port-forward svc/argocd-server -n argocd 8080:443
```

---

## CI/CD

### GitHub Actions

Cuatro workflows automáticos:

| Workflow | Trigger | Qué hace |
|---|---|---|
| `ci.yml` | PR + push main | Build, lint, scan (Gitleaks + Trivy), SBOM |
| `cd.yml` | Push main | Build & push imágenes → Argo CD sync / kubectl apply |
| `security.yml` | Semanal + manual | Gitleaks, Trivy, SBOM, dependency review |
| `reusable-build.yml` | Llamado por CI/CD | Build reusable de imágenes Docker → ACR |

### GitLab CI (alternativa)

`.gitlab-ci.yml` en la raíz: pipeline equivalente con `build → scan → test → deploy`.

### Secretos y variables necesarios

Configurar en **Settings → Secrets and variables → Actions** del repositorio:

**Secretos** (Settings → Secrets):

| Secreto | Descripción |
|---|---|
| `AZURE_CREDENTIALS` | JSON del service principal de Azure |
| `ACR_USERNAME` | Usuario del Azure Container Registry |
| `ACR_PASSWORD` | Password del Azure Container Registry |
| `ARGOCD_AUTH_USERNAME` | Usuario de Argo CD |
| `ARGOCD_AUTH_PASSWORD` | Password de Argo CD |

**Variables** (Settings → Variables):

| Variable | Descripción |
|---|---|
| `ACR_NAME` | Nombre del Azure Container Registry |
| `AKS_NAME` | Nombre del clúster AKS |
| `AKS_RG` | Resource group de AKS |
| `ARGOCD_SERVER` | URL del servidor Argo CD |
| `GITOPS` | Estrategia: `argocd` o `kustomize` |

---

## Seguridad

- **Gitleaks**: scanning de secretos en cada PR y semanalmente.
- **Trivy**: escaneo de vulnerabilidades en filesystem y imágenes Docker.
- **SBOM**: generación automática de Software Bill of Materials.
- **CODEOWNERS**: revisión obligatoria por área de responsabilidad.
- NetworkPolicies, RBAC y Pod Security se implementan en fases posteriores.

---

## Documentación

| Sección | Contenido |
|---|---|
| [Arquitectura](docs/arquitectura/README.md) | Componentes, modelo de objetos, workloads, red, extensiones |
| [Casos de uso](docs/casos-de-uso/README.md) | Microservicios, IA/ML, bases de datos, web/CMS, CI/CD, observabilidad, batch |
| [Despliegue](docs/despliegue/README.md) | Setup de clúster, herramientas, Helm/Kustomize, GitOps |
| [Comandos](docs/comandos/README.md) | Referencia de comandos de `kubectl` |
| [Plan del proyecto](docs/plan-ecommerce-k8s.md) | Plan completo: estructura, arquitectura, fases y decisiones |

---

## Créditos

Este proyecto se apoya en los siguientes repositorios de referencia:

- [walidhabbach/Ecommerce-Microservice-Kubernetes](https://github.com/walidhabbach/Ecommerce-Microservice-Kubernetes) — E-commerce de referencia con microservicios Spring Boot, Argo CD y Terraform AKS.
- [kubernetes/examples](https://github.com/kubernetes/examples) — Manifiestos canónicos de Kubernetes.
- [pulumi/examples](https://github.com/pulumi/examples) — Ejemplos de IaC multi-cloud con Pulumi.
- [kelseyhightower/kubernetes-the-hard-way](https://github.com/kelseyhightower/kubernetes-the-hard-way) — Entender Kubernetes desde cero: TLS, kubeconfigs, etcd, control plane.
