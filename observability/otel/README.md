# observability/otel — Wiring OTLP: solo guía

## Decisión: sin sidecar, sin collector de fallback, sin agente reinventado

El brief de la Fase 8 consideró un "fallback a OTLP si el clúster no tiene
CRDs de ServiceMonitor". Ese fallback **no es necesario**:

1. **Los CRDs de ServiceMonitor existen** — el chart de kube-prometheus-stack
   (Fase 3) los instala, y los values desplegados abren
   `serviceMonitorSelector` / `serviceMonitorNamespaceSelector`
   (`cluster/base/monitoring/kube-prometheus-stack/values.yaml`).
2. **El endpoint OTLP ya existe** — el OpenTelemetry Collector de la Fase 3
   (`cluster/base/monitoring/opentelemetry-collector/collector.yaml` +
   `service.yaml`) escucha en `:4317` (gRPC) y `:4318` (HTTP) en el namespace
   `observability`.
3. **El path de métricas ya está cableado** — cada pom de servicio declara
   `spring-boot-starter-actuator` + `micrometer-registry-prometheus` (verificado
   en los 17 `services/*/pom.xml`), que es exactamente lo que los
   ServiceMonitors de `../servicemonitors` scrapean en `/actuator/prometheus`.

## Qué contiene este directorio

- `otel-env.yaml` — un ConfigMap de referencia NO aplicado que documenta las
  env vars que un servicio necesita para exportar OTLP (métricas/traces) al
  collector, con el endpoint in-cluster correcto:
  `http://otel-collector.observability.svc.cluster.local:4317`.

## Qué necesitan hacer los estudiantes (lado aplicación, no este directorio)

- Para **métricas OTLP**: agregá `micrometer-registry-otlp` al pom del
  servicio y seteá `OTEL_METRICS_EXPORTER=otlp` (+
  `OTEL_EXPORTER_OTLP_ENDPOINT`). Micrometer después empuja al exporter
  `prometheus` del collector (`:8889`), así que las métricas TAMBIÉN llegan a
  Prometheus sin cambiar el ServiceMonitor.
- Para **traces**: agregá el agente Java de OpenTelemetry a la imagen del
  runtime (`-javaagent:...`) con `OTEL_TRACES_EXPORTER=otlp`. El collector
  reenvía los traces a `otlphttp/tempo` (un placeholder hasta que Tempo esté
  desplegado, ver los comentarios del ConfigMap del collector).
- El scrape de Prometheus existente sigue siendo la fuente de verdad para los
  dashboards/alertas de este árbol — OTLP es aditivo, no un reemplazo.

## Por qué NO hay manifiesto de sidecar de agente acá

Un `otel-agent-sidecar.yaml` duplicaría infraestructura que el collector ya
provee (él ES el agente del clúster). El cambio por servicio es dos líneas de
pom + unas pocas env vars — un sidecar forzaría cambios de imagen y duplicaría
el footprint de memoria del agente Java en un clúster de demo con presupuesto
$0. Si un servicio futuro no se puede instrumentar a nivel de app,
re-evaluamos entonces.