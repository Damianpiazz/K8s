# security/network-policies — red de seguridad a nivel de namespace

## Qué hay acá

| Archivo | Qué hace |
|---|---|
| `default-deny-all.yaml` | Default-deny a nivel de namespace (ingress + egress) para `ecommerce`: todo pod sin NetworkPolicy por servicio es denegado en ambas direcciones. |
| `kustomization.yaml` | Agrega lo de arriba para el árbol de `security/`. |

## Qué NO está acá intencionalmente — `allow-kube-system-dns.yaml` (OMITIDO)

El brief original proponía una regla de egress a nivel de ecommerce hacia
`kube-dns` (53/tcp+udp) "para que las políticas existentes por servicio sigan
funcionando". **Verificado como redundante — cada NetworkPolicy por servicio ya
otorga egress de DNS.** Ejemplo
(`services/catalog-svc/k8s/base/networkpolicy.yaml`):

```yaml
egress:
  - to:
      - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } }
        podSelector: { matchLabels: { k8s-app: kube-dns } }
      ports:
        - { port: 53, protocol: UDP }
        - { port: 53, protocol: TCP }
```

y los servicios están construidos con **exactamente el mismo layout**
(services/README.md), así que agregar un `allow-kube-system-dns` separado solo
duplicaría esas reglas. La red de seguridad default-deny no rompe el DNS de los
servicios con política por servicio (sus reglas de egress siguen aplicando via
semántica de unión).

**Si un workload futuro en `ecommerce` necesita DNS pero no tiene política por
servicio**, debe definir su propio egress (o se puede agregar una regla
namespace-wide explícita `allow-kube-system-dns` acá entonces). Esto coincide
con la filosofía del repo de "un servicio = una NetworkPolicy".

## Interacción con las políticas existentes por servicio

- Semántica de unión: los pods cubiertos mantienen sus allow-lists completos
  por servicio.
- Pods no cubiertos: aislamiento total — sin ingress, sin egress (incluido
  DNS, así que ni siquiera pueden resolver peers hasta que alguien escriba su
  política).

## Caveats (preexistentes, no introducidos por este archivo)

1. **Probes de kubelet** — las probes HTTP de readiness/liveness se originan
   en el nodo; una política de ingress estricta PUEDE bloquearlas (limitación
   documentada de Kubernetes). Las políticas por servicio ya tienen esta forma,
   así que la red de seguridad no la empeora. Si las probes se rompen en tu
   CNI, permití el CIDR del nodo explícitamente en el ingress por servicio.
2. **Tráfico service-to-service in-cluster** — las políticas por servicio
   permiten pares de ecommerce via `namespaceSelector: ecommerce` +
   `app.kubernetes.io/part-of: ecommerce-platform` (ejemplo de catalog-svc).
   Los servicios NUEVOS deben replicar ese egress o no podrán alcanzar sus
   peers.

## Verificado contra

- `services/catalog-svc/k8s/base/networkpolicy.yaml` (egress de DNS + egress de peers + ingress de observability)
- `services/README.md` (layout uniforme en los 17 servicios)
- `cluster/base/kyverno/policies/require-labels.yaml` (exclusiones de namespaces — namespaces de charts no enforceados)