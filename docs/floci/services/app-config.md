# App Configuration

Compatible con los SDK de `azure-appconfiguration` (Java, Python, JavaScript, .NET).

## Características

- **Pares clave-valor** — definir, obtener, eliminar y listar con filtros de clave/etiqueta
- **Etiquetas** — valores independientes por cada par (clave, etiqueta); listar etiquetas distintas
- **Indicadores de características** — compatibilidad de primera clase mediante el prefijo `.appconfig.featureflag/` y el tipo de contenido `application/vnd.microsoft.appconfig.ff+json`
- **Revisiones** — historial completo de revisiones en cada escritura; consultable a través de `GET /revisions`
- **Bloqueos** — bloquear o desbloquear pares clave-valor individuales para impedir su modificación
- **Instantáneas** — copias congeladas en un momento puntual de los conjuntos de pares clave-valor filtrados; aprovisionamiento asíncrono + ciclo de vida de archivo/recuperación
- **ETags** — lecturas condicionales (`If-None-Match`) y escrituras/eliminaciones condicionales (`If-Match`)
- **Tipos de composición** — `key` (deduplicar por clave) y `key_label` (conservar todos los pares clave+etiqueta)
- **Paginación** — encabezado `@nextLink` / `Link` con tokens de continuación `after` opacos (100 elementos por página)
- **Proyección `$select`** — devuelve solo los campos solicitados (`key`, `value`, `content_type`, `tags`, …)
- **Filtrado por etiquetas** — `tags=name=value` (repetible, semántica AND) en las listas de pares clave-valor y revisiones
- **Viaje en el tiempo** — `Accept-Datetime` devuelve el valor o la lista históricos en un momento puntual
- **Sync-Token** — token de consistencia devuelto en cada respuesta

## Filtrado, paginación y consistencia

Los SDK ejercitan estos comportamientos de forma transparente; las notas siguientes describen el comportamiento a nivel de protocolo.

- **Paginación.** Las listas devuelven como máximo 100 elementos. Cuando existen más, el cuerpo de la respuesta incluye un
  `@nextLink` relativo (`/kv?api-version=2024-09-01&...&after=<token>`) y un encabezado `Link: <...>; rel="next"`
  coincidente. La continuación es un token `after` opaco codificado en base64; los paginadores del SDK lo siguen automáticamente.
- **`$select`.** Se puede pasar `$Select=key,value` (CSV) para proyectar cada elemento en los campos solicitados.
- **Filtrado por etiquetas.** Repita `tags=env=prod&tags=tier=web` para filtrar los pares clave-valor y las revisiones por **todas**
  las etiquetas indicadas (AND). Disponible en `GET /kv` y `GET /revisions`.
- **`Accept-Datetime`.** Envíe una fecha HTTP (o ISO-8601) para leer el estado en ese momento; se resuelve
  a partir del historial de revisiones. Tenga en cuenta que los clientes reales lo envían con resolución de segundos completos.
- **`Sync-Token`.** Cada respuesta incluye un encabezado `Sync-Token` (`<id>=<base64>;sn=<seq>`, monotónico
  por cuenta). El SDK lo hace circular para garantizar la consistencia de lecturas-después-de-escrituras dentro de una secuencia.

### Aprovisionamiento asíncrono de instantáneas

`PUT /snapshots/{name}` devuelve `201` con `status: provisioning` y un encabezado `Operation-Location`.
Los sondeos del SDK (`begin_create_snapshot` / `beginCreateSnapshot`) consultan `GET /operations?snapshot={name}`,
que reporta `Succeeded` y cambia el estado de la instantánea a `ready`. En el emulador, el aprovisionamiento es
prácticamente instantáneo.

!!! note "Fuera del alcance (trabajo futuro)"
    Operaciones HEAD `Check*` dedicadas con paridad completa de encabezados, cuerpos problem+json en todas las rutas
    de error distintas de 404, aplicación de la retención/caducidad de las instantáneas y el flujo de autenticación OAuth (AAD).

## Endpoint

```
http://localhost:4577/{accountName}-appconfig
```

Cuenta por defecto: `devstoreaccount1`
Endpoint por defecto: `http://localhost:4577/devstoreaccount1-appconfig`

## Cadena de conexión

El SDK de App Configuration espera un endpoint HTTPS. Utilice el patrón de HTTP forzado que se muestra a continuación para el desarrollo local:

=== "Java"

    ```java
    import com.azure.data.appconfiguration.ConfigurationClientBuilder;
    import com.azure.core.http.policy.HttpPipelinePolicy;
    import com.azure.core.http.HttpPipelineCallContext;
    import com.azure.core.http.HttpPipelineNextPolicy;
    import com.azure.core.http.HttpResponse;
    import com.azure.core.util.Context;
    import reactor.core.publisher.Mono;
    import java.net.URL;

    // Rewrite https → http for local emulator
    static class ForceHttpPolicy implements HttpPipelinePolicy {
        @Override
        public Mono<HttpResponse> process(HttpPipelineCallContext ctx, HttpPipelineNextPolicy next) {
            try {
                URL url = new URL(ctx.getHttpRequest().getUrl().toString());
                ctx.getHttpRequest().setUrl(new URL("http", url.getHost(), url.getPort(), url.getFile()).toString());
            } catch (Exception ignored) {}
            return next.process();
        }
    }

    String account  = "devstoreaccount1";
    String endpoint = "https://localhost:4577/" + account + "-appconfig";
    String connStr  = "Endpoint=" + endpoint + ";Id=" + account + ";Secret=placeholder";

    ConfigurationClient client = new ConfigurationClientBuilder()
            .connectionString(connStr)
            .addPolicy(new ForceHttpPolicy())
            .buildClient();
    ```

=== "Python"

    ```python
    from azure.appconfiguration import AzureAppConfigurationClient
    from azure.core.pipeline.transport import RequestsTransport

    class ForceHttpTransport(RequestsTransport):
        def send(self, request, **kwargs):
            request.url = request.url.replace("https://", "http://", 1)
            return super().send(request, **kwargs)

    account  = "devstoreaccount1"
    endpoint = f"https://localhost:4577/{account}-appconfig"
    conn_str = f"Endpoint={endpoint};Id={account};Secret=placeholder"

    client = AzureAppConfigurationClient.from_connection_string(
        conn_str, transport=ForceHttpTransport()
    )
    ```

=== "JavaScript / TypeScript"

    ```typescript
    import { AppConfigurationClient } from "@azure/app-configuration";

    const account  = "devstoreaccount1";
    const endpoint = `https://localhost:4577/${account}-appconfig`;
    const connStr  = `Endpoint=${endpoint};Id=${account};Secret=placeholder`;

    const client = new AppConfigurationClient(connStr);
    ```

---

## Pares clave-valor

### Definir un par clave-valor

=== "Java"

    ```java
    client.setConfigurationSetting("my-key", null, "my-value");
    ```

=== "Python"

    ```python
    from azure.appconfiguration import ConfigurationSetting
    client.set_configuration_setting(ConfigurationSetting(key="my-key", value="my-value"))
    ```

### Obtener un par clave-valor

=== "Java"

    ```java
    ConfigurationSetting setting = client.getConfigurationSetting("my-key", null);
    System.out.println(setting.getValue()); // "my-value"
    ```

=== "Python"

    ```python
    setting = client.get_configuration_setting("my-key")
    print(setting.value)  # "my-value"
    ```

### Eliminar un par clave-valor

=== "Java"

    ```java
    client.deleteConfigurationSetting("my-key", null);
    ```

=== "Python"

    ```python
    client.delete_configuration_setting("my-key")
    ```

### Listar pares clave-valor

=== "Java"

    ```java
    client.listConfigurationSettings(new SettingSelector().setKeyFilter("app/*"))
          .forEach(s -> System.out.println(s.getKey() + " = " + s.getValue()));
    ```

=== "Python"

    ```python
    for s in client.list_configuration_settings(key_filter="app/*"):
        print(f"{s.key} = {s.value}")
    ```

---

## Etiquetas

Las etiquetas permiten mantener variantes de la misma clave según el entorno:

=== "Java"

    ```java
    client.setConfigurationSetting(
        new ConfigurationSetting().setKey("timeout").setValue("30").setLabel("prod"));
    client.setConfigurationSetting(
        new ConfigurationSetting().setKey("timeout").setValue("5").setLabel("dev"));

    // Fetch by label
    String prodVal = client.getConfigurationSetting("timeout", "prod").getValue(); // "30"
    String devVal  = client.getConfigurationSetting("timeout", "dev").getValue();  // "5"
    ```

=== "Python"

    ```python
    client.set_configuration_setting(ConfigurationSetting(key="timeout", value="30", label="prod"))
    client.set_configuration_setting(ConfigurationSetting(key="timeout", value="5",  label="dev"))

    prod = client.get_configuration_setting("timeout", label_filter="prod").value  # "30"
    dev  = client.get_configuration_setting("timeout", label_filter="dev").value   # "5"
    ```

---

## Indicadores de características

=== "Java"

    ```java
    // Enable a flag
    client.setConfigurationSetting(new FeatureFlagConfigurationSetting("dark-mode", true));

    // Check the flag
    ConfigurationSetting s = client.getConfigurationSetting(
        ".appconfig.featureflag/dark-mode", null);
    boolean enabled = ((FeatureFlagConfigurationSetting) s).isEnabled();
    ```

=== "Python"

    ```python
    from azure.appconfiguration import FeatureFlagConfigurationSetting

    client.set_configuration_setting(FeatureFlagConfigurationSetting("dark-mode", enabled=True))

    flag = client.get_configuration_setting(".appconfig.featureflag/dark-mode")
    print(flag.enabled)  # True
    ```

---

## Instantáneas

Las instantáneas capturan una copia congelada y puntual de los pares clave-valor filtrados. Son inmutables tras su creación: los cambios en los pares clave-valor activos no afectan a una instantánea existente.

### Crear una instantánea

=== "Java"

    ```java
    ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
        List.of(new ConfigurationSettingsFilter("app/*").setLabel("prod"))
    );
    ConfigurationSnapshot created = client
        .beginCreateSnapshot("release-1.0", snapshot, null)
        .getFinalResult();
    ```

=== "Python"

    ```python
    from azure.appconfiguration import ConfigurationSettingsFilter

    created = client.begin_create_snapshot(
        name="release-1.0",
        filters=[ConfigurationSettingsFilter(key="app/*", label="prod")],
    ).result()
    ```

### Leer desde una instantánea

=== "Java"

    ```java
    client.listConfigurationSettingsForSnapshot("release-1.0")
          .forEach(s -> System.out.println(s.getKey() + " = " + s.getValue()));
    ```

=== "Python"

    ```python
    for s in client.list_configuration_settings(snapshot_name="release-1.0"):
        print(f"{s.key} = {s.value}")
    ```

### Archivar y recuperar

=== "Java"

    ```java
    client.archiveSnapshot("release-1.0");   // status → archived
    client.recoverSnapshot("release-1.0");   // status → ready
    ```

=== "Python"

    ```python
    client.archive_snapshot("release-1.0")
    client.recover_snapshot("release-1.0")
    ```

### Tipos de composición

| Tipo | Comportamiento |
|---|---|
| `key` (por defecto) | Una entrada por clave; cuando las claves se solapan, gana el último filtro coincidente |
| `key_label` | Una entrada por par (clave, etiqueta); sin deduplicación |

=== "Java"

    ```java
    new ConfigurationSnapshot(filters)
        .setSnapshotComposition(SnapshotComposition.KEY_LABEL);
    ```

=== "Python"

    ```python
    from azure.appconfiguration import SnapshotComposition

    client.begin_create_snapshot(
        name="all-envs",
        filters=[ConfigurationSettingsFilter(key="app/*")],
        composition_type=SnapshotComposition.KEY_LABEL,
    ).result()
    ```

---

## Referencia de la API REST

Todos los endpoints están bajo `/{accountName}-appconfig/` con un parámetro de consulta `api-version` (p. ej., `?api-version=2023-11-01`).

### Pares clave-valor

| Método | Ruta | Descripción |
|---|---|---|
| `GET` / `HEAD` | `/kv` | Listar pares clave-valor (admite filtros `key`, `label`) |
| `GET` / `HEAD` | `/kv/{key}` | Obtener un par clave-valor |
| `PUT` | `/kv/{key}` | Definir un par clave-valor |
| `DELETE` | `/kv/{key}` | Eliminar un par clave-valor |

### Claves y etiquetas

| Método | Ruta | Descripción |
|---|---|---|
| `GET` / `HEAD` | `/keys` | Listar nombres de clave distintos |
| `GET` / `HEAD` | `/labels` | Listar etiquetas distintas |

### Revisiones

| Método | Ruta | Descripción |
|---|---|---|
| `GET` / `HEAD` | `/revisions` | Listar el historial de revisiones (admite filtros `key`, `label`) |

### Bloqueos

| Método | Ruta | Descripción |
|---|---|---|
| `PUT` | `/locks/{key}` | Bloquear un par clave-valor (impide su modificación) |
| `DELETE` | `/locks/{key}` | Desbloquear un par clave-valor |

### Instantáneas

| Método | Ruta | Descripción |
|---|---|---|
| `GET` / `HEAD` | `/snapshots` | Listar instantáneas (admite filtro `name`) |
| `GET` / `HEAD` | `/snapshots/{name}` | Obtener una instantánea |
| `PUT` | `/snapshots/{name}` | Crear una instantánea |
| `PATCH` | `/snapshots/{name}` | Archivar o recuperar una instantánea |
| `GET` | `/kv?snapshot={name}` | Listar los pares clave-valor congelados de una instantánea |

### Operaciones (sondeo LRO)

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/operations?snapshot={name}` | Consultar el estado de creación de la instantánea (siempre devuelve `Succeeded`) |

---

## Operaciones condicionales (ETags)

Cada respuesta de pares clave-valor incluye un encabezado `ETag`. Utilice `If-Match` para la concurrencia optimista:

```bash
# Conditional update — only succeeds if ETag matches
curl -X PUT "http://localhost:4577/devstoreaccount1-appconfig/kv/my-key" \
  -H "If-Match: \"<etag>\"" \
  -H "Content-Type: application/json" \
  -d '{"value": "new-value"}'
```

Un desajuste devuelve **412 Precondition Failed**. Una clave bloqueada devuelve **423 Locked**.

---

## Configuración de almacenamiento

```yaml
floci-az:
  storage:
    services:
      app-config:
        # mode: persistent   # override global storage mode
        flush-interval-ms: 5000

  services:
    app-config:
      enabled: true
```

| Variable de entorno | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_APP_CONFIG_ENABLED` | `true` | Habilitar o deshabilitar el servicio |
| `FLOCI_AZ_STORAGE_SERVICES_APP_CONFIG_MODE` | _(global)_ | Modo de almacenamiento por servicio |