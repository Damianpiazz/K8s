# Fix 02: `No spring.config.import property has been defined` en 10 jobs de test

10 jobs `Test <service>` fallaron al arrancar el contexto de Spring en los
tests: `APPLICATION FAILED TO START`. El origen es que el `application.yml` de
`src/test/resources/` **sombrea** al de `src/main/resources/` en el classpath
de Maven, y la propiedad `spring.config.import` (que vive en el archivo de
main) nunca llega al entorno de test.

Servicios afectados: `search-svc`, `catalog-svc`, `returns-svc`, `cart-svc`,
`notification-svc`, `checkout-svc`, `recommendation-svc`, `shipping-svc`,
`order-svc`, `payment-svc`.

## Error observado

Líneas exactas en `logs_github/0_Test search-svc.txt` (líneas 1370-1461; el
mismo patrón en los otros 9 logs):

```text
2026-09-08T03:32:28.8067968Z APPLICATION FAILED TO START
...
2026-09-08T03:32:28.8070211Z No spring.config.import property has been defined
...
2026-09-08T03:32:28.8279983Z Caused by: org.springframework.cloud.commons.ConfigDataMissingEnvironmentPostProcessor$ImportException: No spring.config.import set
2026-09-08T03:32:28.8282054Z 	at org.springframework.cloud.commons.ConfigDataMissingEnvironmentPostProcessor.postProcessEnvironment(ConfigDataMissingEnvironmentPostProcessor.java:82)
```

Logs con el mismo fallo: `0_Test`, `9_Test`, `13_Test`, `16_Test`, `19_Test`,
`23_Test`, `30_Test`, `32_Test`, `36_Test` y `6_Test` (search, order, catalog,
returns, cart, notification, checkout, recommendation, payment y shipping,
respectivamente).

## Por qué ocurre

1. Todos estos servicios usan Spring Cloud Config en runtime. Su
   `src/main/resources/application.yml` declara el import del config server:
   `spring.config.import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}` (verificado en los 10 servicios).
2. En el classpath de Maven, `src/test/resources/` aparece **antes** que
   `src/main/resources/`. Spring Boot carga **solo el primer**
   `classpath:/application.yml` que encuentra en esa ubicación.
3. El `application.yml` de test (que contiene únicamente la config hermética
   `eureka.client.enabled: false` y similares) es el que se carga; el de main
   queda completamente oculto y su `spring.config.import` nunca se ve.
4. El postprocesador `ConfigDataMissingEnvironmentPostProcessor` (de
   spring-cloud-commons) detecta `spring-cloud-starter-config` en el
   classpath pero ninguna propiedad `spring.config.import`, y aborta el
   arranque con `ImportException`. (El prefijo `optional:` del import solo
   cubre que el config server no esté disponible; no cubre que la propiedad
   esté ausente del entorno.)

Los tests no deben depender de un config server externo. Por eso el fix es
deshabilitar el import-check **solo en la config de test**, sin tocar el
`application.yml` de runtime.

## Fix aplicado

En **cada uno** de los 10 `services/<svc>/src/test/resources/application.yml`
se agregó (fusionando con el bloque `spring:` existente cuando lo hay:
`catalog-svc`, `order-svc` y `payment-svc` ya tenían `spring.datasource`):

```yaml
spring:
  cloud:
    config:
      import-check:
        enabled: false
```

- `services/search-svc/src/test/resources/application.yml`
- `services/catalog-svc/src/test/resources/application.yml`
- `services/returns-svc/src/test/resources/application.yml`
- `services/cart-svc/src/test/resources/application.yml`
- `services/notification-svc/src/test/resources/application.yml`
- `services/checkout-svc/src/test/resources/application.yml`
- `services/recommendation-svc/src/test/resources/application.yml`
- `services/shipping-svc/src/test/resources/application.yml`
- `services/order-svc/src/test/resources/application.yml`
- `services/payment-svc/src/test/resources/application.yml`

**No se tocó** ningún `src/main/resources/application.yml` (el runtime sigue
importando el config server) ni los test yml de `api-gateway`, `bff-web`,
`analytics-svc`, `inventory-svc`, `config-service`, `discovery-service` y
`auth` (esos servicios no sufren este fallo).

## Cómo verificar

1. `grep -rn "spring.config.import" services/*/src/test/resources/` debe
   mostrar únicamente los bloques `import-check:` con
   `enabled: false` bajo `spring.cloud.config` (y **no** el comportamiento
   previo de shadowing).
2. Parseo YAML: todos los archivos editados se parsean con `yaml.safe_load`
   (`YAML OK`).
3. Re-ejecución de CI (validación final, requiere Maven/Java en el runner):
   los 10 jobs `Test <service>` deben llegar a `BUILD SUCCESS`.

## Siguiente paso

Ver [README.md](README.md) para el contexto global. Maven y Java no están
instalados en el entorno local, por lo que la validación de runtime queda para
la próxima corrida de CI.