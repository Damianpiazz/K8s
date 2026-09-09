# Fix 03: POM no parseable por `&` sin escapar en `<description>` (analytics-svc, inventory-svc)

Los jobs `Test analytics-svc` y `Test inventory-svc` fallaron antes de
compilar: Maven no pudo ni leer el `pom.xml`. La causa es un `&` sin escapar
dentro del `<description>` (línea 18), que el parser XML interpreta como el
inicio de una entidad.

## Error observado

Líneas exactas en `logs_github/33_Test inventory-svc.txt` (línea 154):

```text
2026-09-08T03:31:39.2828790Z [FATAL] Non-parseable POM /home/runner/work/K8s/K8s/services/inventory-svc/pom.xml: entity reference names can not start with character ' ' (position: START_TAG seen ...<description>Inventory & ... @18:30)  @ line 18, column 30
```

Y en `logs_github/5_Test analytics-svc.txt` (línea 154):

```text
2026-09-08T03:31:52.0645283Z [FATAL] Non-parseable POM /home/runner/work/K8s/K8s/services/analytics-svc/pom.xml: entity reference names can not start with character ' ' (position: START_TAG seen ...<description>Event ingestion & ... @18:36)  @ line 18, column 36
```

## Por qué ocurre

El XML solo admite `&` como parte de una entidad (`&amp;`, `&lt;`, etc.). Un
`&` "pelado" seguido de espacio hace que el parser intente leer una referencia
a entidad cuyo nombre no puede empezar por espacio → `Non-parseable POM`.

Ambos pom.xml tienen en la línea 18:

```xml
<description>Event ingestion & reporting REST API (in-memory log + counters)</description>   <!-- analytics-svc -->
<description>Inventory & reservation REST API (JPA + H2 for dev, Postgres-ready)</description> <!-- inventory-svc -->
```

## Fix aplicado

Se reemplazó `&` por `&amp;` en los dos `<description>` y se escaneó el resto
de ambos pom.xml en busca de otros `&` sin escapar (URLs, nombres): **no hay
ninguno** — estos eran los únicos.

- `services/analytics-svc/pom.xml` → `<description>Event ingestion &amp; reporting REST API (in-memory log + counters)</description>`
- `services/inventory-svc/pom.xml` → `<description>Inventory &amp; reservation REST API (JPA + H2 for dev, Postgres-ready)</description>`

El valor visible del texto no cambia: `&amp;` se renderiza como `&`.

## Cómo verificar

1. Parseo XML: `python -c "import xml.etree.ElementTree as ET; [ET.parse(p) for p in ['services/analytics-svc/pom.xml','services/inventory-svc/pom.xml']]; print('XML OK')"` → `XML OK`.
2. Re-ejecución de CI: ambos jobs deben pasar del parseo del POM y llegar a la
   fase de tests (`BUILD SUCCESS`).

## Siguiente paso

Ver [README.md](README.md) para el contexto global.