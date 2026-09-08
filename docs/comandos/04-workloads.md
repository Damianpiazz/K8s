# Deployments y rollouts

El **Deployment** es el workload principal para aplicaciones stateless (ver [12-workloads.md](../arquitectura/12-workloads.md)). Estos comandos gestionan su estado, escalado y actualizaciones.

## `kubectl get deployments`

Lista **todos los Deployments** del namespace actual, con réplicas deseadas/actuales/disponibles y la estrategia de rollout.

```text
kubectl get deployments
kubectl get deploy                      # abreviatura
kubectl get deployments -o wide
```

## `kubectl describe deployment <deployment_name>`

Muestra **información detallada de un Deployment**: réplicas, selector, estrategia de actualización, contenedores (imagen, recursos, probes) y el historial de eventos de rollout.

```text
kubectl describe deployment <deployment_name>
```

## `kubectl scale deployment <deployment_name> --replicas=3`

Escala el número de réplicas de un Deployment a **3**.

```text
kubectl scale deployment <deployment_name> --replicas=3
```

> El ReplicaSet subyacente ajusta los Pods para alcanzar el estado deseado (ver [04-kube-controller-manager.md](../arquitectura/04-kube-controller-manager.md)).

## `kubectl rollout status deployment <deployment_name>`

Comprueba el **estado de un rollout** en curso.

```text
kubectl rollout status deployment <deployment_name>
```

Devuelve algo como `deployment "app" successfully rolled out` cuando termina, o `Waiting for deployment ... rollout to finish` mientras tanto.

## `kubectl rollout history deployment <deployment_name>`

Consulta el **historial de revisiones** (cada cambio de la plantilla genera una revisión).

```text
kubectl rollout history deployment <deployment_name>
```

## `kubectl rollout undo deployment <deployment_name>`

Hace **rollback a la revisión anterior**.

```text
kubectl rollout undo deployment <deployment_name>
```

Para volver a una revisión concreta del historial:

```text
kubectl rollout undo deployment <deployment_name> --to-revision=<número>
```

## Resumen

| Comando | Qué hace |
| --- | --- |
| `kubectl get deployments` | Lista Deployments del namespace |
| `kubectl describe deployment <deploy>` | Detalla un Deployment (estrategia, eventos) |
| `kubectl scale deployment <deploy> --replicas=3` | Cambia el número de réplicas |
| `kubectl rollout status deployment <deploy>` | Estado del rollout en curso |
| `kubectl rollout history deployment <deploy>` | Historial de revisiones |
| `kubectl rollout undo deployment <deploy> [--to-revision=N]` | Rollback a revisión anterior (o a una específica) |