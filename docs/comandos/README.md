# Comandos de Kubernetes (kubectl)

## Introducción

`kubectl` es el cliente de línea de comandos que se comunica con el **kube-apiserver** mediante peticiones REST sobre TLS (ver [01-kube-apiserver.md](../arquitectura/01-kube-apiserver.md)). Toda la gestión del clúster se hace a través de él: objetos (Pods, Deployments, Services), configuración, almacenamiento y diagnóstico.

## Estructura básica

```text
kubectl [comando] [tipo de recurso] [nombre] [flags]
```

- **`comando`**: acción, p. ej. `get`, `describe`, `create`, `apply`, `delete`, `logs`, `exec`, `scale`, `rollout`.
- **`tipo de recurso`**: objeto, p. ej. `pods`, `deployments`, `services`, `configmaps`, `secrets`, `pv`, `pvc`, `namespaces`, `nodes`, `events`.
- **`flags`**: opciones, p. ej. `-n <namespace>`, `--from-literal`, `--replicas`, `-f <archivo>`.

> **Forma singular/plural**: `kubectl get pod`, `kubectl get pods` y `kubectl get po` son equivalentes; kubectl acepta el tipo en singular, plural y abreviaturas comunes (`po`, `svc`, `deploy`, `cm`, `secret`, `ns`, `no`).

## Índice de documentos

| Documento | Categoría | Contenido |
| --- | --- | --- |
| [01-cluster-y-nodos.md](01-cluster-y-nodos.md) | Gestión del clúster | info, nodos, estado del clúster |
| [02-namespaces.md](02-namespaces.md) | Namespaces | listar, describir, crear, eliminar |
| [03-pods.md](03-pods.md) | Pods | listar, describir, logs, exec, eliminar |
| [04-workloads.md](04-workloads.md) | Deployments | describe, scale, rollout status/history/undo |
| [05-services-y-red.md](05-services-y-red.md) | Services | listar, describir, exponer, eliminar |
| [06-configuracion.md](06-configuracion.md) | ConfigMaps y Secrets | crear, listar, describir |
| [07-almacenamiento.md](07-almacenamiento.md) | Volúmenes | PV y PVC |
| [08-salud-y-monitoreo.md](08-salud-y-monitoreo.md) | Health checks | probes, eventos, diagnóstico |
| [09-operaciones-avanzadas.md](09-operaciones-avanzadas.md) | Operaciones | apply, explain, get events |

## Consejos generales

- **Namespace por defecto**: sin el flag `-n`, kubectl opera sobre el namespace configurado en el contexto (suele ser `default`). Para ver todas: `kubectl get pods -A` (todas las namespaces).
- **Salida `-o wide`**: agrega columnas (IPs de nodo, imágenes). `-o yaml`/`-o json` da la definición completa del objeto.
- **Watch `-w`**: `kubectl get pods -w` mantiene la vista actualizándose en tiempo real.

## Clave de convenciones en estos documentos

- `<nombre>` entre ángulos indica un argumento a reemplazar (p. ej. `<pod-name>`).
- `pod_name`, `deployment_name`, `configmap_name` denotan el nombre del recurso correspondiente.
- `container_name` denota el nombre del contenedor **dentro del Pod** (los Pods multi-contenedor requieren indicarlo).