# Operaciones avanzadas

Comandos de gestión declarativa y consulta de recursos.

## `kubectl apply -f <filename>`

Aplica un archivo de configuración para **crear o actualizar** recursos.

```text
kubectl apply -f deployment.yaml
kubectl apply -f directorio/           # aplica todos los YAML del directorio
kubectl apply -f https://.../archivo.yaml   # desde una URL
```

> `apply` es la forma **declarativa** recomendada (idempotente): define el estado deseado en el manifiesto y kubectl reconcilia. Se usa en contraste con los comandos imperativos (`create`, `expose`, `scale`). El API server valida y persiste el objeto en `etcd` (ver [01-kube-apiserver.md](../arquitectura/01-kube-apiserver.md) y [10-objetos-y-recursos.md](../arquitectura/10-objetos-y-recursos.md)).

## `kubectl explain <resource>`

Obtiene **información detallada de un recurso**: su estructura de campos y tipos.

```text
kubectl explain deployment
kubectl explain deployment.spec
kubectl explain deployment.spec.template.spec.containers
```

> Es la ayuda en línea de la API: muestra cada campo, su tipo y una descripción. No requiere clúster conectado. Muy útil para escribir manifiestos correctamente.

## `kubectl get events`

Lista **eventos relacionados con los recursos** del namespace actual.

```text
kubectl get events
```

(Ampliamente cubierto en [08-salud-y-monitoreo.md](08-salud-y-monitoreo.md) — se lista aquí por ser una de las operaciones avanzadas más usadas para diagnóstico.)

## Otras operaciones útiles (avanzadas)

```text
kubectl get <recurso> -o yaml                    # salida completa del objeto en YAML
kubectl get <recurso> -o jsonpath='{.spec...}'   # extrae campos concretos
kubectl edit deployment <nombre>                 # edita el recurso en vivo
kubectl delete -f manifiesto.yaml                # borra los recursos del manifiesto
kubectl port-forward service/<svc> 8080:80       # reenvía un puerto local al Service
kubectl config use-context <context>             # cambia de contexto (ver 17-kubeconfig)
```

## Resumen

| Comando | Qué hace |
| --- | --- |
| `kubectl apply -f <archivo>` | Aplica un manifiesto (crear/actualizar, declarativo) |
| `kubectl explain <recurso>` | Documenta la estructura del recurso |
| `kubectl get events` | Lista eventos de los recursos del namespace |
| `kubectl edit <recurso> <nombre>` | Edita un recurso en vivo |
| `kubectl delete -f <archivo>` | Borra los recursos definidos en el manifiesto |