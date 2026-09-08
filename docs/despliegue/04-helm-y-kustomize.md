# Helm y Kustomize: templating de manifiestos

## Introducción

Helm y Kustomize son las dos herramientas estándar para **gestionar los manifiestos** de Kubernetes a escala. Resuelven el mismo problema (manifiestos repetitivos y difíciles de parametrizar) con filosofías **distintas**:

| | **Helm** | **Kustomize** |
| --- | --- | --- |
| Modelo | **Empaquetador** (charts + releases) | **Transformador** (base + overlays) |
| Metodología | Plantillas Go con valores (`{{ .Values... }}`) | Parches de YAML puro (sin plantillas) |
| Instalación | `helm install` (cliente + tiller eliminado en v3) | `kubectl apply -k` o `kustomize build` |
| Versiones/rollback | Sí (releases con revisión y rollback) | No (el versionado lo hace Git) |
| Paquetes/repos | Sí (chart repositories) | No (directorios) |
| Mejor para | Empaquetar apps/componentes reutilizables y de terceros | Personalizar manifiestos propios por entorno sin templating |

## Helm

Helm es el **gestor de paquetes** de Kubernetes. Un **chart** es un paquete preconfigurado de recursos; una instalación de un chart se llama **release**.

### Estructura de un chart

```text
mi-chart/
├── Chart.yaml            # metadatos (nombre, versión, dependencias)
├── values.yaml           # valores por defecto (sobrescribibles)
├── charts/               # dependencias (sub-charts)
└── templates/            # plantillas Go que generan los YAML
    ├── deployment.yaml
    ├── service.yaml
    └── _helpers.tpl
```

- **`Chart.yaml`**: describe el chart (nombre, versión, descripción, dependencias).
- **`values.yaml`**: valores por defecto que el usuario puede sobrescribir.
- **`templates/`**: plantillas con sintaxis Go (`{{ .Values.replicaCount }}`, `{{ .Release.Name }}`) que Helm renderiza antes de enviar a Kubernetes.

### Flujo de trabajo

```text
helm repo add bitnami https://charts.bitnami.com/bitnami   # agregar un repo de charts

helm install <release> <chart>                              # instalar
helm install <release> <chart> --namespace <ns>
helm install <release> <chart> --set image.tag=v2          # sobrescribir un valor
helm install <release> <chart> --values mi-values.yaml     # sobrescribir con archivo
helm upgrade <release> <chart> -f mi-values.yaml           # actualizar
helm rollback <release> <revision>                         # volver a una revisión
helm uninstall <release>                                    # desinstalar
helm list                                                   # releases instalados
```

### Validaciones previas

```text
helm lint <chart>              # valida el chart
helm template <chart>          # renderiza los YAML sin instalar (dry-run client)
helm install <release> <chart> --dry-run --debug
```

### Cuándo usar Helm

- Desplegar **aplicaciones de terceros** (Prometheus, WordPress, Jenkins, ingress-nginx) desde repos de charts.
- **Empaquetar** la propia aplicación para distribuirla o reutilizarla.
- Necesitar **rollback de releases** y gestión de versiones.
- Ejemplo real: el chart de WordPress de [casos-de-uso/web-y-cms.md](../casos-de-uso/web-y-cms.md), y Prometheus vía chart en [casos-de-uso/observabilidad.md](../casos-de-uso/observabilidad.md).

## Kustomize

Kustomize **no usa plantillas**: trabaja con los YAML de Kubernetes **tal cual** y los **transforma** con parches y generadores. Es parte integrante de `kubectl` (`kubectl apply -k`).

### Modelo base + overlays

- **base/**: los manifiestos comunes (deployment, service) con su `kustomization.yaml`.
- **overlays/**: personalizaciones por entorno (dev, staging, prod) que referencian la base y le aplican parches.

```text
app/
├── base/
│   ├── kustomization.yaml      # resources: deployment.yaml, service.yaml
│   ├── deployment.yaml
│   └── service.yaml
└── overlays/
    ├── staging/
    │   └── kustomization.yaml  # resources: ../../base + namePrefix/commonLabels/patches
    └── prod/
        └── kustomization.yaml  # resources: ../../base + patches (más réplicas)
```

### Funciones principales del `kustomization.yaml`

| Campo | Qué hace |
| --- | --- |
| `resources` | Referencia la base u otros manifiestos |
| `patches` (strategic/JSON) | Modifica campos de los recursos (réplicas, CPU, imágenes) |
| `namePrefix` / `nameSuffix` | Prefija/sufija los nombres (para aislar entornos) |
| `commonLabels` / `commonAnnotations` | Agrega labels/annotations a todos los recursos |
| `configMapGenerator` / `secretGenerator` | Genera ConfigMaps/Secrets desde archivos, con hashes que fuerzan el rollout |

### Flujo de trabajo

```text
# Desarrollar: construir la salida
kustomize build overlays/prod

# Desplegar directamente con kubectl (recomendado)
kubectl apply -k overlays/prod
```

### Cuándo usar Kustomize

- **Personalizar YAML propios** por entorno sin aprender templating.
- Mantener los manifiestos **legibles y en YAML puro**.
- Viene **integrado en kubectl** (no requiere instalación adicional).

> **Tip**: Kustomize también puede **consumir Helm charts** (campo `helmCharts` en el `kustomization.yaml`), combinando lo mejor de ambos: el empaquetado de Helm con la composición de Kustomize.

## Helm vs. Kustomize: cuándo usar cada uno

| Situación | Recomendación |
| --- | --- |
| Instalar software de terceros (charts públicos) | **Helm** |
| Empaquetar/distribuir la propia app | **Helm** |
| Personalizar YAML propios por entorno | **Kustomize** |
| Sin overhead de templating, manifiestos puros | **Kustomize** |
| Necesito releases con rollback | **Helm** |
| Git como única fuente de verdad (sin historial de releases) | **Kustomize** |

## Resumen

| Herramienta | Modelo | Instalación | Rollback | Mejor para |
| --- | --- | --- | --- | --- |
| Helm | Charts + releases (plantillas Go) | `helm install` | Sí | Paquete de apps/terceros |
| Kustomize | Base + overlays (parches YAML) | `kubectl apply -k` | No (Git) | Personalizar manifiestos propios |

**Siguiente**: [05-gitops.md](05-gitops.md) — automatizar estos despliegues con GitOps.