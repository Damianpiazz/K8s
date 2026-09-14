# Microsoft Entra ID

Un **proveedor local de OpenID Connect** que emite JWTs reales firmados con **RS256**, con
documento de descubrimiento publicado y JWKS. Esto reemplaza el stub estático anterior de tokens
sin firmar, de modo que las aplicaciones que *adquieren* y *validan* tokens de Entra puedan
funcionar completamente sin conexión.

> La **Fase 1** entregó los cimientos de OIDC y los dos grants no interactivos (**client
> credentials** y **resource-owner password / ROPC**). La **Fase 2**
> ([#120](https://github.com/floci-io/floci-az/issues/120)) agrega el grant de **authorization
> code + PKCE** para el inicio de sesión interactivo (SPA en el navegador), además de una porción
> limitada de Microsoft Graph para la pertenencia a grupos: ver [`services/graph.md`](graph.md).
> La administración del registro de aplicaciones y el CRUD completo de Graph siguen pendientes
> ([#23](https://github.com/floci-io/floci-az/issues/23)).

## Características

- **Tokens firmados** — JWTs RS256 con una clave de firma estable persistida entre reinicios; los
  tokens de solo aplicación llevan `idtyp=app`, y cada token lleva un `uti` único, igual que Entra real
- **Descubrimiento** — `/.well-known/openid-configuration` derivado de la URL base de la petición
- **JWKS** — `/discovery/v2.0/keys` que expone la clave de firma pública (`kty`, `use`, `alg`, `kid`,
  `n`, `e`) además de la cadena de certificados autofirmada (`x5c`, `x5t`)
- **Grants** — `client_credentials`, `password` (ROPC) y `authorization_code` (auth code + PKCE),
  formatos de token v1.0 y v2.0
- **Errores con formato de Azure** — los errores de token devuelven `error`, `error_description`
  (con el código `AADSTS`), `error_codes`, `trace_id`, `correlation_id`, `timestamp` y `error_uri`
- **Semilla de desarrollo** — un inquilino por defecto y un registro de aplicación de desarrollo
  conocido, de modo que `ClientSecretCredential` funciona sin configuración previa

## Endpoints

Todos los endpoints tienen raíz de inquilino en la URL base (puerto `4577`). `{tenant}` puede ser
un id de inquilino o `common` / `organizations` / `consumers`.

| Ruta | Propósito |
|---|---|
| `GET /{tenant}/oauth2/v2.0/authorize` | Endpoint de autorización (auth code + PKCE) |
| `POST /{tenant}/oauth2/v2.0/token` | Endpoint de token (v2.0) |
| `POST /{tenant}/oauth2/token` | Endpoint de token (v1.0) |
| `GET /{tenant}/v2.0/.well-known/openid-configuration` | Descubrimiento OpenID |
| `GET /{tenant}/.well-known/openid-configuration` | Descubrimiento OpenID |
| `GET /{tenant}/discovery/v2.0/keys` | JWKS |

## Inquilino por defecto y credenciales de desarrollo

| Valor | Por defecto |
|---|---|
| Id de inquilino | `00000000-0000-0000-0000-000000000002` |
| Id de cliente | `11111111-1111-1111-1111-111111111111` |
| Secreto de cliente | `floci-az-dev-secret` |
| Usuario de desarrollo (UPN) | `dev-user@floci-az.local` |
| Grupo de desarrollo | `floci-az dev group` (el usuario de desarrollo es miembro directo) |

## Inicio de sesión interactivo (authorization code + PKCE)

No hay una pantalla de consentimiento interactiva real: `GET /{tenant}/oauth2/v2.0/authorize`
autoaprueba contra el usuario de desarrollo sembrado (u otro usuario sembrado, seleccionado
mediante `login_hint`) y redirige directamente a `redirect_uri` con un `code` — sin formulario de
inicio de sesión ni nada que cliquear. Esto mantiene el flujo totalmente automatizable y a la vez
ejercita el protocolo real de auth-code+PKCE que habla MSAL (`@azure/msal-browser`,
`@azure/msal-react`, `@azure/msal-node`).

```
GET /{tenant}/oauth2/v2.0/authorize
    ?client_id=11111111-1111-1111-1111-111111111111
    &redirect_uri=https://app.local/callback
    &response_type=code
    &response_mode=query          # or "fragment"
    &scope=openid profile
    &state=...
    &nonce=...
    &code_challenge=...           # PKCE S256 challenge
    &code_challenge_method=S256
    &login_hint=dev-user@floci-az.local   # optional; defaults to the seeded dev user
```

Redirige con `302` a `{redirect_uri}?code=...&state=...` (o `#code=...&state=...` para
`response_mode=fragment`). El code emitido está vinculado al inquilino, `client_id` y `redirect_uri`
de esta petición: el canje debe repetir el mismo `client_id`/`redirect_uri` y realizarse contra el
mismo inquilino, o fallará con `invalid_grant`. Cánjelo con `grant_type=authorization_code`:

```bash
curl -s http://localhost:4577/00000000-0000-0000-0000-000000000002/oauth2/v2.0/token \
  -d grant_type=authorization_code \
  -d client_id=11111111-1111-1111-1111-111111111111 \
  -d redirect_uri=https://app.local/callback \
  -d code=<code from the redirect> \
  -d code_verifier=<the PKCE verifier for the challenge you sent>
```

La respuesta incluye tanto `access_token` como `id_token`. El `aud` del ID token es siempre el id
de cliente (según OIDC, sin importar v1.0/v2.0) y devuelve el `nonce` de la petición `/authorize`;
MSAL rechaza un ID token cuyo nonce no coincida. La verificación PKCE (`S256` o `plain`) se omite
cuando `/authorize` se llamó sin `code_challenge` — `client_id` sigue siendo obligatorio y está
vinculado al code, de modo que omitir el challenge ya no entrega un code utilizable a un llamador
que no conoce el cliente. Aún no existe una allow-list de redirect-URI del registro de aplicaciones
([#23](https://github.com/floci-io/floci-az/issues/23)), por lo que se acepta cualquier `client_id`
en `/authorize`, en línea con la validación permisiva de clientes de esta fase en otros lados
(`client_credentials` también se acepta sin validación estricta).

**Aún no compatible:** `grant_type=refresh_token` — la renovación silenciosa de tokens de MSAL
mediante `offline_access` no funcionará todavía contra este emulador.

La **pertenencia a grupos** del usuario autenticado *no* está incrustada en el token (sin reclamo
`groups`, igual que el comportamiento por defecto de Entra real) — en su lugar, llame al endpoint
`getMemberGroups` de Graph; ver [`services/graph.md`](graph.md).

## Adquirir un token

=== "Python"

    ```python
    from azure.identity import ClientSecretCredential

    cred = ClientSecretCredential(
        tenant_id="00000000-0000-0000-0000-000000000002",
        client_id="11111111-1111-1111-1111-111111111111",
        client_secret="floci-az-dev-secret",
        authority="http://localhost:4577",
    )
    token = cred.get_token("api://resource/.default")
    print(token.token)  # RS256-signed JWT
    ```

=== "curl"

    ```bash
    curl -s http://localhost:4577/00000000-0000-0000-0000-000000000002/oauth2/v2.0/token \
      -d grant_type=client_credentials \
      -d client_id=11111111-1111-1111-1111-111111111111 \
      -d client_secret=floci-az-dev-secret \
      -d scope=api://resource/.default
    ```

## Validar un token

Obtenga la JWKS y valide la firma, `iss`, `aud` y `exp`:

```python
import jwt
from jwt import PyJWKClient

jwks = PyJWKClient("http://localhost:4577/00000000-0000-0000-0000-000000000002/discovery/v2.0/keys")
signing_key = jwks.get_signing_key_from_jwt(token)
claims = jwt.decode(token, signing_key.key, algorithms=["RS256"], audience="api://resource")
```

## Configuración

```yaml
floci-az:
  services:
    entra:
      enabled: true                 # local OIDC provider (default on)
      default-tenant-id: "00000000-0000-0000-0000-000000000002"
      # issuer:                     # optional override; default {baseUrl}/{tenant}/v2.0
      token-lifetime-seconds: 3599
      validate-tokens: false        # true = enforce signature/claims on incoming Bearer tokens
      # signing-key-path:           # optional; default {storage.persistent-path}/entra
```

| Parámetro | Variable de entorno | Por defecto |
|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_ENTRA_ENABLED` | `true` |
| `default-tenant-id` | `FLOCI_AZ_SERVICES_ENTRA_DEFAULT_TENANT_ID` | `00000000-0000-0000-0000-000000000002` |
| `issuer` | `FLOCI_AZ_SERVICES_ENTRA_ISSUER` | _(derivado de la petición)_ |
| `token-lifetime-seconds` | `FLOCI_AZ_SERVICES_ENTRA_TOKEN_LIFETIME_SECONDS` | `3599` |
| `validate-tokens` | `FLOCI_AZ_SERVICES_ENTRA_VALIDATE_TOKENS` | `false` |
| `signing-key-path` | `FLOCI_AZ_SERVICES_ENTRA_SIGNING_KEY_PATH` | `{storage.persistent-path}/entra` |

> `validate-tokens` permanece **desactivado** por defecto para que los servicios existentes sigan
> aceptando cualquier token Bearer en desarrollo. La *aplicación* (enforcement) de tokens contra la
> clave de firma local será opcional en una fase posterior.

> **Mantenga `enabled: true` salvo que tenga un motivo para no hacerlo.** El endpoint de token
> OAuth2 (`/{tenant}/oauth2/v2.0/token`) lo sirve este servicio. Deshabilitar Entra hace que ese
> endpoint devuelva `404`, lo que rompe los handshakes de inicio de sesión de ARM/Terraform y de
> los SDK que se autentican a través de él.