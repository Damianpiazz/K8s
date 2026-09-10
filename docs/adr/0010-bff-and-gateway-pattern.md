# ADR-0010: Patrón BFF + API Gateway (api-gateway + bff-web)

- **Estado**: propuesto
- **Fecha**: 2026-09-07
- **Decisores**: equipo de plataforma

## Contexto

El tráfico externo entra a la plataforma a través de ingress-nginx hacia el
deployment `api-gateway` (Spring Cloud Gateway, puerto 8080, el único ingress
con host público `api.<domain>`). Los servicios de negocio (catalog, cart,
order, …) están detrás. Existe un segundo tier front-facing: `bff-web`, un
backend-for-frontend que compone y modela respuestas para clientes web. La
pregunta es dónde viven las preocupaciones cross-cutting y la composición
específica por cliente.

## Decisión

- **api-gateway = el gateway edge**: terminación TLS en el ingress, validación
  JWT (ADR-0005), CORS, ruteo hacia servicios por nombre de discovery
  (ADR-0004), y el límite de confianza de NetworkPolicy (solo ingress-nginx
  puede alcanzarlo; él puede alcanzar todos los pares de ecommerce).
- **bff-web = la capa de composición para clientes browser/SPA**: agrega
  llamadas a varios servicios en un payload amigable para el cliente, así el
  SPA hace un solo round trip y nunca habla directo con los servicios de
  negocio.
- Ambos son ciudadanos completos del layout uniforme de servicios (k8s/base +
  overlay de prod + HPA/PDB/NetworkPolicy + ServiceMonitor); bff-web enruta
  via discovery como cualquier otro peer.
- El camino browser → bff-web → servicios es el flujo de llamadas
  **recomendado** para tráfico de UI; la comunicación servicio-a-servicio
  sigue siendo directa (sin hop del bff).

## Consecuencias

- Un hop extra para el tráfico web (el bff lo agrega a nivel de API — ganancia
  neta para UIs chatty).
- El gateway se mantiene delgado y genérico; el modelado específico de cliente
  se mueve al bff, que puede evolucionar por cliente más adelante (bff mobile,
  bff partner) sin tocar el gateway.
- Dos deployments más para asegurar/observar — ambos siguen exactamente el
  mismo contrato, así que el tooling (tests, dashboards) aplica sin cambios.
- bff-web debe replicar la historia de auth del gateway (o confiar en el
  header del gateway) — el diseño de propagación JWT entre gateway y bff
  todavía está por finalizar (punto abierto para la fase de implementación).

## Alternativas consideradas

- **Solo gateway (sin bff)**: lo más simple (9 servicios alcanzables
  directo), pero cada cambio de UI fuerza cambios de rutas/agregación en el
  gateway y el SPA hace N round trips — descartado como camino primario.
- **GraphQL gateway**: la agregación schema-driven es elegante pero agrega
  toda una capa de query (graphql-java, gobernanza de schema) al alcance del
  TP — diferido; el patrón bff cubre la misma necesidad imperativamente.
- **Agregación a nivel de ingress (subrequests de nginx)**: posible pero
  empuja lógica de negocio a la config del ingress — descartado: las
  políticas y probes son uniformes por servicio, y la composición por
  subrequests es una pesadilla de config para mantener.