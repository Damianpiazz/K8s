# Pods

El **Pod** es la unidad mínima de despliegue (ver [11-pods.md](../arquitectura/11-pods.md)). Estos comandos gestionan y diagnostican Pods del namespace actual.

## `kubectl get pods`

Lista **todos los Pods** del namespace actual, con estado, reinicios y edad.

```text
kubectl get pods
kubectl get pods -o wide      # agrega IP del Pod y el nodo
kubectl get pods -A           # Pods de todos los namespaces
kubectl get pods -w           # vista en tiempo real
```

## `kubectl describe pod <pod_name>`

Muestra **información detallada de un Pod**: estado y eventos, condiciones (Readiness, Initialized), contenedores (imagen, requests/limits), probes, volúmenes, QoS y eventos.

```text
kubectl describe pod <pod_name>
```

> Es la primera herramienta ante un Pod que no arranca: los **eventos** al final suelen revelar la causa (imagen no descargable, `CrashLoopBackOff`, faltas de recursos, etc.).

## `kubectl logs <pod_name>`

Imprime los **logs del Pod**. En Pods multi-contenedor hay que indicar el contenedor:

```text
kubectl logs <pod_name>
kubectl logs <pod_name> -c <container_name>   # Pods multi-contenedor
kubectl logs <pod_name> -f                    # seguir (stream) los logs
```

## `kubectl exec -it <pod_name> -- <container_name> <command>`

Ejecuta **un comando dentro de un contenedor en ejecución** dentro del Pod.

```text
kubectl exec -it <pod_name> -- /bin/sh            # Pods de un contenedor
kubectl exec -it <pod_name> -c <container_name> -- /bin/sh   # multi-contenedor
```

- `-it`: modo interactivo (terminal). Ejecuta el comando indicado después de `--`.
- Es el equivalente de "entrar" al contenedor para depurar (equivalente a `docker exec`).

## `kubectl delete pod <pod_name>`

Elimina **un Pod específico**.

```text
kubectl delete pod <pod_name>
```

> Si el Pod pertenece a un Deployment/ReplicaSet, se volverá a crear inmediatamente (el controlador reconcilia el estado deseado, ver [04-kube-controller-manager.md](../arquitectura/04-kube-controller-manager.md)). Para eliminar de verdad un Pod gestionado, se borra el Deployment.

## Resumen

| Comando | Qué hace |
| --- | --- |
| `kubectl get pods` | Lista Pods del namespace actual |
| `kubectl describe pod <pod_name>` | Detalla un Pod (estado, probes, eventos) |
| `kubectl logs <pod_name> [-c <container>]` | Logs de un Pod/contenedor |
| `kubectl exec -it <pod_name> [-c <container>] -- <cmd>` | Ejecuta un comando dentro del contenedor |
| `kubectl delete pod <pod_name>` | Elimina un Pod (si está en Deployment, se recrea) |