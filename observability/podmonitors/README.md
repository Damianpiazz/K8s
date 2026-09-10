# PodMonitors — deliberadamente OMITIDOS

El brief original de la Fase 8 pedía un PodMonitor apuntando a los servicios
edge (`api-gateway`, `bff-web`) "si fuera necesario para métricas de Spring
Boot que ServiceMonitor no puede alcanzar".

**Decisión: no hace falta — no se despliega ningún PodMonitor.**

## Por qué

Un PodMonitor solo es necesario cuando un workload expone métricas en un
puerto que no tiene un `Service` de Kubernetes de respaldo (o cuyo endpoint de
métricas difiere del puerto del service). Ninguno de los servicios edge cae en
esa categoría:

- `api-gateway` (puerto **8080**) y `bff-web` (puerto **8090**) definen cada
  uno un `ClusterIP` Service regular con un puerto nombrado `http` →
  `targetPort: http` (verificado en `services/api-gateway/k8s/base/service.yaml`
  y `services/bff-web/k8s/base/service.yaml`).
- Ambos exponen el mismo endpoint de métricas de Actuator que cualquier otro
  servicio: `/actuator/prometheus` (services/README.md: "Every service exposes
  `/actuator/prometheus`").

El ServiceMonitor de `api-gateway` en `../servicemonitors/servicemonitors.yaml`
ya cubre ese path exacto. Un PodMonitor seleccionando los mismos pods crearía
**targets de scrape duplicados** (mismas series, doble carga de scrape en un
clúster de demo con presupuesto $0 y 6h de retención).

## Cuándo SÍ necesitarías un PodMonitor acá

- Si un servicio futuro expone métricas de Prometheus en un **puerto sin
  Service** o en endpoints **localhost/headless** (p. ej. un SDK de
  OpenTelemetry pusheando a un sidecar, o un puerto de métricas
  deliberadamente no publicado como Service).
- Si cambiás los servicios edge para exponer métricas en un puerto de
  management separado que no es parte del puerto `http` del Service.

Si eso pasa: agregá el archivo de PodMonitor acá (selector `app in
[api-gateway, bff-web]`, namespace `ecommerce`, namespaceSelector que matchee
`observability`), listalo en un `kustomization.yaml`, y el ServiceMonitor sigue
funcionando sin tocar nada.

## Wiring

Nada que cablear: este directorio deliberadamente no contiene recursos. El
orquestador NO debería agregar `observability/podmonitors` a ninguna
kustomization (una kustomization vacía no agrega nada, pero mantenerla afuera
mantiene el build limpio).