# Fix 21: El checkout se cuelga ~60 s con Kafka caído (stall de `max.block.ms`)

El checkout publicaba los eventos con `KafkaTemplate.send(...).whenComplete(...)`,
que *parece* asíncrono pero **no lo es**: `send()` bloquea el hilo del checkout
hasta obtener metadata del broker. Con Kafka caído y el default de
`max.block.ms=60000`, cada `POST /api/checkout` quedaba colgado hasta 60 s. Se
acotó `max.block.ms` a `1500` en la configuración del productor.

## Error observado

- Con el broker Kafka caído (emulador degradado), cada `POST /api/checkout`
  no respondía hasta ~60 s antes de completar.
- El log de fallo del publish (`Failed to publish ...; checkout continues`)
  aparecía recién alrededor de los 60 s, no de inmediato.
- El `whenComplete` del productor se ejecutaba, pero **después** del stall: el
  checkout ya había estado bloqueado durante todo ese tiempo.

Esto rompe el requisito documentado en el propio `application.yml`
(`requirement b: checkout never blocks on Kafka`): la publicación de eventos es
best-effort y no debe bloquear la respuesta del checkout. El fix 18 garantizó
que un broker caído **no rompa** el checkout, pero no acotó **cuánto tiempo**
podía retenerlo.

## Por qué ocurre

`KafkaTemplate.send()` es síncrono en su fase de *metadata fetch*: antes de
encolar el record hace una llamada bloqueante para resolver metadata de
particiones/líder y obtener el buffer de envío. Esa fase respeta
`max.block.ms`, cuyo valor por defecto en el productor de spring-kafka es
**60000 ms**.

Con el broker caído, esa llamada de metadata no recibe respuesta y agota el
`max.block.ms` completo antes de fallar. El uso de
`send(...).whenComplete(...)` solo hace asíncrono el **resultado** del envío,
pero la invocación de `send()` en sí sigue ocupando el hilo que la llama —el
hilo que atiende el checkout— durante todo ese bloqueo. La asincronía percibida
era, por lo tanto, una falsa sensación: el stall ocurría antes de que el
callback tuviera oportunidad de correr.

## Fix aplicado

En `services/checkout-svc/src/main/resources/application.yml`, dentro del
bloque `spring.kafka` existente, se agregó la propiedad del productor:

```yaml
spring:
  kafka:
    # ...
    producer:
      properties:
        max.block.ms: 1500
```

- Ubicación exacta: bajo `spring.kafka` → `producer` → `properties`
  (junto a la property `security.protocol` ya existente bajo
  `spring.kafka.properties`).
- Enfoque minimal y durable: con el broker caído, el publish se rinde a los
  ~1.5 s en lugar de 60 s; el checkout continúa y el camino de fallo del
  `whenComplete` registra `Failed to publish <topic> (...); checkout continues`
  (comportamiento introducido en el fix 18). Con el broker arriba, el mismo
  camino acotado aplica sin efectos adversos.
- El overlay local (`cluster/overlays/local/env-config.yaml`) no requiere
  cambios: no sobreescribe propiedades del productor, por lo que el valor del
  `application.yml` es el que rige.

Test de regresión ligero (sin contexto de Spring, sin broker), en
`services/checkout-svc/src/test/java/com/ecommerce/checkout/config/CheckoutKafkaProducerConfigTest.java`:

- `producerMaxBlockMsIsBoundedAtExactBindingPath`: carga únicamente el
  `application.yml` de main con `YamlPropertySourceLoader` y bindea
  `spring.kafka.producer.properties.max.block.ms` vía `Binder`, y asserta que
  el valor sea `"1500"` (y no el default `60000`).
- `producerMaxBlockMsReachesKafkaProducerConfig`: bindea
  `spring.kafka` a `KafkaProperties` y verifica que el valor llegue al mapa que
  consume el productor, con `containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG,
  "1500")`.

## Cómo verificar

**Estática (sin broker):** el test `CheckoutKafkaProducerConfigTest` cubre el
binding exacto y la llegada del valor al mapa del productor. En entornos sin
Maven/Java disponibles, la verificación se apoya en la lectura del
`application.yml` (valor `max.block.ms: 1500` bajo
`spring.kafka.producer.properties`) y en el propio test de regresión.

**E2E (re-aplicar overlay local):**

```bash
kubectl apply -k cluster/overlays/local
kubectl get pods -n ecommerce
kubectl logs deploy/checkout-svc
```

- **Con el broker caído:** el checkout completa en ~1.5 s y el log muestra
  `Failed to publish order-events (...); checkout continues` (no a los 60 s).
- **Con el broker arriba:** el log muestra `Published order-events ← {…}` y
  `Published payment-events ← {…}` para cada checkout.
