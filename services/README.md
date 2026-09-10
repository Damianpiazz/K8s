# Services — Microservicios de e-commerce

Este directorio contiene los microservicios que componen el backend de
e-commerce. Cada servicio sigue **exactamente el mismo layout** para que el
pipeline de CI/CD y los manifiestos GitOps se mantengan uniformes.

## Layout

```
services/<svc>/
├─ src/                           # source y config mínimo pero funcional
│   └─ resources/application.yml  # o application.yaml
├─ Dockerfile                     # build maven multi-stage → runtime JRE 17 (temurin)
├─ .dockerignore
├─ k8s/
│   ├─ base/                      # manifiestos agnósticos de ambiente (namespace ecommerce)
│   │   ├─ deployment.yaml
│   │   ├─ service.yaml
│   │   ├─ hpa.yaml
│   │   ├─ pdb.yaml
│   │   ├─ serviceaccount.yaml
│   │   ├─ networkpolicy.yaml
│   │   ├─ configmap.yaml         # solo cuando el servicio necesita defaults de env
│   │   └─ kustomization.yaml
│   └─ overlays/
│       └─ prod/                  # overlay fino de prod
│           ├─ kustomization.yaml
│           └─ patches/
│               └─ deployment-patch.yaml
```

Los manifiestos **no** están cableados en `cluster/base` (top-level): la
plataforma se despliega via Argo CD apoyándose en una app `services-aggregator`,
o directamente con `kubectl apply -k services/<svc>/k8s/base`. El workflow de
CD (`GITOPS=kustomize`) bumpea los tags de imagen matcheando `newName` en la
kustomization que parchea.

## Cablear los servicios en un overlay de ambiente (one-time, por env)

El workflow de CD (`GITOPS=kustomize`, `.github/workflows/cd.yml`) parchea
`cluster/overlays/{env}/kustomization.yaml`, reemplazando `newTag` para cada
imagen cuyo `newName` sea igual a `<ACR_NAME>.azurecr.io/<svc>` — **ese archivo
debe incluir los overlays de servicios**. En una fase posterior, agregar a cada
`cluster/overlays/{dev,staging,prod}/kustomization.yaml`:

```yaml
resources:
  # ...recursos existentes de cluster/base + env-config...
  - ../../../services/api-gateway/k8s/overlays/prod
  - ../../../services/catalog-svc/k8s/overlays/prod
  - ../../../services/cart-svc/k8s/overlays/prod
  - ../../../services/auth/k8s/overlays/prod
  # config-service / discovery-service son infraestructura — desplegarlos primero

images:                       # refleja el contrato del CD (newName debe matchear ACR_NAME)
  - name: acr.azurecr.io/api-gateway
    newName: acr.azurecr.io/api-gateway
    newTag: latest            # CD lo reescribe al SHA del commit
  - name: acr.azurecr.io/catalog-svc
    newName: acr.azurecr.io/catalog-svc
    newTag: latest
  # ... una entrada por servicio ...
```

> Alternativa (modo Argo CD): apuntá una Application a
> `services/<svc>/k8s/overlays/prod` por servicio, o una única Application
> agregadora a un futuro `cluster/overlays/{env}` que incluya estos paths.

## Nota de la matriz del CD — config-service & discovery-service

La matriz de build del CD en `.github/workflows/cd.yml` cubre `api-gateway`,
`auth`, `catalog-svc`, `cart-svc` y los servicios de fases posteriores, pero
**no** los dos servicios de infraestructura. Opciones:

- Pushearlos manualmente una vez: `docker build -t <acr>.azurecr.io/config-service:latest services/config-service && docker push …`
- O agregar `config-service` / `discovery-service` a las dos matrices de
  `cd.yml` (`build-images`) — un cambio de 2 líneas en una fase posterior.

## Placeholders que DEBES reemplazar

| Placeholder | Dónde | Qué poner |
|---|---|---|
| `acr.azurecr.io` | el `images[].newName` de cada `k8s/**/kustomization.yaml` y la imagen del `deployment.yaml` | tu ACR login server, p. ej. `myacr.azurecr.io` (seteá `ACR_NAME=myacr` en GitHub Actions y actualizá `newName` una vez) |
| `api.<domain>` | host de `api-gateway/k8s/base/ingress.yaml` + `spec.tls[].hosts` | tu zona DNS pública gestionada por external-dns |
| Nombre del Key Vault | `cluster/overlays/*/patches/secret-store-patch.yaml` | el Azure Key Vault que guarda los secretos (gestionado por infra/terraform) |
| Secreto admin de Keycloak | env del deployment de `auth` (`KEYCLOAK_ADMIN_PASSWORD`) | un Secret real (external-secrets → Azure Key Vault preferido) |

## Convenciones comunes

- Java 17, Spring Boot **3.3.x**, Spring Cloud **2023.0.x** (BOM), grupo Maven `com.ecommerce`.
- Cada servicio expone `/actuator/health`, `/actuator/info`, `/actuator/prometheus`, `/actuator/metrics`.
- Cada servicio que necesita discovery importa `spring-cloud-starter-netflix-eureka-client`.
- Imágenes de Docker: `acr.azurecr.io/<svc>:latest` en `base`, y cada overlay declara una
  entrada `images:` para que el CD tagee `newTag` con el SHA del commit.

## Qué espera el CI

`.github/workflows/ci.yml` buildea `services/<name>/Dockerfile` y corre
`mvn -B test --file services/<name>/pom.xml` para cada nombre de su matriz que
tenga Dockerfile/pom. Mantené un `pom.xml` y tests que pasen sin infraestructura
externa (solo in-memory/H2 — nada de DB/Redis/Kafka reales en unit tests).