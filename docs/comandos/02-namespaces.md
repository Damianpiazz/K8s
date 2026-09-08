# Namespaces

Los **namespaces** agrupan lógicamente los objetos del clúster, permitiendo aislar equipos, aplicaciones o entornos. Ver concepto en [10-objetos-y-recursos.md](../arquitectura/10-objetos-y-recursos.md).

## `kubectl get namespaces`

Lista **todos los namespaces** del clúster.

```text
kubectl get namespaces
kubectl get ns                      # abreviatura
```

## `kubectl describe namespace <namespace_name>`

Muestra **información detallada de un namespace**: quotas de recursos (ResourceQuota) y límites (LimitRange) definidos en él, además de su estado.

```text
kubectl describe namespace <namespace_name>
```

## `kubectl create namespace <namespace_name>`

Crea **un nuevo namespace**.

```text
kubectl create namespace <namespace_name>
```

## `kubectl delete namespace <namespace_name>`

Elimina un namespace y **todo lo que contiene** (Pods, Services, ConfigMaps, Secrets, etc.).

```text
kubectl delete namespace <namespace_name>
```

> ⚠️ Es una operación destructiva masiva: borra todos los recursos del namespace. Usar con cuidado.

## Trabajar con un namespace específico

- `-n <namespace>`: operar sobre un namespace concreto.
- `-A` / `--all-namespaces`: operar sobre todos a la vez (p. ej. `kubectl get pods -A`).
- Cambiar el namespace por defecto del contexto: `kubectl config set-context --current --namespace=<ns>` (ver [17-kubeconfig.md](../arquitectura/17-kubeconfig.md)).

## Resumen

| Comando | Qué hace |
| --- | --- |
| `kubectl get namespaces` | Lista los namespaces |
| `kubectl describe namespace <ns>` | Detalla un namespace (quotas, límites, estado) |
| `kubectl create namespace <ns>` | Crea un namespace |
| `kubectl delete namespace <ns>` | Elimina un namespace y su contenido |
| `kubectl get <recurso> -n <ns>` | Opera sobre un namespace concreto |
| `kubectl get <recurso> -A` | Opera sobre todos los namespaces |