# Fix 15: CoreDNS no resuelve host.docker.internal dentro de los pods

Los pods del clúster k3s local no resolvían `host.docker.internal`, necesario
para alcanzar los sidecars publicados en el host (Postgres, Kafka). CoreDNS no
conoce ese nombre por defecto; se registró en `NodeHosts` del ConfigMap
`coredns`.

## Error observado

- `nslookup host.docker.internal` dentro de un pod devolvía `NXDOMAIN`.
- Los pods no alcanzaban los sidecars del host pese a que el nombre existe en
  la red de Docker.
- Solo el nodo k3s (que corre como contenedor) resolvía el nombre: la
  resolución fallaba dentro de los pods.

## Por qué ocurre

CoreDNS no conoce `host.docker.internal`: es un nombre que Docker inyecta en
sus redes, no un registro DNS del clúster. El Corefile del clúster ya usa el
plugin `hosts` con `hosts /etc/coredns/NodeHosts`, así que el punto de
extensión natural es ese archivo.

Intento fallido documentado: un ConfigMap `coredns-custom` con un segundo
plugin `hosts` **rompe CoreDNS** con
`plugin/hosts: this plugin can only be used once per Server Block` y el pod de
CoreDNS entra en `CrashLoopBackOff`.

## Fix aplicado

Parchear el ConfigMap `coredns` del namespace `kube-system` agregando la
entrada en `data.NodeHosts`:

```text
172.22.0.1 host.docker.internal
```

donde `172.22.0.1` es el gateway de la red `floci_az_default` (la IP del host
vista desde los contenedores).

- Se aplicó desde un archivo YAML temporal: los patches JSON multi-línea se
  rompen en PowerShell, así que el patch en línea no era viable.
- Tras el cambio, CoreDNS recarga la configuración y los pods resuelven el
  nombre.

Caveat: k3s puede reconciliar `NodeHosts` (regenerarlo desde el estado del
clúster). Si la resolución vuelve a fallar, hay que repetir el parche.

## Cómo verificar

```bash
kubectl run probe --image=busybox -- nslookup host.docker.internal
```

El `nslookup` debe devolver `172.22.0.1`. Ejecutando dentro del pod probe:

```bash
nc host.docker.internal 37865
```

Debe responder (el sidecar de Postgres escucha en ese puerto).