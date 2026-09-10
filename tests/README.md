# tests/ — Assets de test orientados a integración (Fase 9)

Este directorio guarda **chequeos de contrato a nivel de repo** que
complementan — pero NO reemplazan — los tests JUnit por servicio
(`services/<svc>/src/test/`, 17 suites de Spring Boot, p. ej.
`CatalogServiceApplicationTests`). Los tests JUnit prueban que cada servicio
funciona; estos chequeos prueban que los **manifiestos y el wiring** alrededor
de los servicios sigan matcheando los contratos del repo (services/README.md,
cd.yml, wiring de cluster/overlays, integración de observability/ +
security/).

## Qué verifica cada chequeo

| Chequeo | Comando | Verifica |
|---|---|---|
| `manifests/test-yaml-parse.py` | `python tests\manifests\test-yaml-parse.py` | Cada `.yaml`/`.yml` del repo es YAML válido sin claves de mapeo duplicadas. |
| `manifests/test-kustomize-build.py` | `python tests\manifests\test-kustomize-build.py` | `kustomize build` (o fallback `kubectl kustomize`) renderiza limpio para `cluster/base`, `cluster/overlays/{dev,staging,prod}`, `observability`, `security`, y cada `services/*/k8s/base` + `services/*/k8s/overlays/prod`. Saltea con warning si no hay ninguna de las herramientas. |
| `manifests/test-service-contract.py` | `python tests\manifests\test-service-contract.py` | El contrato de layout de los 17 servicios: `Dockerfile`, los siete manifiestos `k8s/base`, `images[].name == newName == acr.azurecr.io/<svc>`, labels de pod `app` + `env`, `securityContext.runAsNonRoot` + `seccompProfile: RuntimeDefault`, una probe `/actuator/health`, y un puerto `http` en el `Service`. |
| `manifests/test-overlay-wiring.py` | `python tests\manifests\test-overlay-wiring.py` | `cluster/overlays/{dev,staging,prod}`: cada entrada `resources[]` resuelve, `images[]` cubre los 17 servicios con el contrato `newName` del CD, dev/staging apuntan a `k8s/base` mientras prod apunta a `k8s/overlays/prod`, y las capas `observability` + `security` de la Fase 8 están cableadas. |

Los chequeos salen con non-zero en falla y son seguros de correr en Windows
(pathlib + UTF-8 en todos lados). Única dependencia: PyYAML
(`python -m pip install pyyaml`); si falta, los scripts imprimen una pista de
instalación.

## Cómo correrlos

```powershell
# Desde la raíz del repo (los paths se resuelven relativos a la ubicación del
# script, así que cualquier working directory funciona):
python tests\manifests\test-yaml-parse.py
python tests\manifests\test-kustomize-build.py
python tests\manifests\test-service-contract.py
python tests\manifests\test-overlay-wiring.py

# O todo junto más el chequeo del árbol kustomize en una pasada:
.\scripts\tests\validate-manifests.ps1            # PowerShell (Windows)
./scripts/tests/validate-manifests.sh             # bash (Linux/macOS)
```

## Por qué existen estos contratos (tie-in con la Fase 8)

- **Observability**: `test-service-contract.py` chequea las propiedades exactas
  de las que dependen los ServiceMonitors en `observability/servicemonitors/`
  — label de pod `app: <svc>` y un puerto `http` en el Service.
  `test-overlay-wiring.py` confirma que cada env carga la capa
  `observability/`. Sin eso, Prometheus (kube-prometheus-stack) no descubre
  nada y los dashboards de SLI (`observability/dashboards/service-sli.json`)
  quedan vacíos.
- **Security**: los chequeos de `securityContext` (runAsNonRoot,
  `seccompProfile: RuntimeDefault`) son el gate de PSA-restricted de
  `security/pod-security/pod-security-labels.yaml` — el blocker conocido que
  se señala en `security/README.md`. El chequeo de presencia de
  `networkpolicy.yaml` es parte del modelo default-deny: un servicio sin
  NetworkPolicy por servicio queda completamente aislado por
  `security/network-policies/default-deny-all.yaml`.
- **Contrato de CD**: `images[].newName == acr.azurecr.io/<svc>` es
  exactamente el selector que `.github/workflows/cd.yml` usa con `yq` para
  reescribir `newTag`. Si el `newName` de un servicio drift, el pipeline deja
  de bumpear sus imágenes silenciosamente.

## Nota de integración con CI

`.github/workflows/ci.yml` ya corre un kustomize build sobre
`cluster/overlays/*` y tests Maven por servicio. Cablear `tests/manifests/*.py`
al CI (p. ej. un job `test-manifests` que corra los cuatro chequeos) cerraría
el gap entre "renderiza" y "contratos" — **deliberadamente no hecho acá**: el
brief acota esta fase a assets locales + de test, y `.github/` es read-only
para esta fase. Cuando lo cablees, usá `pip install pyyaml` o
`actions/setup-python` + un archivo de requirements.

## Nota de scope

Estos chequeos son estáticos/estructurales. La integración real
(service-to-service sobre el clúster, flujos e2e a través del api-gateway,
load tests) pertenece a una fase posterior — el layout del directorio
(`tests/manifests/`) deja lugar para `tests/e2e/` y `tests/load/` al lado.