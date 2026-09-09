# Fix 04: `HttpStatusCode cannot be converted to HttpStatus` en api-gateway

El job `Test api-gateway` falló al compilar con un error de tipos en
`RequestLoggingFilter.java`: una variable declarada como
`org.springframework.http.HttpStatus` recibe el resultado de
`ServerHttpResponse.getStatusCode()`, que desde Spring Framework 6 /
Spring Boot 3 devuelve `org.springframework.http.HttpStatusCode`.

## Error observado

Línea exacta en `logs_github/15_Test api-gateway.txt` (línea 1307):

```text
2026-09-08T03:31:59.6949914Z [ERROR] /home/runner/work/K8s/K8s/services/api-gateway/src/main/java/com/ecommerce/gateway/filter/RequestLoggingFilter.java:[35,55] incompatible types: org.springframework.http.HttpStatusCode cannot be converted to org.springframework.http.HttpStatus
```

## Por qué ocurre

En Spring Framework 6 (base de Spring Boot 3), la firma de
`ServerHttpResponse.getStatusCode()` cambió de `HttpStatus` a
`HttpStatusCode` (interfaz implementada por `HttpStatus`). Asignar el valor de
retorno a una variable `HttpStatus` ya no compila; hay que usar la interfaz
`HttpStatusCode` (o `HttpStatusCode.valueOf(...)` si se necesitara convertir).

El código roto (línea 35 de
`services/api-gateway/src/main/java/com/ecommerce/gateway/filter/RequestLoggingFilter.java`):

```java
HttpStatus status = response.getStatusCode();   // error: HttpStatusCode no se convierte a HttpStatus
```

## Fix aplicado

En `services/api-gateway/src/main/java/com/ecommerce/gateway/filter/RequestLoggingFilter.java`:

1. El import `org.springframework.http.HttpStatus` se reemplazó por
   `org.springframework.http.HttpStatusCode` (era la única referencia a
   `HttpStatus` en el archivo; no hay usos del tipo `HttpStatus.OK` que
   conservar).
2. La declaración de línea 35 pasó a:
   ```java
   HttpStatusCode status = response.getStatusCode();
   ```
3. `status.value()` (línea 39) sigue funcionando: `value()` está definido en
   la interfaz `HttpStatusCode`.

No se modificó ningún otro archivo de api-gateway.

## Cómo verificar

1. Lectura del archivo: confirmar que el import sea
   `org.springframework.http.HttpStatusCode` y que no quede ninguna
   referencia colgante a `HttpStatus`.
2. Re-ejecución de CI: el job `Test api-gateway` debe compilar
   (`BUILD SUCCESS`). No hay Maven/Java en el entorno local, por lo que esta
   es la verificación definitiva.

## Siguiente paso

Ver [README.md](README.md) para el contexto global.