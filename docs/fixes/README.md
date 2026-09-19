# Corrección de la ejecución de CI del 2026-09-08 (commit 9dc1b82)

Esta carpeta documenta el análisis y la corrección de la ejecución de CI del
2026-09-08 sobre el commit `9dc1b82` (HEAD): **34 de 38 jobs fallaron**, todos
con origen en **7 causas raíz distintas**. Los 4 jobs que pasaron fueron
`Test config-service`, `Test discovery-service`, `Test auth` (sin pom.xml, sus
pasos Maven quedaron omitidos) y `Secret scanning`.

Cada documento de esta carpeta es autocontenido: describe el error observado
(con la línea exacta extraída de `logs_github/*.txt`), la causa raíz, el fix
aplicado, los archivos afectados y cómo verificar la corrección.

## Resumen

| # | Jobs afectados | Error observado | Causa raíz | Fix | Archivo(s) del fix |
|---|----------------|-----------------|------------|-----|--------------------|
| 1 | 17 jobs `Build <service>` | `invalid tag ".azurecr.io/<service>:<sha>": invalid reference format` | La variable de repositorio `ACR_NAME` no está configurada en GitHub; el tag se renderizó con prefijo vacío (`.azurecr.io/...`) | Tag de validación independiente del registro (`<service>:ci-<sha>`) + input `acr_name` explícito en el workflow reutilizable con guard | [01-build-acr.md](01-build-acr.md) |
| 2 | 10 jobs `Test <service>` (search, catalog, returns, cart, notification, checkout, recommendation, shipping, order, payment) | `APPLICATION FAILED TO START ... No spring.config.import property has been defined` | `src/test/resources/application.yml` sombrea al `application.yml` de main en el classpath de test; el `spring.config.import` nunca llega al entorno de test | Deshabilitar el import-check en la config **solo de test** (`spring.cloud.config.import-check.enabled: false`) | [02-config-import.md](02-config-import.md) |
| 3 | 2 jobs `Test` (analytics-svc, inventory-svc) | `Non-parseable POM ... entity reference names can not start with character ' '` | `&` sin escapar en `<description>` (línea 18) de ambos pom.xml | Reemplazar `&` por `&amp;` | [03-pom-xml.md](03-pom-xml.md) |
| 4 | 1 job `Test api-gateway` | `RequestLoggingFilter.java:[35,55] incompatible types: HttpStatusCode cannot be converted to HttpStatus` | Spring Framework 6 / Boot 3: `ServerHttpResponse.getStatusCode()` devuelve `HttpStatusCode` | Declarar la variable como `HttpStatusCode` y ajustar el import | [04-api-gateway-httpstatuscode.md](04-api-gateway-httpstatuscode.md) |
| 5 | 1 job `Test bff-web` | `HomeResponse.java:[15,32] invalid accessor method in record` | El accessor autogenerado `degraded()` del record colisiona con el factory estático `HomeResponse.degraded()` | Renombrar el factory a `degradedResponse()` y actualizar el único call site | [05-bff-web-record-accessor.md](05-bff-web-record-accessor.md) |
| 6 | 1 job `Lint manifests` | `accumulating resources from '../../../services/auth/k8s/base' must resolve to a file` | `admin-credentials.yaml` está ignorado por `.gitignore` (`**/admin-credentials.yaml`) y no existe en el checkout de CI; es un placeholder seguro sin secretos reales | Dejar de ignorar el placeholder y trackearlo (el comentario del kustomization se actualiza) | [06-kustomize-auth-placeholder.md](06-kustomize-auth-placeholder.md) |
| 7 | 2 jobs (`Trivy filesystem scan`, `Generate SBOM`) | `Unable to resolve action aquasecurity/trivy-action@0.24.0` | El tag real de la action es `v0.24.0` (con `v`); `@0.24.0` no existe | Usar `@v0.24.0` en las 4 referencias | [07-trivy-action.md](07-trivy-action.md) |

El documento [08-notas.md](08-notas.md) recoge todo lo que **no** es un error:
jobs que pasaron, advertencias informativas (deprecación de Node.js 20) y los
contratos pendientes para el CD (`ACR_NAME` y prefijo `acr.azurecr.io`).

## Correcciones entorno local (emulador Floci-AZ)

Los siguientes documentos cubren la fase de recuperación del entorno local
basado en el emulador Floci-AZ (AKS local): recreación del clúster k3s,
recuperación del estado de Terraform, wiring del data plane a los sidecars de
Postgres y Kafka, y publicación de imágenes al registry local.

| # | Error observado | Causa raíz | Fix | Archivo(s) |
|---|-----------------|------------|-----|------------|
| 11 | kubectl no responde tras `docker compose up -d floci-az`; sidecar k3s en `Exited(2)` | floci-az detiene el sidecar k3s al recrearse el medio; el contenedor no se borra (datos kine y config sobreviven) | `docker start floci-az-aks-d1d0618a` restaura el mismo clúster; el kubeconfig sigue válido | [11-emulator-recreate-k3s-sidecar.md](11-emulator-recreate-k3s-sidecar.md) |
| 12 | `terraform plan` muestra "8 to add, 2 to change" (VNet, subnet y NSG ausentes del estado) | la recreación del emulador borró los recursos de red anclados; el estado remoto quedó inconsistente | `terraform apply -auto-approve -target` sobre networking/registry/databases; el re-run tras el 404 converge | [12-terraform-drift-recovery.md](12-terraform-drift-recovery.md) |
| 13 | réplicas `Pending` tras recuperar el clúster (nodo único saturado) | los HPA del base escalan por CPU y saturan el scheduling en un solo nodo | fix runtime: borrar HPA (`--all`) y escalar cada deployment a 1 réplica (fix durable en overlay: TODO) | [13-hpa-scale-local-single-node.md](13-hpa-scale-local-single-node.md) |
| 14 | puerto 9093 mudo: el emulador no levanta ningún sidecar Kafka | floci-az:latest solo implementa Artemis/AMQP; la UI anuncia un endpoint que nadie sirve; el compose mapeaba 9093:9093 hacia com.docker.backend | contenedor standalone Redpanda `floci-az-kafka-sc` (`-p 9093:9092`, advertised host.docker.internal:9093) + quitar 9093:9093 del compose | [14-eventhub-kafka-9093.md](14-eventhub-kafka-9093.md) |
| 15 | NXDOMAIN al resolver host.docker.internal dentro de los pods | CoreDNS no conoce el nombre; un `coredns-custom` con segundo plugin `hosts` rompe CoreDNS (CrashLoopBackOff) | parchear el CM `coredns` en `data.NodeHosts` con `172.22.0.1 host.docker.internal` (aplicado desde YAML temporal) | [15-coredns-host-docker-internal.md](15-coredns-host-docker-internal.md) |
| 16 | las apps usan H2; ninguna service habla con Postgres (37865) | wiring a los sidecars nunca completado; envFrom ponía `org.h2.Driver` y el URL H2 venía de los configmaps base | patches JSON6902 en el overlay local con env explícito `jdbc:postgresql://host.docker.internal:37865/ecommerce` + crear la DB `ecommerce` | [16-dataplane-postgres-wiring.md](16-dataplane-postgres-wiring.md) |
| 17 | egress cortado a 37865/9093 (el probe falla solo dentro de `ecommerce`) | las NetworkPolicies base abren egress solo a 443/5432; el default-deny corta 37865 y 9093 | NetworkPolicy aditiva `host-sidecar-egress-policy.yaml` (ipBlock 172.22.0.1/32 en 37865 y 9093) | [17-networkpolicy-host-egress.md](17-networkpolicy-host-egress.md) |
| 18 | checkout `COMPLETED` pero order-events/payment-events vacíos | no existía ningún productor: CheckoutService solo guardaba el record en memoria | `CheckoutEventPublisher.java` (OFF por defecto, activado con `CHECKOUT_EVENTS_KAFKA_ENABLED=true`) + tests unitarios | [18-kafka-producer-checkout-svc.md](18-kafka-producer-checkout-svc.md) |
| 19 | el pod sigue con código viejo tras rebuild+push+rollout; `docker push` falla con error HTTP/HTTPS | `imagePullPolicy=IfNotPresent` usa la imagen cacheada por tag; el daemon de Docker Desktop exige HTTPS contra un registry HTTP | bump de tag `870f35a` solo para checkout-svc + push vía crane (`--insecure`) sin tocar daemon.json | [19-image-tag-bump-crane-push.md](19-image-tag-bump-crane-push.md) |
| 20 | `GET /api/cart/{id}` no trae `"total"` y el checkout responde con `"total":0` (eventos de Kafka con amount 0) | anti-patrón Jackson: el accessor `total()` no tiene prefijo `get`/`is`, así que Jackson no lo serializa → `fetchCartTotal` recibe `null` y cae al fallback `BigDecimal.ZERO` | `@JsonProperty("total")` sobre `Cart.total()` (sin renombrar el método) + test de serialización | [20-cart-total-serialization.md](20-cart-total-serialization.md) |
| 21 | checkout se cuelga ~60 s con Kafka caído (stall `max.block.ms`) | `KafkaTemplate.send()` bloquea hasta `max.block.ms=60000` por defecto cuando el broker no responde metadata; el `whenComplete` async no evita el stall del hilo | acotar `max.block.ms=1500` bajo `spring.kafka.producer.properties` + test ligero de binding | [21-checkout-kafka-max-block-ms.md](21-checkout-kafka-max-block-ms.md) |
| 22 | ArgoCD queda en `default` al instalar, y el bootstrap depende de un remote base de kustomize | el `install.yaml` oficial v2.12.3 no define `namespace` en los objetos namespaced (se inyecta con `-n argocd`); el remote base git acopla el install a red/upstream y kustomize v5 deprecó los remote bases | manifests v2.12.3 vendored como `install.yaml` local + kustomization sin remote base (`resources: [install.yaml]`) + comando canónico `kubectl apply -k cluster/base/argocd/install -n argocd` | [22-argocd-local-bootstrap.md](22-argocd-local-bootstrap.md) |
| 23 | KPS app en `Unknown` con `ComparisonError`: `failed to get git client for repo https://github.com/<org>/<repo>.git` | el app-of-apps renderiza los children desde `origin/main`, que aún tenía el placeholder `repoURL` (R2 sin commit/push) | `repoURL=https://github.com/Damianpiazz/K8s.git` + `targetRevision=main` en los 7 manifests con placeholder; path `$values` verificado; root `ecommerce-apps` `Synced/Healthy`; KPS queda **pendiente** hasta commit+push | [23-argocd-app-of-apps-kube-prometheus-stack.md](23-argocd-app-of-apps-kube-prometheus-stack.md) |

## Correcciones de plan y paridad (2026-09-18)

| # | Error / contexto | Causa raíz | Fix | Archivo(s) |
|---|------------------|------------|-----|------------|
| 24 | 4 docs de plan borrados del tree sin commitear + 13 referencias rotas a ellos | planes viejos obsoletos por el plan de paridad floci ↔ Azure | `docs/PLAN.md` canónico + repunte de las 13 referencias (10 archivos) | [24-docs-plan-refinamiento.md](24-docs-plan-refinamiento.md) |
| 25 | floci sin monitoreo: el overlay local borraba la Application `kube-prometheus-stack`, los 18 ServiceMonitors y las 5 PrometheusRules; Keycloak sin métricas y su SM apuntaba a `/actuator/prometheus` | exclusión por nombre diseñada para el bootstrap directo (fix 22); quedó obsoleta con la paridad GitOps; Keycloak no expone `/actuator/prometheus` (con `--metrics-enabled=true` sirve `/metrics` en el puerto HTTP 8080 en KC ≤24; el puerto de management 9000 existe solo desde KC 25+) | el overlay local conserva KPS + 18 SMs + 5 rules (fix 13 HPA intacto); Keycloak con `--metrics-enabled=true` + `--health-enabled=true` en base; SM de auth apunta a `port: http` + `/metrics`; suite de manifests gana el check de wiring SM→Service→Deployment | [25-monitoreo-local-floci.md](25-monitoreo-local-floci.md) |
| 26 | dashboard RED pegado de Grafana 11 renderiza vacío en el stack | el dashboard externo referencia familias que Micrometer no emite: `http_server_request_duration_seconds_*` (singular), `http_requests_active`/`http_requests_total` (inexistentes) y labels `service`/`http_route` (acá son `job`/`uri`) | rewrite contra las familias reales `http_server_requests_seconds_{count,sum,bucket}` (tags `uri`/`method`/`status`) en `observability/dashboards/red-metrics.json`; panel "Active Requests" → "Services Up" (`up`); provisionado como ConfigMap con label `grafana_dashboard: "1"` | [26-red-dashboard.md](26-red-dashboard.md) |
| 27 | `env: production` hardcodeado en base miente en local/dev/staging; overlays renderizan `:latest`; password de Grafana como placeholder en values; runbooks de alertas apuntan a `example.com` | el base era la única fuente del label `env` y las imágenes; el chart de Grafana (8.4.x) soporta `admin.existingSecret` que nadie usaba; alertas con TODO pendiente | label `env` por overlay (pods + Namespace ecommerce, parches inline); `images:` con tags reales en los 4 overlays + `build-push.ps1` mantiene el SHA corto local; Grafana vía ExternalSecret `grafana-admin-credentials` (fallback local); runbooks reales en las 4 alertas | [27-desacoplamiento-entorno.md](27-desacoplamiento-entorno.md) |
| 28 | UIs sin ingress/TLS/SSO (ArgoCD y Grafana solo con port-forward); floci sin GitOps total: el overlay local seguía borrando las 6 Applications de operadores, 4 ClusterPolicies y el ClusterIssuer selfsigned; sin Application propia para los servicios; 18 HPA excluidos (fix 13, pre-GitOps) | la Fase 4 quedó pendiente tras el bootstrap (fix 22); el SSO dependía de 3 placeholders sin sincronizar y de clients del realm que no existían; la exclusión por nombre de Applications era del bootstrap directo, obsoleta con la paridad GitOps (fix 25 solo revirtió KPS) | ingress de ArgoCD/Grafana con TLS por issuer (`argocd-tls`/`grafana-tls`/`auth-tls`); SSO OIDC por overlay: `argocd-cm`/`argocd-rbac-cm`/`oidc-argocd-secret` + clients `argocd`/`grafana` + grupos `ecommerce-admins` en el realm; `envFromConfigMaps` de Grafana como objeto (`{name: grafana-oauth-config}`, contrato del subchart 8.4.x); floci GitOps total: se importan las 6 Applications + 4 ClusterPolicies + ClusterIssuer selfsigned + 18 HPA, nueva Application `ecommerce-local`, suplemento `argocd-extra` (hostAliases) | [28-uis-ingress-tls-sso-gitops-floci.md](28-uis-ingress-tls-sso-gitops-floci.md) |
| 29 | Azure no desplegable: sin Key Vault ni fuente de credenciales (ExternalSecrets apuntaban al placeholder `kv-<env>-ecommerce`), sin SASL/Event Hubs para Kafka, realm con redirectUris inválidas (`<domain>`, aborta la importación), ingresses/SSO con `<domain>`, y contrato de build del frontend ambiguo | la Fase 5 quedó pendiente tras el fix 28; el plan asumía ARGs de build del frontend sin verificar el default real del Dockerfile; los overlays Azure no tenían puntas reales de secrets ni hosts reales | módulo terraform `keyvault` (RBAC + identidad kubelet + 8 secrets sembrados, outputs `keyvault_vault_url`/`keyvault_tenant_id`/`eso_identity_client_id`); ExternalSecrets reales `oidc-argocd`, `grafana-oauth-credentials`, `event-hubs-credentials` (ManagedIdentity + IMDS); SASL Event Hubs por patch Azure (checkout/notification) + env-config con FQDNs reales (`eh-<env>-ecommerce-<suffix>`); realm por overlay con hosts del zone (contract realm↔KV↔Secret); Grafana vía `envValueFrom`; frontend: contrato `MOCK=false` verificado (default Dockerfile, CI sin build args, sin cambio de código) | [29-azure-desplegable.md](29-azure-desplegable.md) |
| 30 | servicios con datasource seguían en H2 en memoria (Azure incluido: los patches de prod solo comentaban el switch); auth en `start-dev` con H2 embebido también en Azure; local dependía del postgres del emulador con puerto DINÁMICO (37865) y el `random_password "db"` del KV no coincidía con la password real del Flexible Server | la Fase 6 quedó pendiente; el fix 29 desplegó la forma sin el fondo (datasource gestionado nunca activado); el KV sembraba una db-password que el servidor real nunca tuvo | base sin `jdbc:h2` (datasource por overlay, `env` > `envFrom`); local: sidecar `postgres:16-alpine` POR POD (127.0.0.1:5432/DB propia, adiós 37865); Azure: `jdbc:postgresql://${DB_HOST}:${DB_PORT}/<db>` desde `db-credentials` (ES→KV, `db-username`/`db-password` = las MISMA tfvars del módulo databases, se eliminó `random_password "db"`); Keycloak Azure `kc.sh start` + `--db=postgres` + `--proxy-headers=xforwarded` + `KC_DB_URL` (server compartido, DB `keycloak` en `postgres_databases`); egress local recortada a Kafka | [30-paridad-fina-postgres-keycloak.md](30-paridad-fina-postgres-keycloak.md) |

## Cómo se validó esta corrección

Verificación local (sin red, sin Maven/Java):

1. **YAML**: todos los workflows y los 10 `application.yml` de test se parsean
   con `yaml.safe_load` (`YAML OK`).
2. **XML**: ambos pom.xml se parsean con `xml.etree.ElementTree` (`XML OK`).
3. **Kustomize**: `kubectl kustomize cluster/overlays/{dev,staging,prod}`
   renderiza sin errores del tipo `must resolve to a file`; además la suite
   `python tests/manifests/test-kustomize-build.py` pasa íntegra
   (`KUSTOMIZE BUILD CHECK PASSED`).
4. **Grep de contratos**: `trivy-action@` solo aparece como `@v0.24.0`;
   `ACR_NAME` ya no aparece en `ci.yml`; `reusable-build.yml` expone el input
   `acr_name` con guard; `cd.yml` lo pasa explícitamente.
5. **Java**: los dos fixes (api-gateway y bff-web) se validaron por lectura
   cuidadosa de tipos, imports y call sites.

> **Nota sobre Maven/Java**: este entorno local no tiene instalados Maven ni
> Java (no hay `mvn`, `java` ni wrappers `mvnw`). Los tests de Spring que
> dependen de compilación se validarán en la **próxima ejecución de CI**, que
> es la verificación final de los fixes 2 a 5.

## Siguiente paso

Revisar el diff completo (docs + código), hacer commit y push para disparar la
nueva ejecución de CI y confirmar que los 38 jobs pasan.