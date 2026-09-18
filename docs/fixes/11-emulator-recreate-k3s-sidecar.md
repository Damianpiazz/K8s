# Fix 11: Emulador recreado deja el clúster k3s detenido (el sidecar no se borra)

El emulador local Floci-AZ (equivalente local de AKS) detiene el contenedor
sidecar que ejecuta el clúster k3s cuando el medio se recrea. Tras un
`docker compose up -d floci-az`, kubectl dejó de responder y el clúster quedó
detenido pero intacto: basta con volver a arrancar el contenedor para
recuperar el mismo clúster.

## Error observado

Tras recrear el emulador:

- kubectl dejó de responder con `Unable to connect`: el kubeconfig
  `floci-ecommerce` no podía contactar al API server.
- El sidecar de k3s aparecía en estado `Exited(2)` en la lista de
  contenedores.

El clúster no se perdió: el contenedor aparecía detenido, no borrado.

## Por qué ocurre

Floci-AZ (emulador de AKS) detiene los sidecar de contenedores k3s al
recrearse el medio: un `docker stop` limpio que envía SIGTERM al proceso (a
las 20:33 en la sesión de recuperación). El contenedor **no** se borra, por lo
que su capa de datos (kine, el backend de almacenamiento de k3s) y su
configuración (CA, `registries.yaml`) sobreviven a la recreación.

## Fix aplicado

Arrancar el contenedor del clúster sin recrearlo:

```bash
docker start floci-az-aks-d1d0618a
```

Esto restaura el **mismo** clúster:

- Misma CA (no invalida el kubeconfig).
- Mismo nodo (`74d10e7e9d88`).
- Misma versión (`v1.34.1+k3s1`).

El kubeconfig `~/.kube/floci-ecommerce.yaml` sigue siendo válido y los
deployments y pods previos sobreviven: sus datos no dependen del ciclo de vida
del contenedor.

## Cómo verificar

```bash
kubectl get nodes
kubectl get pods -n ecommerce
```

- `kubectl get nodes` devuelve el nodo en estado `Ready`.
- `kubectl get pods -n ecommerce` devuelve los pods anteriores en estado
  `Running` (o en el estado en que quedaron antes de la detención).