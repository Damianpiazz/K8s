# Fix 05: `invalid accessor method in record` en bff-web (HomeResponse)

El job `Test bff-web` falló al compilar `HomeResponse.java` porque el
componente `degraded` del record genera automáticamente un accessor
`degraded()`, que colisiona con el método factory estático
`HomeResponse.degraded()`. Java no permite un método estático con la misma
firma que el accessor autogenerado.

## Error observado

Línea exacta en `logs_github/4_Test bff-web.txt` (línea 1257):

```text
2026-09-08T03:32:07.2931513Z [ERROR] /home/runner/work/K8s/K8s/services/bff-web/src/main/java/com/ecommerce/bff/model/HomeResponse.java:[15,32] invalid accessor method in record com.ecommerce.bff.model.HomeResponse
```

## Por qué ocurre

El record declara el componente booleano `degraded`:

```java
public record HomeResponse(List<ProductSummary> products, ProductSummary hero, boolean degraded) { ... }
```

El compilador genera el accessor `degraded()` automáticamente. El código
además define un factory estático con la misma firma:

```java
public static HomeResponse degraded() { return new HomeResponse(List.of(), null, true); }
```

En un record, declarar un método (aunque sea estático) con la misma firma que
un accessor autogenerado es ilegal → `invalid accessor method in record`.

## Fix aplicado

1. `services/bff-web/src/main/java/com/ecommerce/bff/model/HomeResponse.java`:
   el factory se renombró a `degradedResponse()` (línea 15):
   ```java
   public static HomeResponse degradedResponse() {
       return new HomeResponse(List.of(), null, true);
   }
   ```
2. `services/bff-web/src/main/java/com/ecommerce/bff/service/BffService.java`
   (línea 54, único call site del factory):
   `HomeResponse.degraded()` → `HomeResponse.degradedResponse()`.

Se verificó con grep en todo el árbol `services/bff-web/` que no queda ningún
otro call site de `HomeResponse.degraded()` ni usos ambiguos de `.degraded()`:
el único uso era el de `BffService.home()` (el accessor de instancia
`degraded()` del record no se usa en ningún otro lado).

## Cómo verificar

1. Grep: `grep -rn "degraded()" services/bff-web/src` debe mostrar únicamente
   `degradedResponse()` y el accessor del record (definiciones/usos
   coherentes, sin llamadas al factory viejo).
2. Re-ejecución de CI: el job `Test bff-web` debe compilar y pasar los tests
   (`BUILD SUCCESS`). No hay Maven/Java en el entorno local; esta es la
   verificación definitiva.

## Siguiente paso

Ver [README.md](README.md) para el contexto global.