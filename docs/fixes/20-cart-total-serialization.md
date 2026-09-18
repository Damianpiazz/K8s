# Fix 20: El total del carrito llega a checkout como 0 (Jackson no serializa `Cart.total()`)

El checkout completaba con éxito pero con `"total":0`, y los eventos de
`order-events`/`payment-events` llevaban `amount 0`. La causa era un
anti-patrón de serialización Jackson en el modelo de `cart-svc`: el accessor
`total()` no sigue la convención Java Beans, así que Jackson lo omitía del JSON.

## Error observado

- Respuesta de checkout con total en cero, a pesar de un carrito con ítems:

```json
{"orderId":2, ..., "total":0, "status":"COMPLETED"}
```

- Los eventos publicados en `order-events`/`payment-events` llevaban `amount 0`.
- `GET /api/cart/{id}` no incluía el campo `"total"` en el JSON.

## Por qué ocurre

Jackson descubre properties **solo** por los accesores que siguen la convención
Java Beans: `getX()` o `isX()`. El método `total()` de `Cart` es un accessor de
solo lectura que **no tiene** ninguno de esos prefijos, por lo que Jackson no lo
reconoce como property y lo omite del JSON serializado.

Consecuencia en cadena:

1. `GET /api/cart/{id}` no trae el campo `"total"`.
2. `CheckoutService.fetchCartTotal` recibe `total = null`.
3. El código cae al fallback y usa `BigDecimal.ZERO` como total del pedido.

Es un caso clásico del anti-patrón "método accessor que no es getter": el
método **sí** devuelve el valor calculado, pero el serializador no lo invoca
porque no matchea el patrón de property.

## Fix aplicado

En `services/cart-svc/src/main/java/com/ecommerce/cart/model/Cart.java`:

- Se añadió `@JsonProperty("total")` sobre el accessor `total()`
  (import `com.fasterxml.jackson.annotation.JsonProperty`), **manteniendo el
  nombre del método** y sin tocar la API Java interna del modelo.

```java
@JsonProperty("total")
public BigDecimal total() { ... }
```

- Enfoque minimal: la anotación fuerza la property `"total"` en el JSON sin
  cambiar los call-sites.
- Alternativa descartada: renombrar el método a `getTotal()`. Habría roto la
  API interna de `Cart` y actualizado todos los call-sites en `CartService`.

Test de regresión: `services/cart-svc/src/test/java/com/ecommerce/cart/model/CartSerializationTest.java`
— un test que serializa un `Cart` con dos ítems de a `12.34` (cantidad 2) y
asserta que el JSON contenga `"total"` con el valor `24.68`.

## Cómo verificar

Imagen con el fix (misma ruta crane que el fix 19, solo `cart-svc` re-tageada a
`870f35b`; el resto del overlay sigue en `870f34f`):

```bash
docker build -t acr.azurecr.io/cart-svc:870f35b .
docker run --rm -v "<tmp>:/data" gcr.io/go-containerregistry/crane push --insecure "<tmp>/x.tar" host.docker.internal:5000/localecommercefloci01/cart-svc:870f35b
kubectl apply -k cluster/overlays/local
kubectl get pods -n ecommerce
kubectl get deploy cart-svc -o jsonpath='{.spec.template.spec.containers[0].image}'
```

- El pod `cart-svc` queda `1/1` con la imagen `.../cart-svc:870f35b`.

**Gate E2E pendiente**: la validación funcional (curl a `GET /api/cart/{id}`
viendo `"total"` > 0 y checkout con el total real) **no fue ejecutable en el
momento del fix** por degradación pre-existente del cluster: 10 de 18
deployments en `CrashLoopBackOff` (exit `137`), nodo único saturado por
memoria (overcommit 138%). Es un incidente ambiental del nodo local, **no una
regresión de este fix**.

Cuando el cluster recupere, verificar:

```bash
curl -s http://<gateway>/api/cart/<id>
```

- El JSON incluye `"total"` con el valor de la suma de líneas (> 0).
- El checkout responde con `"total"` real (no `0`) y los eventos de
  `order-events`/`payment-events` llevan el `amount` correspondiente.