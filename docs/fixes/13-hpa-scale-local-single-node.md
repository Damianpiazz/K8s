# Fix 13: HPA satura el nodo único local (réplicas en Pending)

Tras recuperar el clúster, los HPA del base habían escalado los deployments y
el nodo único del entorno local quedó saturado: muchas réplicas quedaron en
estado `Pending` sin poder schedulerse.

## Error observado

- `kubectl get pods -n ecommerce` mostraba múltiples réplicas en `Pending`
  para cada deployment.
- El clúster local tiene un solo nodo, incapaz de alojar todas las réplicas
  que los HPA habían creado.

## Por qué ocurre

Los HPA definidos en el base escalan por CPU. En el entorno local, con un solo
nodo, ese escalado satura el scheduling de Kubernetes: no hay nodos
disponibles para las réplicas adicionales, que quedan en `Pending` a la espera
de recursos.

## Fix aplicado

Fix **runtime** (se documenta como tal, no como cambio durable de
infraestructura):

```bash
kubectl delete hpa -n ecommerce --all
kubectl scale deploy -n ecommerce --all --replicas=1
```

- Eliminar los HPA impide que vuelvan a escalar por CPU en caliente.
- Escalar cada deployment a 1 réplica libera el nodo y permite que las
  réplicas existentes schedulen.

Caveat: el overlay local **recrea los HPA en cada `apply`** (vuelven con la
configuración del base). El fix durable (deshabilitar o ajustar los HPA en el
overlay local) queda como TODO pendiente.

## Cómo verificar

```bash
kubectl get pods -n ecommerce
```

Ningún pod debe quedar en `Pending`; todos los deployments quedan con una
réplica en `Running`.