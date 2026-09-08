# Services y red

El **Service** provee IP estable + DNS + balanceo L4 para un conjunto de Pods (ver [13-services.md](../arquitectura/13-services.md)).

## `kubectl get services`

Lista **todos los Services** del namespace actual, con su ClusterIP, tipo y puertos.

```text
kubectl get services
kubectl get svc                 # abreviatura
kubectl get services -o wide    # agrega selector y EndpointSlices
```

## `kubectl describe service <service_name>`

Muestra **información detallada de un Service**: tipo (ClusterIP/NodePort/LoadBalancer/ExternalName), selector, puertos, Endpoints (IPs de Pods backend reales) y eventos.

```text
kubectl describe service <service_name>
```

> La sección **Endpoints** revela si el Service encuentra Pods backend (selector bien configurado). Si está vacía o con IPs viejas, hay un problema de labels/selector.

## `kubectl expose deployment <deployment_name> --type=LoadBalancer --port=80`

Expone un Deployment como un **Service de tipo LoadBalancer** en el puerto 80. Es una forma imperativa y rápida de crear un Service a partir de un Deployment existente.

```text
kubectl expose deployment <deployment_name> --type=LoadBalancer --port=80
```

Otros tipos:

```text
kubectl expose deployment <deployment_name> --type=ClusterIP --port=80
kubectl expose deployment <deployment_name> --type=NodePort --port=80
```

> Es un atajo para generar el Service; en producción suele preferirse declarar el Service en un manifiesto y aplicarlo con `kubectl apply` (ver [09-operaciones-avanzadas.md](09-operaciones-avanzadas.md)).

## `kubectl delete service <service_name>`

Elimina **un Service específico**. No borra los Pods backend.

```text
kubectl delete service <service_name>
```

> Si el Service era `LoadBalancer`, en la nube también se libera el balanceador asociado (vía `cloud-controller-manager`, ver [05-cloud-controller-manager.md](../arquitectura/05-cloud-controller-manager.md)).

## Resumen

| Comando | Qué hace |
| --- | --- |
| `kubectl get services` | Lista Services del namespace |
| `kubectl describe service <svc>` | Detalla un Service (selector, Endpoints, tipo) |
| `kubectl expose deployment <deploy> --type=LoadBalancer --port=80` | Crea un Service a partir de un Deployment |
| `kubectl delete service <svc>` | Elimina un Service |