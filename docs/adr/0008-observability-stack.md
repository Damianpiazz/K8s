# ADR-0008: Stack de observabilidad — Prometheus, Grafana, OTel collector, SLIs

- **Estado**: aceptado
- **Fecha**: 2026-09-07
- **Decisores**: equipo de plataforma

## Contexto

La demo debe mostrar que la plataforma es observable: dashboards que prueban
tráfico, alertas que se disparan en fallas reales, y un camino de scrape que
cada servicio respeta. La Fase 3 instaló kube-prometheus-stack como chart
Helm (Prometheus + Grafana + Alertmanager) con selectors abiertos
(`serviceMonitorSelector: {}`, `ruleSelector: {}`); la Fase 6 trae las
definiciones de SLI y los dashboards. Traces y logs quedaron como placeholders
en la configuración del collector de OTel.

## Decisión

- **Métricas**: kube-prometheus-stack desde la Application de chart
  (`cluster/base/argocd/applications/kube-prometheus-stack.yaml`); scraping a
  nivel de aplicación via 17 ServiceMonitors en `observability/servicemonitors/`
  (namespaceSelector → `ecommerce`, `jobLabel: app`, path
  `/actuator/prometheus`, puerto `http`).
- **Dashboards**: ConfigMaps sidecar de Grafana (`grafana_dashboard: "1"`,
  `uid: prometheus` datasource) en `observability/dashboards/` —
  spring-boot-overview, ecommerce-platform, service-sli.
- **Alertas**: 4 PrometheusRules en `observability/alerts/` (down, tasa de
  5xx, latencia p99, presión de heap JVM) alineadas a la tabla de SLI en
  `observability/README.md` sobre ventanas de 5 minutos.
- **Traces**: OTel Collector
  (`cluster/base/monitoring/opentelemetry-collector`) es la única entrada del
  pipeline; los backends de Tempo/Loki permanecen como placeholders con
  comentarios (no tienen Application dedicada todavía).
- Convención de SLI por servicio (label `job`): disponibilidad (up), tasa de
  error (`http_server_requests_seconds_count` 5xx), latencia p99, heap JVM.

## Consecuencias

- El namespace observability es la única entrada de scrape (las
  NetworkPolicies por servicio ya permiten el namespace `observability` +
  `app.kubernetes.io/name: prometheus` — `network-policies.mmd` lo muestra).
- Amigable con el presupuesto de muestra: intervalo de scrape de 30s,
  retención pequeña — suficiente para un clúster de demo de un solo nodo.
- Los ServiceMonitors deben vivir en `observability` con
  `matchNames: [ecommerce]` (la trampa clásica de "el monitor en su propio
  namespace no ve nada" está documentada en el header del archivo y testeada
  por `test-service-contract.py`).
- El password de admin de Grafana viaja como placeholder de values —
  cambiarlo por external-secrets (ADR-0007) en despliegues reales.

## Alternativas consideradas

- **Metrics Server + kubectl top solamente**: prueba que el HPA funciona pero
  no da dashboards de demo ni alertas; descartado.
- **Azure Monitor / Prometheus gestionado**: el camino gestionado existe para
  Azure, pero kube-prometheus-stack sigue siendo portable entre
  minikube/kind/AKS y coincide con el repo de referencia — elegido por
  portabilidad del TP.
- **Jaeger/Zipkin para traces**: el repo de referencia usó Zipkin; el
  collector de OTel deja la puerta abierta a ambos — Tempo elegido como
  placeholder moderno por defecto.