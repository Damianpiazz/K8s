# Key Vault

Compatible con los SDK `azure-keyvault-secrets` y `azure-keyvault-keys` (Python, Java, JavaScript, .NET).

## Características

### Secrets

- **CRUD de secrets** — set, get, delete, list de secrets
- **Versioning** — cada `set_secret` crea una versión inmutable nueva; el puntero latest rastrea la más reciente
- **Ciclo de vida de eliminación temporal** — delete mueve un secret al espacio de nombres de eliminados; se puede recuperar o purgar
- **Actualización de properties** — actualiza `content_type`, `tags`, `enabled`, `nbf`, `exp` sin cambiar el valor
- **Atributos** — `enabled`, `not_before`, `expires_on`; los secrets deshabilitados devuelven 403 al hacer get
- **Timestamps opcionales** — los atributos `nbf` y `exp` sin definir se omiten en las respuestas; los valores proporcionados siguen siendo timestamps Unix numéricos
- **Operaciones de listado** — listar secrets activos, secrets eliminados o versiones de un secret específico
- **Slash final opcional** — las rutas fijas (`/secrets`, `/deletedsecrets`, `/certificates/contacts`) aceptan un slash final, incluida la carga de configuración de .NET `AddAzureKeyVault`
- **Backup** — hacer backup de un secret (blob codificado en base64)
- **IDs de versión hex de 32 caracteres** — coincide con el formato de ID de versión de Azure

### Keys y criptografía

- **CRUD de keys** — crear/importar keys RSA (`RSA`, `RSA-HSM`), EC (`EC`, `EC-HSM`, P-256/P-384/P-521) y
  oct (`oct`, `oct-HSM`); get/list/list-versions; atributos PATCH
- **Ciclo de vida de eliminación temporal** — delete → espacio de nombres `deletedkeys` → recover o purge
- **Backup/restore** — `POST /keys/{name}/backup` y `POST /keys/restore` (ver desviaciones más abajo)
- **Rotación** — `POST /keys/{name}/rotate` y administración de la política de rotación (`/keys/{name}/rotationpolicy`)
- **Operaciones criptográficas** — `encrypt`/`decrypt` (RSA-OAEP, RSA-OAEP-256, RSA1_5; AES-GCM A128/A192/A256),
  `sign`/`verify` (RS256/384/512, PS256/384/512, ES256/384/512), `wrapkey`/`unwrapkey`
- **`/rng`** — bytes aleatorios para material de claves del lado del cliente (ver desviaciones)
- **Managed HSM** — el mismo plano de datos servido bajo el sufijo `/{account}-managedhsm/`

## Endpoint

```
http://localhost:4577/{accountName}-keyvault
```

Cuenta por defecto: `devstoreaccount1`
Endpoint por defecto: `http://localhost:4577/devstoreaccount1-keyvault`

Cuando TLS está habilitado, el enrutamiento basado en host también funciona: `https://{vault}.vault.azure.net` y
`https://{vault}.managedhsm.azure.net` enrutan al Key Vault / Managed HSM correspondiente cuando DNS
resuelve esos nombres a floci-az. Estas URLs sin puerto apuntan al puerto 443, así que publique o haga
forward del puerto 443 además del 4577 en los despliegues de Docker: el emulador intenta hacer bind del
443 por defecto, pero trata el fallo de bind como no fatal. Los clientes que admiten un puerto explícito
de endpoint pueden usar en su lugar `https://{vault}.vault.azure.net:4577` y
`https://{vault}.managedhsm.azure.net:4577`. El certificado autofirmado ya incluye los SAN
`*.vault.azure.net` y `*.managedhsm.azure.net`, por lo que los clientes solo deben confiar en la CA
generada en `{persistent-path}/tls/floci-az-selfsigned-ca.crt`.

## Cadena de conexión

El SDK de Key Vault exige HTTPS y usa un flujo de autenticación basado en challenge. Use los patrones
siguientes para conectarse al emulador local:

=== "Python"

    ```python
    import re, time
    from azure.core.credentials import AccessToken, TokenCredential
    from azure.core.pipeline.transport import RequestsTransport
    from azure.keyvault.secrets import SecretClient

    class FakeCredential(TokenCredential):
        def get_token(self, *scopes, **kwargs):
            return AccessToken("fake-token", int(time.time()) + 3600)

    class ForceHttpTransport(RequestsTransport):
        def send(self, request, **kwargs):
            request.url = request.url.replace("https://", "http://", 1)
            return super().send(request, **kwargs)

    account   = "devstoreaccount1"
    endpoint  = "http://localhost:4577"
    vault_url = re.sub(r"^http://", "https://", endpoint) + f"/{account}-keyvault"

    client = SecretClient(
        vault_url=vault_url,
        credential=FakeCredential(),
        transport=ForceHttpTransport(),
        verify_challenge_resource=False,
    )
    ```

=== "Java"

    ```java
    import com.azure.core.credential.AccessToken;
    import com.azure.core.credential.TokenCredential;
    import com.azure.core.http.HttpPipelineCallContext;
    import com.azure.core.http.HttpPipelineNextPolicy;
    import com.azure.core.http.HttpResponse;
    import com.azure.core.http.policy.HttpPipelinePolicy;
    import com.azure.security.keyvault.secrets.SecretClient;
    import com.azure.security.keyvault.secrets.SecretClientBuilder;
    import reactor.core.publisher.Mono;
    import java.net.URL;
    import java.time.OffsetDateTime;

    static class FakeCredential implements TokenCredential {
        @Override
        public Mono<AccessToken> getToken(TokenRequestContext req) {
            return Mono.just(new AccessToken("fake-token", OffsetDateTime.now().plusHours(1)));
        }
    }

    static class ForceHttpPolicy implements HttpPipelinePolicy {
        @Override
        public Mono<HttpResponse> process(HttpPipelineCallContext ctx, HttpPipelineNextPolicy next) {
            try {
                URL url = new URL(ctx.getHttpRequest().getUrl().toString());
                ctx.getHttpRequest().setUrl(
                    new URL("http", url.getHost(), url.getPort(), url.getFile()).toString());
            } catch (Exception ignored) {}
            return next.process();
        }
    }

    String account  = "devstoreaccount1";
    String vaultUrl = "https://localhost:4577/" + account + "-keyvault";

    SecretClient client = new SecretClientBuilder()
            .vaultUrl(vaultUrl)
            .credential(new FakeCredential())
            .addPolicy(new ForceHttpPolicy())
            .disableChallengeResourceVerification()
            .buildClient();
    ```

---

## Secrets

### Establecer un secret

=== "Python"

    ```python
    secret = client.set_secret("my-secret", "my-value")
    print(secret.value)           # "my-value"
    print(secret.properties.version)  # 32-char hex version ID
    ```

=== "Java"

    ```java
    KeyVaultSecret secret = client.setSecret("my-secret", "my-value");
    System.out.println(secret.getValue());                   // "my-value"
    System.out.println(secret.getProperties().getVersion()); // 32-char hex
    ```

### Obtener un secret

=== "Python"

    ```python
    secret = client.get_secret("my-secret")          # latest version
    secret = client.get_secret("my-secret", version) # specific version
    ```

=== "Java"

    ```java
    KeyVaultSecret secret = client.getSecret("my-secret");           // latest
    KeyVaultSecret secret = client.getSecret("my-secret", version);  // specific version
    ```

### Eliminar un secret (eliminación temporal)

=== "Python"

    ```python
    poller = client.begin_delete_secret("my-secret")
    deleted = poller.result()
    print(deleted.deleted_date)          # when it was deleted
    print(deleted.scheduled_purge_date)  # after 7 days
    ```

### Recuperar un secret eliminado

=== "Python"

    ```python
    poller = client.begin_recover_deleted_secret("my-secret")
    recovered = poller.result()
    secret = client.get_secret("my-secret")  # back to normal
    ```

### Purgar un secret eliminado

=== "Python"

    ```python
    client.purge_deleted_secret("my-secret")  # permanently gone
    ```

---

## Versionado

Cada llamada a `set_secret` crea una versión inmutable nueva. `get_secret(name)` siempre devuelve la
versión más reciente.

=== "Python"

    ```python
    v1 = client.set_secret("db-password", "hunter2")
    v2 = client.set_secret("db-password", "correct-horse-battery-staple")

    # Latest is always v2
    latest = client.get_secret("db-password")
    assert latest.value == "correct-horse-battery-staple"

    # Access v1 by version ID
    old = client.get_secret("db-password", v1.properties.version)
    assert old.value == "hunter2"

    # List all versions
    for props in client.list_properties_of_secret_versions("db-password"):
        print(props.version, props.created_on)
    ```

---

## Actualización de properties

Actualiza metadatos sin crear una versión nueva:

=== "Python"

    ```python
    s = client.set_secret("api-key", "abc123", content_type="text/plain")

    client.update_secret_properties(
        "api-key",
        s.properties.version,
        content_type="application/json",
        tags={"env": "prod"},
        enabled=False,
    )
    ```

---

## Referencia de la API REST

Todos los endpoints están bajo `/{accountName}-keyvault/` con un parámetro de consulta `api-version`.

### Secrets

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/secrets` | Lista todos los secrets (solo properties, sin valores) |
| `GET` | `/secrets/{name}` | Obtiene la versión más reciente |
| `PUT` | `/secrets/{name}` | Establece un secret (crea una versión nueva) |
| `DELETE` | `/secrets/{name}` | Elimina un secret (soft-delete) |
| `GET` | `/secrets/{name}/{version}` | Obtiene una versión específica |
| `PATCH` | `/secrets/{name}/{version}` | Actualiza las properties del secret |
| `GET` | `/secrets/{name}/versions` | Lista todas las versiones |
| `POST` | `/secrets/{name}/backup` | Hace backup de un secret |

### Secrets eliminados

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/deletedsecrets` | Lista los secrets eliminados |
| `GET` | `/deletedsecrets/{name}` | Obtiene un secret eliminado |
| `DELETE` | `/deletedsecrets/{name}` | Purga (elimina permanentemente) |
| `POST` | `/deletedsecrets/{name}/recover` | Recupera un secret eliminado |

### Keys

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/keys/{name}/create` | Crea una key (`kty`, `key_size`, `curve`, `key_ops`) |
| `PUT` | `/keys/{name}` | Importa una key (`key` JWK) |
| `GET` | `/keys` | Lista las keys |
| `GET` | `/keys/{name}` | Obtiene la versión más reciente |
| `GET` | `/keys/{name}/{version}` | Obtiene una versión específica |
| `PATCH` | `/keys/{name}/{version}` | Actualiza los atributos de la key (`enabled`, `nbf`, `exp`, `tags`) |
| `DELETE` | `/keys/{name}` | Elimina una key (soft-delete) |
| `GET` | `/keys/{name}/versions` | Lista todas las versiones |
| `POST` | `/keys/{name}/backup` | Hace backup de una key |
| `POST` | `/keys/restore` | Restaura una key desde un blob de backup |
| `POST` | `/keys/{name}/rotate` | Rota (regenera) una key |
| `GET`/`PUT` | `/keys/{name}/rotationpolicy` | Obtiene o define la política de rotación de la key |

### Keys eliminadas

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/deletedkeys` | Lista las keys eliminadas |
| `GET` | `/deletedkeys/{name}` | Obtiene una key eliminada |
| `DELETE` | `/deletedkeys/{name}` | Purga (elimina permanentemente) |
| `POST` | `/deletedkeys/{name}/recover` | Recupera una key eliminada |

### Operaciones criptográficas

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/keys/{name}[/{version}]/encrypt` | Cifra (`RSA-OAEP`, `RSA-OAEP-256`, `RSA1_5`, `A128GCM`, `A192GCM`, `A256GCM`) |
| `POST` | `/keys/{name}[/{version}]/decrypt` | Descifra |
| `POST` | `/keys/{name}[/{version}]/sign` | Firma (`RS256/384/512`, `PS256/384/512`, `ES256/384/512`) |
| `POST` | `/keys/{name}[/{version}]/verify` | Verifica una firma |
| `POST` | `/keys/{name}[/{version}]/wrapkey` | Envuelve una key (`RSA-OAEP-256` para keys RSA) |
| `POST` | `/keys/{name}[/{version}]/unwrapkey` | Desenvuelve una key |
| `POST` | `/rng` | Bytes aleatorios (`{"count": 1..128}`) |

---

## Desviaciones intencionales

Estas son diferencias deliberadas respecto al Azure Key Vault real. Son comportamiento estable y
documentado — no bugs:

- **Los blobs de backup son texto plano sin cifrar.** Azure real devuelve blobs opacos cifrados con HSM que
  solo pueden restaurarse en el mismo vault. floci-az emite una snapshot JSON legible (JWK + metadatos) para
  que los backups sean portables e inspeccionables. No trate los blobs de backup como secrets.
- **`/rng` tiene un tope de 128 bytes por petición.** Esto coincide con Azure real (que tiene tope en 128).
  Un borrador anterior del plan indicaba 1024; eso era incorrecto.
- **El material de claves se almacena en claro en el backend de almacenamiento** (memoria/persistente),
  no cifrado con HSM.
- **`key_size` puede aparecer en el JWK público devuelto** aunque no forma parte del esquema `JsonWebKey`
  de Azure. Los SDK de Azure toleran campos desconocidos; se mantiene por conveniencia.
- **`nbf`/`exp` deben ser timestamps numéricos del epoch Unix.** Se toleran y normalizan los espacios en
  blanco; cualquier otra forma se rechaza con `400 BadParameter` al crear/importar/PATCH/restaurar.

---

## Configuración de almacenamiento

```yaml
floci-az:
  storage:
    services:
      key-vault:
        # mode: persistent   # override global storage mode
        flush-interval-ms: 5000

  services:
    key-vault:
      enabled: true
```

| Variable de entorno | Por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_KEY_VAULT_ENABLED` | `true` | Habilita o deshabilita el servicio |
| `FLOCI_AZ_STORAGE_SERVICES_KEY_VAULT_MODE` | _(global)_ | Modo de almacenamiento por servicio |