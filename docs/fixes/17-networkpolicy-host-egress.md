# Fix 17: NetworkPolicy default-deny bloquea el egress a los sidecars del host

Con CoreDNS ya resolviendo `host.docker.internal`, los pods del namespace
`ecommerce` seguían sin poder conectar a los sidecars del host (Postgres
`37865`, Kafka `9093`). La causa era el egress restringido por las
NetworkPolicies del base.

## Error observado

- Los pods de `ecommerce` no llegaban ni a `37865` ni a `9093`, aunque CoreDNS
  respondía correctamente.
- El probe de conectividad **fallaba dentro del namespace** `ecommerce`, pero
  **funcionaba** ejecutado desde el namespace `default`.

## Por qué ocurre

Las NetworkPolicies base de los services abren egress a `0.0.0.0/0` solo en
los puertos `443/5432` (pensadas para Azure Database for PostgreSQL, que
expone `5432`). El sidecar local de Postgres usa el puerto `37865` y Kafka usa
`9093`: el default-deny de egress de esas políticas corta las conexiones a
esos puertos.

## Fix aplicado

Nuevo archivo `cluster/overlays/local/host-sidecar-egress-policy.yaml`: una
NetworkPolicy **aditiva** en el namespace `ecommerce` que abre egress al host:

- Pods seleccionados: `catalog`, `order`, `payment`, `inventory`,
  `notification`, `checkout`.
- Destino: `ipBlock 172.22.0.1/32` (gateway de `floci_az_default`, el host).
- Puertos: `37865` y `9093`.

La política se incluyó en el bloque `resources` del overlay local
(`cluster/overlays/local/kustomization.yaml`), por lo que se aplica junto con
todo el overlay.

## Cómo verificar

```bash
kubectl apply -k cluster/overlays/local
kubectl get pods -n ecommerce
```

- Los deployments levantan `1/1`.
- El flujo de checkout completa (la conexión a Postgres y a Kafka ya no se
  corta por la NetworkPolicy).