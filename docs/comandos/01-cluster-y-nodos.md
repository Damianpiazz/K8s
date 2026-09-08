# Gestión del clúster y nodos

Comandos de nivel superior para observar el clúster y sus nodos.

## `kubectl cluster-info`

Muestra la **información del clúster**: la URL del API server, el endpoint de CoreDNS y otros servicios del plano de control.

```text
kubectl cluster-info
```

| Comando | Qué hace |
| --- | --- |
| `kubectl cluster-info` | Muestra la información resumida del clúster (URL del API server, etc.) |
| `kubectl cluster-info dump` | Vuelca el estado completo del clúster (diagnóstico en profundidad) |

## `kubectl get nodes`

Lista **todos los nodos** del clúster, con su estado, roles, versiones y edad.

```text
kubectl get nodes
kubectl get nodes -o wide    # agrega IPs y versión del SO
```

## `kubectl describe node <node_name>`

Muestra **información detallada de un nodo específico**: capacidad de recursos (CPU, memoria), condiciones, taints, pods que aloja y eventos.

```text
kubectl describe node <node_name>
```

| Campo importante | Qué indica |
| --- | --- |
| `Capacity` / `Allocatable` | Recursos totales vs. asignables del nodo |
| `Conditions` | ¿Listo? Presión de disco/memoria, red |
| `Taints` | Qué Pods NO se programan aquí por defecto (ver [03-kube-scheduler.md](../arquitectura/03-kube-scheduler.md)) |
| `Non-terminated Pods` | Pods que están corriendo en el nodo |

## Resumen

| Comando | Qué hace |
| --- | --- |
| `kubectl cluster-info` | Información general del clúster |
| `kubectl get nodes` | Lista los nodos |
| `kubectl describe node <node_name>` | Detalla un nodo (recursos, condiciones, taints, Pods) |

## Concepto relacionado

Las condiciones de los nodos las actualiza el **Node controller** del `kube-controller-manager` (en nube, vía `cloud-controller-manager`) — ver [04-kube-controller-manager.md](../arquitectura/04-kube-controller-manager.md) y [05-cloud-controller-manager.md](../arquitectura/05-cloud-controller-manager.md).