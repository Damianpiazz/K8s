# Pods

## Rol

El **Pod** es la unidad mínima de despliegue en Kubernetes y el centro de casi todos los conceptos del clúster. Muchos objetos de Kubernetes están construidos alrededor de los Pods.

Un Pod agrupa **uno o más contenedores** que comparten:

- La misma red (IP única por Pod).
- Almacenamiento (volúmenes).
- Ciclo de vida (se planifican y ejecutan juntos en el mismo nodo).

## Estructura del Pod

- **`metadata`** — nombre, namespace, labels, annotations.
- **`spec.containers`** — lista de contenedores con imagen, puertos, recursos (requests/limits), probes.
- **`spec.initContainers`** — contenedores de inicialización.
- **`spec.schedulerName`** — permite elegir un scheduler personalizado (ver [16-extensiones.md](16-extensiones.md)).

## Multi-container Pods

Los Pods pueden contener múltiples contenedores que se comunican entre sí mediante `localhost`, ya que comparten la red del Pod. Caso típico: un contenedor principal con un **sidecar** (p. ej. proxy, log shipper o agente de observabilidad).

## Init Containers

- Contenedores que se ejecutan **antes** de los contenedores principales.
- Se ejecutan en orden, y el Pod no avanza hasta que todos terminan con éxito (o se reintenta si fallan).
- Útiles para: esperar dependencias (bases de datos), preparar configuraciones o permisos, descargar datos previos.

## Ciclo de vida del Pod

| Fase | Descripción |
| --- | --- |
| `Pending` | El Pod fue aceptado por el clúster pero aún no se ejecuta (esperando scheduler o imágenes). |
| `Running` | Al menos un contenedor principal está en ejecución. |
| `Succeeded` | Todos los contenedores terminaron con éxito (típico de Jobs). |
| `Failed` | Al menos un contenedor terminó en error. |
| `Unknown` | El estado no puede obtenerse (p. ej. fallo de comunicación con el nodo). |

## Container Lifecycle Hooks

Kubernetes permite ejecutar hooks en el ciclo de vida de un contenedor:

- **`PostStart`** — se ejecuta justo después de crear el contenedor.
- **`PreStop`** — se ejecuta justo antes de que el contenedor se detenga (útil para drenar conexiones, graceful shutdown).

## Classes de calidad de servicio (QoS)

| Clase | Condición | Comportamiento |
| --- | --- | --- |
| `Guaranteed` | requests == limits en todos los contenedores | Menor probabilidad de ser terminado por presión de recursos |
| `Burstable` | Al menos un contenedor con requests < limits | Prioridad media |
| `BestEffort` | Sin requests ni limits | Primero en ser terminado ante presión de memoria |

## Prioridad y preemptión

- Las **PriorityClasses** definen la prioridad de los Pods.
- Si un Pod de alta prioridad no puede programarse, el scheduler puede **desalojar (preempt)** Pods de menor prioridad que ocupen los recursos necesarios.

## Afinidad y anti-afinidad

- **Affinity**: reglas para que un Pod se programe cerca de otros Pods o en nodos con ciertas características.
- **Anti-affinity**: reglas para *evitar* que Pods se programen juntos (p. ej. réplicas en nodos distintos para alta disponibilidad).

## Labels y selectors

- **Labels**: pares clave-valor adjuntos a los objetos (y al Pod).
- **Selectors**: permiten a otros objetos (ReplicaSet, Service, NetworkPolicy) seleccionar un conjunto de Pods por sus labels. Son la base del *service discovery* y del ruteo en Kubernetes.

## Pod Disruption Budget (PDB)

- Limita el número de Pods de una aplicación que pueden interrumpirse **voluntariamente** a la vez (drenaje de nodos, actualizaciones, autoscaling).
- Protege la disponibilidad de aplicaciones con múltiples réplicas durante operaciones de mantenimiento.

## Troubleshooting de Pods

Ante fallos se recomienda observar, en orden: estado/fases del Pod (`kubectl describe pod`), eventos, logs (`kubectl logs`), y estado de las probes de liveness/readiness/startup.

## Resumen

| Aspecto | Detalle |
| --- | --- |
| Unidad mínima | Pod: uno o más contenedores que comparten red y almacenamiento |
| Inicialización | Init containers (ejecución secuencial previa) |
| Ciclo de vida | Pending → Running → Succeeded/Failed |
| Hooks | PostStart, PreStop |
| QoS | Guaranteed, Burstable, BestEffort |
| Selección | Labels + selectors |
| Disponibilidad | PDB limita interrupciones voluntarias |

**Siguiente**: [12-workloads.md](12-workloads.md) — objetos que gestionan Pods (Deployment, StatefulSet, DaemonSet, etc.).