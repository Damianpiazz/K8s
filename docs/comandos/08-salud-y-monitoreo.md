# Salud y monitoreo

Comandos para diagnosticar la salud de los Pods y del clúster: **probes** (readiness/liveness/startup) y eventos. Ver el rol del kubelet en las probes en [06-kubelet.md](../arquitectura/06-kubelet.md) y el ciclo de vida del Pod en [11-pods.md](../arquitectura/11-pods.md).

## Probes: readiness y liveness

Kubernetes define sondas que determinan el estado de un contenedor:

| Probe | Objetivo | Si falla |
| --- | --- | --- |
| **Liveness** | ¿El contenedor sigue vivo? | Reinicia el contenedor |
| **Readiness** | ¿Está listo para recibir tráfico? | Lo saca de los Service Endpoints |
| **Startup** | ¿Terminó de arrancar? | Protege liveness en apps de arranque lento |

## `kubectl describe pod <pod_name>`

En la sección del contenedor se muestra la configuración de las **probes** (Readiness/Liveness/Startup: sus endpoints, puertos y umbrales), y en `Events` los reinicios por fallo de liveness.

```text
kubectl describe pod <pod_name>
```

> Es la forma principal de "ver" el estado de las probes. Observar la columna `RESTARTS` de `kubectl get pods` y los eventos por fallo de `Liveness probe failed` / `Readiness probe failed`.

> Nota: no existe un comando `kubectl get readiness/liveness probe ...`; el estado de las probes se inspecciona con `describe` (estado de readiness visible también en las condiciones del Pod) y con la práctica de monitoreo.

## `kubectl get events`

Lista **los eventos** de los recursos del namespace actual (creación de objetos, fallos de scheduling, reinicios, errores de imágenes, etc.).

```text
kubectl get events
kubectl get events --sort-by='.lastTimestamp'   # ordenados por tiempo
kubectl get events -A                            # todos los namespaces
```

> Los eventos son la fuente de diagnóstico por excelencia: revelan por qué un Pod no se programa (scheduler), no arranca (imagen, crash) o se reinicia (probe fallida). Ver [03-kube-scheduler.md](../arquitectura/03-kube-scheduler.md).

## Resumen

| Comando | Qué hace |
| --- | --- |
| `kubectl describe pod <pod_name>` | Muestra estado de probes (readiness/liveness/startup) y eventos del Pod |
| `kubectl get events` | Lista eventos de los recursos del namespace |
| `kubectl get events -A` | Eventos de todos los namespaces |

## Complementos

- `kubectl get pods`: la columna `RESTARTS` indica reinicios (posible fallo de liveness).
- `kubectl logs <pod>`: para ver la causa desde la salida del contenedor.