# Fix 26 — Dashboard RED reescrito contra las métricas reales (Micrometer)

## Fecha
2026-09-18

## Error / contexto observado
El usuario pegó un dashboard RED externo (Grafana 11) y todos los paneles
renderizaban **vacíos** en el stack local/Azure. El dashboard hacía referencia
a familias y labels que **este stack no emite**:

- `http_server_request_duration_seconds_*` (singular "request"): familia
  inexistente. Micrometer/Spring Boot acá expone `http_server_requests_seconds_*`.
- `http_requests_active` y `http_requests_total`: no existen en el export de
  `/actuator/prometheus` de estos servicios.
- Labels `service` y `http_route`: acá el label de identidad de la serie es
  `job` (los ServiceMonitors usan `jobLabel: app`, así `job` = nombre del
  servicio) y el path se llama `uri`.

Resultado: PromQL sin series que matcheen → paneles sin datos, sin errores
visibles de consulta.

## Causa raíz
El dashboard externo estaba diseñado para el ecosistema del que salió (otro
exportador / convención de labels) y se pegó sin adaptar al contrato real del
stack. El contrato real (verificado en los ServiceMonitors y en las series que
emiten los servicios):

- Familia: `http_server_requests_seconds_count` / `_sum` / `_bucket`.
- Tags: `uri` (path template, p. ej. `/api/cart/{id}`), `method`, `status`,
  `outcome`, `exception`.
- `job` = nombre del servicio (ServiceMonitor `jobLabel: app`).
- Además disponibles: `up`, `process_uptime_seconds`, `process_cpu_usage`,
  `jvm_memory_*`, `jvm_gc_pause_seconds`.
- El frontend (Next.js) no tiene métricas ni ServiceMonitor: queda fuera.

## Fix aplicado
Rewrite completo en `observability/dashboards/red-metrics.json`
(uid `red-metrics-dashboard`, título "RED — API Platform"):

| Referencia del dashboard externo | Real en este stack |
|---|---|
| `http_server_request_duration_seconds_bucket/count/sum` | `http_server_requests_seconds_bucket/count/sum` |
| `service` | `job` (via ServiceMonitor `jobLabel: app`) |
| `http_route` | `uri` |
| `http_requests_active` | **reemplazado** por "Services Up" (`up`) |
| `http_requests_total` | `sum(rate(http_server_requests_seconds_count[5m]))` |

Paneles (todos con PromQL contra las familias reales):

1. **Summary**: RPS, Error Rate % (5xx/total × 100), Latency avg, Latency p95,
   Latency p99 (stats con thresholds).
2. **Rate**: RPS by Endpoint (`by (uri)`), RPS by Method (`by (method)`),
   gauge de throughput.
3. **Errors**: Error Rate % (`by (job)`), tasa de 5xx, tasa de 4xx, bargauge de
   5xx `by (uri)`.
4. **Duration**: timeseries p50/p90/p95/p99 (con línea de referencia "target 1s")
   y bargauge de p95 `by (uri)`.
5. **System State**: stat **"Services Up"** por servicio (`up{...}`), reemplazo
   del panel "Active Requests" del dashboard original (esa métrica no existe en
   Micrometer; el estado de servicios es lo que el usuario quiere ver).

Thresholds: error rate yellow 1 / red 5 (en %); latencia yellow 0.5 s /
red 1.0 s (p95 por endpoint y stats).

Variables de templating, env-agnósticas (mismo estilo que `service-sli.json`):

- `$namespace` (default `ecommerce`, `label_values(namespace)`),
- `$app` (`label_values(up{namespace=~"$namespace"}, job)`, multi + All),
- `$uri` (`label_values(http_server_requests_seconds_count{...}, uri)`),
- `$method` (encadenada a `$uri`),
- `$env` (`label_values(up{namespace=~"$namespace"}, env)`): **se activa
  completamente en la Fase 3** (label `env` correcto por overlay); hoy con
  default `allValue: ".*"` es un no-op y todas las consultas siguen
  funcionando.

Todas las consultas que filtran series usan
`{namespace=~"$namespace", job=~"$app", uri=~"$uri", method=~"$method", env=~"$env"}`;
los histogram_quantile agregan `by (le)` (y `by (le, uri)` para el bargauge).

Provisioning: entrada `grafana-dashboard-red-metrics` en
`observability/dashboards/kustomization.yaml` (configMapGenerator + label
`grafana_dashboard: "1"` + `app.kubernetes.io/part-of: ecommerce-platform`),
idéntica al patrón de los otros 3 dashboards.

## Archivos afectados
- `observability/dashboards/red-metrics.json` (nuevo)
- `observability/dashboards/kustomization.yaml` (entrada del ConfigMap)
- `docs/fixes/README.md` (índice) y `docs/PLAN.md` (estado de Fase 2)

## Cómo verificar
```powershell
# JSON válido:
python -m json.tool observability/dashboards/red-metrics.json > $null

# Ninguna consulta referencia familias/labels muertas (sin resultados):
rg -n "http_server_request_duration_seconds|http_requests_active|http_requests_total|http_route" observability/dashboards/red-metrics.json

# La suite builda (43 targets) y el render de dev expone el ConfigMap con el
# label del sidecar:
python tests/manifests/test-kustomize-build.py
kubectl kustomize cluster/overlays/dev | Select-String -Context 0,3 "grafana-dashboard-red-metrics"

# En Grafana (una vez sincronizado): el dashboard aparece vía sidecar y cada
# panel muestra series de http_server_requests_seconds_*.
```