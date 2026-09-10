# payment-svc — API REST de procesamiento de pagos

Procesa intentos de pago con una regla de aprobación simple y mantiene un
store in-memory para la demo. El stack JPA/H2/Postgres está en el classpath con
claves de config alineadas a `config-service` `payment-svc.yml` — listo para
Azure Postgres.

## Endpoints

| Método | Path | Comportamiento |
|---|---|---|
| POST | `/api/payments` | procesa: `{orderId, amount, method, cardLast4?}` → 201 + pago con `APPROVED`/`DECLINED` |
| GET | `/api/payments/{id}` | pago individual (404 si desconocido) |
| GET | `/api/payments/order/{orderId}` | todos los pagos de un pedido |
| GET | `/api/payments` | lista todos los pagos |

Regla de aprobación (demo): `amount ≤ 0` → DECLINED; `cardLast4` placeholder
que termina en `0000` → DECLINED; todo lo demás → APPROVED. Un sistema real
delegaría la decisión a un servicio de tokenización PSP (Stripe/Adyen).

## Correrlo localmente

```bash
cd services/payment-svc
mvn spring-boot:run          # arranca en :8085
```

```bash
curl -X POST localhost:8085/api/payments \
  -H 'Content-Type: application/json' \
  -d '{"orderId":1,"amount":149.48,"method":"card","cardLast4":"4242"}'
```

## Cambiar a Postgres gestionado por Azure

`k8s/base/configmap.yaml` (`payment-svc-config`) guarda los defaults del
datasource. Sobreescribí las claves `SPRING_DATASOURCE_*` según las
instrucciones comentadas (credenciales de DB desde Azure Key Vault via
external-secrets) y cambiá el `PaymentService` in-memory por su
implementación `PaymentRepository` (JPA) — el contrato REST queda idéntico.

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `payment-svc:8085` |
| `k8s/base/configmap.yaml` | defaults de datasource + URLs de config/eureka |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde api-gateway; egress DNS + peers + 5432/443 |
| `k8s/overlays/prod/` | 2 réplicas, resources más grandes (⚠ nota in-memory) |

## Wiring (one-time, por env)

Agregá `../../../services/payment-svc/k8s/overlays/prod` a la lista
`resources:` de `cluster/overlays/{env}/kustomization.yaml` — la entrada
`images:` ya está pre-listada para que el CD la tagee desde el día uno.

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (archivos de kustomization + deployment).
2. Store → Postgres de Azure (ver comentarios del configmap) + credenciales en Key Vault.