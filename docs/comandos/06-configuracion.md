# Configuraciones: ConfigMaps y Secrets

Los **ConfigMaps** guardan configuración no sensible, y los **Secrets** guardan datos sensibles (credenciales, tokens, claves). Ambos se inyectan a los Pods como variables de entorno o volúmenes (ver [10-objetos-y-recursos.md](../arquitectura/10-objetos-y-recursos.md)).

## `kubectl create configmap <configmap_name> --from-literal=key=value`

Crea un ConfigMap a partir de un **par clave-valor literal**.

```text
kubectl create configmap <configmap_name> --from-literal=key=value
```

## `kubectl create configmap <configmap_name> --from-file=path/to/file`

Crea un ConfigMap a partir de un **archivo** (el nombre del archivo se usa como clave).

```text
kubectl create configmap <configmap_name> --from-file=path/to/file
```

También desde un directorio: `--from-file=directorio/`, o múltiples `--from-file`/`--from-literal` en un solo comando.

## `kubectl create secret generic <secret_name> --from-literal=key=value`

Crea un **Secret genérico** a partir de un par clave-valor literal.

```text
kubectl create secret generic <secret_name> --from-literal=key=value
```

## `kubectl create secret generic <secret_name> --from-file=path/to/file`

Crea un Secret genérico a partir de un **archivo**.

```text
kubectl create secret generic <secret_name> --from-file=path/to/file
```

## `kubectl get configmaps` / `kubectl get secrets`

Lista todos los ConfigMaps/Secrets del namespace actual.

```text
kubectl get configmaps
kubectl get cm                # abreviatura

kubectl get secrets
kubectl get secret            # singular
```

## `kubectl describe configmap <configmap_name>` / `kubectl describe secret <secret_name>`

Muestra información detallada del ConfigMap/Secret.

```text
kubectl describe configmap <configmap_name>
kubectl describe secret <secret_name>
```

> En los Secrets, `describe` **no muestra los valores** (por seguridad): solo muestra las claves y sus tamaños. Para ver los valores codificados hay que leer el objeto completo con `kubectl get secret <name> -o yaml` y decodificar el base64.

## Resumen

| Comando | Qué hace |
| --- | --- |
| `kubectl create configmap <cm> --from-literal=k=v` | ConfigMap desde pares clave-valor |
| `kubectl create configmap <cm> --from-file=ruta/archivo` | ConfigMap desde archivo(s) |
| `kubectl create secret generic <sec> --from-literal=k=v` | Secret desde pares clave-valor |
| `kubectl create secret generic <sec> --from-file=ruta/archivo` | Secret desde archivo(s) |
| `kubectl get configmaps` / `kubectl get secrets` | Lista ConfigMaps / Secrets |
| `kubectl describe configmap <cm>` / `kubectl describe secret <sec>` | Detalla (los valores de Secret no se muestran) |

## Consideración de seguridad

Los **Secrets** se guardan en `etcd`. Aunque estén codificados en base64, **no son cifrados** salvo que se habilite el cifrado en reposo (encryption at rest) del API server. Limitar su acceso con RBAC (ver [01-kube-apiserver.md](../arquitectura/01-kube-apiserver.md)).