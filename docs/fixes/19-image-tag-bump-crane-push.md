# Fix 19: Rebuild con el mismo tag deja pods viejos + push HTTP al registry local

Después de rebuild, push y rollout restart, el pod de `checkout-svc` seguía
corriendo el código anterior (sin logs de publish). Dos causas: el tag de
imagen no cambió (`imagePullPolicy=IfNotPresent`) y `docker push` no funciona
contra un registry local HTTP.

## Error observado

- Tras rebuild + push + rollout restart, el pod seguía con el código viejo:
  no aparecían los logs de publish de Kafka.
- `docker push` al registry local fallaba:

```text
http: server gave HTTP response to HTTPS client
```

## Por qué ocurre

1. `imagePullPolicy=IfNotPresent`: containerd usa la imagen cacheada por
   **tag**, aunque el contenido (el jar) haya cambiado. Con el mismo tag, el
   nodo no vuelve a bajar la imagen.
2. El daemon de Docker Desktop exige HTTPS y el registry local es HTTP (sin
   `insecure-registries` configurado; una actualización de Docker Desktop
   borró la configuración previa).

## Fix aplicado

1. **Bump de tag solo para `checkout-svc`** en el kustomization local:
   `newTag: 870f35a` (el tag global sigue en `870f34f` = HEAD). Se dejó un
   comentario en el kustomization explicando que **el bump de tag es
   obligatorio** para que el nodo vuelva a bajar la imagen.
2. **Push vía crane** en lugar de `docker push`: crane habla HTTP directo, sin
   tocar `daemon.json` y sin reiniciar Docker Desktop (reiniciarlo cambiaría
   el puerto del sidecar de Postgres, `37865`):

```bash
docker save -o <tmp>/x.tar <imagen>
docker run --rm -v "<tmp>:/data" gcr.io/go-containerregistry/crane push --insecure "/data/x.tar" host.docker.internal:5000/localecommercefloci01/checkout-svc:870f35a
```

- Verificar el digest con `crane digest --insecure`.
- El `crane.exe` del host **no sirve**: `host.docker.internal` resuelve a una
  IP de la LAN fuera de la VM de Docker, así que crane debe correr dentro de
  un contenedor.

## Cómo verificar

```bash
kubectl get deploy checkout-svc -o jsonpath='{.spec.template.spec.containers[0].image}'
kubectl logs deploy/checkout-svc
```

- La imagen del deployment muestra el tag `870f35a`.
- El pod arranca con el jar nuevo y publica los eventos de Kafka
  (`Published order-events ← …`).