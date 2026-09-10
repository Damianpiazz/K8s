# ADR-0004: Spring Cloud + Eureka para service discovery (y Config Server)

- **Estado**: aceptado
- **Fecha**: 2026-09-07
- **Decisores**: equipo de plataforma

## Contexto

17 servicios Spring Boot 3.3.x deben encontrarse entre sí sin direcciones
hardcodeadas: el api-gateway enruta hacia los servicios de negocio, y los
servicios resuelven las instancias de otros dentro del namespace ecommerce.
El repo de referencia (walidhabbach) trae Eureka + Spring Cloud Config, y los
servicios ya importan `spring-cloud-starter-netflix-eureka-client` (convención
de `services/README.md`). Spring Cloud 2023.0.x es el BOM en uso.

## Decisión

- **Eureka** (`discovery-service`, puerto 8761) como registro de servicios:
  los servicios se registran vía el cliente de Eureka y resuelven pares por
  nombre lógico.
- **Spring Cloud Config** (`config-service`, puerto 8888) como servidor de
  configuración central — los defaults agnósticos a ambiente vienen de él;
  los overrides por ambiente viajan en el ConfigMap `ecommerce-env-config`
  (envFrom) en vez de perfiles de config-server.
- El api-gateway (Spring Cloud Gateway) usa discovery para enrutar
  (búsquedas estilo `lb://<service>`), así que un servicio nuevo no necesita
  cambios en la tabla de rutas del gateway para resolución de nombres.
- Ambos servicios de infraestructura son ciudadanos de primera clase del
  layout uniforme: `k8s/base` + `k8s/overlays/prod` + HPA/PDB/NetworkPolicy.

## Consecuencias

- Los servicios alcanzan sus pares vía nombres lógicos estables incluso
  cuando las IPs de los Pods cambian — las NetworkPolicies permiten egress
  entre pares de ecommerce por label, así que el tráfico de discovery fluye
  (comentario de política en catalog-svc: `config-service (:8888),
  discovery-service (:8761)`).
- Eureka es un registro stateful-ish: al reiniciar se reconstruye desde
  heartbeats; el deployment mantiene 1 réplica en dev y 3 en prod (overlay).
- Una pieza más para monitorear en `troubleshooting.md` (sección "Eureka no
  se registra") y una dependencia más para que cada servicio arranque antes
  de unirse al registro.
- Config-on-Git vs ConfigMap: el proyecto usa deliberadamente ConfigMaps para
  configuraciones específicas de ambiente para que los secretos y valores de
  ambiente permanezcan en artefactos de GitOps, no en el backend de un
  servidor de configuración.

## Alternativas consideradas

- **Solo DNS nativo de Kubernetes**: `svc.namespace.svc.cluster.local`
  funciona pero no brinda salud a nivel de instancia ni selección
  consciente de LB del lado de Spring; se mantiene como modelo mental de
  fallback.
- **Consul**: mismo trabajo, operador extra (tokens ACL, gossip) sin
  ventaja para un TP de una sola región.
- **istio (service mesh)**: service discovery real + mTLS, pero demasiado
  pesado para el alcance de la demo; PSA/NetworkPolicies ya cubren la
  historia de aislamiento.
