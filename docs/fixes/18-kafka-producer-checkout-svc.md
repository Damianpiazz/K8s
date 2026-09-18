# Fix 18: Productor Kafka faltante en checkout-svc

El flujo completo (token, carrito, checkout) terminaba en `COMPLETED`, pero no
llegaba ningún evento a `order-events` ni a `payment-events`: no existía
ningún productor en el código. Se agregó `CheckoutEventPublisher` en
`checkout-svc`, activable por configuración.

## Error observado

- El flujo completo (token, carrito, checkout) terminaba `COMPLETED`.
- No llegaba ningún evento a `order-events` ni a `payment-events`.
- El consumidor `notification-svc` estaba suscripto y con particiones
  asignadas, pero los tópicos quedaban vacíos.

## Por qué ocurre

No existía **ningún** productor en el código: un grep solo encontraba
`KafkaTemplate` y `@KafkaListener` en `notification-svc`. `CheckoutService`
documentaba en su Javadoc que "en producción se emite en `order-events`", pero
la implementación local solo guardaba el record en memoria.

## Fix aplicado

Nuevo `CheckoutEventPublisher.java` en `checkout-svc`:

- Bean condicional con `@ConditionalOnProperty(checkout.events.kafka.enabled=true)`:
  **OFF por defecto**, de modo que CI y tests sin broker no fallan (mismo
  patrón que `notifications.kafka.enabled`).
- El env explícito vence la property. La publicación es NPE-safe: el payload
  se construye dentro del try, los nulls se convierten en `""` y el send es
  asíncrono con `whenComplete`, que loguea `Published <topic> ← <json>` (o un
  warn en caso de error). Nunca afecta la respuesta del checkout.
- `spring-kafka` agregado al pom de `checkout-svc`.
- Configuración:
  - `spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP:localhost:9092}`
  - `spring.kafka.properties.security.protocol=${KAFKA_SECURITY_PROTOCOL:PLAINTEXT}`
- `CheckoutService` inyecta un `ObjectProvider<CheckoutEventPublisher>` y
  publica `order-events` + `payment-events` después de registrar el record.
- `cluster/overlays/local/env-config.yaml`:
  `CHECKOUT_EVENTS_KAFKA_ENABLED=true`.
- Tests unitarios `CheckoutEventPublisherTest`: topics, payload, nulls y fallo
  de serialización.

## Cómo verificar

Realizar un checkout y luego:

```bash
kubectl logs deploy/checkout-svc
kubectl logs deploy/notification-svc
```

- Logs de `checkout-svc`: `Published order-events ← {…}`.
- Logs de `notification-svc`: `order-events ← {…}` y `payment-events ← {…}`.

Nota: `total`/`amount` salen en `0` por un quirk del total del carrito, fuera
del alcance de este fix.