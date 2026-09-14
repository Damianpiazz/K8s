# Managed Identity

Compatible con `ManagedIdentityCredential` de `azure-identity` (Java, Python, Node.js), el plano
de administración ARM `Microsoft.ManagedIdentity` y cualquier cliente HTTP. Cubre las
**user-assigned identities** (con federated identity credentials) y el **endpoint de token IMDS**
que las aplicaciones usan para adquirir tokens sin secrets.

> **Solo HTTP: sin Docker.** El estado del CRUD ARM está en memoria; los tokens IMDS se acuñan con
> la misma clave de firma RSA que la emulación de [Entra ID](entra.md), de modo que se verifican
> contra la JWKS del emulador.

---

## Características

- **User-assigned identities** — CreateOrUpdate, Get, Update (tags), Delete, List (por grupo de
  recursos y por suscripción); `principalId` / `clientId` / `tenantId` son GUID generados por el
  servidor que se mantienen estables entre actualizaciones (spec msi `2024-11-30`)
- **Federated identity credentials** — CreateOrUpdate, Get, Delete, List con `issuer` / `subject` /
  `audiences` (tal como las usa `azurerm_federated_identity_credential`)
- **Lectura de identidad system-assigned** — `GET {scope}/providers/Microsoft.ManagedIdentity/identities/default`
  devuelve GUID determinísticos por ámbito
- **Endpoint de token IMDS** — `GET /metadata/identity/oauth2/token` (spec imds `2023-07-01`):
  requiere el header `Metadata: true`, acepta `resource` además de un selector opcional `client_id` /
  `object_id` / `msi_res_id`, y devuelve la forma de respuesta IMDS con todos los valores como
  string. Se acepta cualquier `api-version` (los SDK envían `2018-02-01`)
- **Tokens verificables** — JWTs v1.0 (`appid`, `oid`, `idtyp=app`) firmados con la clave de Entra;
  valide contra `GET /common/discovery/v2.0/keys`

---

## Endpoints

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ManagedIdentity/userAssignedIdentities
GET    /subscriptions/{sub}/providers/Microsoft.ManagedIdentity/userAssignedIdentities

PUT    .../userAssignedIdentities/{name}/federatedIdentityCredentials/{ficName}
GET    .../userAssignedIdentities/{name}/federatedIdentityCredentials/{ficName}
DELETE .../userAssignedIdentities/{name}/federatedIdentityCredentials/{ficName}
GET    .../userAssignedIdentities/{name}/federatedIdentityCredentials

GET    /{scope}/providers/Microsoft.ManagedIdentity/identities/default

GET    /metadata/identity/oauth2/token?resource={resource}[&client_id=...]   # header: Metadata: true
```

---

## Inicio rápido

### 1 — Crear una user-assigned identity

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ManagedIdentity/userAssignedIdentities/my-identity?api-version=2024-11-30" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus"}'
```

```json
{
  "name": "my-identity",
  "type": "Microsoft.ManagedIdentity/userAssignedIdentities",
  "properties": {
    "tenantId": "00000000-0000-0000-0000-000000000002",
    "principalId": "e3b0c442-…",
    "clientId": "9f86d081-…"
  }
}
```

### 2 — Adquirir un token mediante IMDS (HTTP crudo)

```bash
curl -s -H "Metadata: true" \
  "http://localhost:4577/metadata/identity/oauth2/token?resource=https://management.azure.com/&api-version=2018-02-01&client_id={clientId}"
```

Omita `client_id` para un token system-assigned. Todos los valores de la respuesta son strings,
según el contrato real de IMDS:

```json
{
  "access_token": "eyJ0…",
  "client_id": "9f86d081-…",
  "expires_in": "3599",
  "expires_on": "1767225599",
  "ext_expires_in": "3599",
  "not_before": "1767221999",
  "resource": "https://management.azure.com/",
  "token_type": "Bearer"
}
```

### 3 — Usar `ManagedIdentityCredential` desde los SDK

Los SDK de azure-identity apuntan a `http://169.254.169.254` por defecto. Apúntelos al emulador
con `AZURE_POD_IDENTITY_AUTHORITY_HOST` (respetado por los SDK de Java, Python y Node.js):

```bash
export AZURE_POD_IDENTITY_AUTHORITY_HOST=http://localhost:4577
```

=== "Python"

    ```python
    from azure.identity import ManagedIdentityCredential

    credential = ManagedIdentityCredential(client_id="{clientId}")  # or no args for system-assigned
    token = credential.get_token("https://management.azure.com/.default")
    ```

=== "Java"

    ```java
    ManagedIdentityCredential credential = new ManagedIdentityCredentialBuilder()
            .clientId("{clientId}")
            .build();
    AccessToken token = credential
            .getToken(new TokenRequestContext().addScopes("https://management.azure.com/.default"))
            .block();
    ```

=== "Node.js"

    ```javascript
    const { ManagedIdentityCredential } = require("@azure/identity");

    const credential = new ManagedIdentityCredential("{clientId}");
    const token = await credential.getToken("https://management.azure.com/.default");
    ```

---

## Configuración

```yaml
floci-az:
  services:
    managed-identity:
      enabled: true
      system-assigned-scope: subscriptions/00000000-0000-0000-0000-000000000001
```

| Propiedad | Variable de entorno | Por defecto | Descripción |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_MANAGED_IDENTITY_ENABLED` | `true` | Habilita el provider ARM y el endpoint IMDS |
| `system-assigned-scope` | `FLOCI_AZ_SERVICES_MANAGED_IDENTITY_SYSTEM_ASSIGNED_SCOPE` | `subscriptions/00000000-0000-0000-0000-000000000001` | Ámbito ARM que siembra el `principalId`/`clientId` de la identidad IMDS system-assigned. Defínalo en el ámbito de su recurso (p. ej., `subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines/{vm}`) para que los tokens IMDS coincidan con las lecturas `GET {scope}/.../identities/default` |

El inquilino, el issuer y la vida útil del token siguen la configuración de [Entra ID](entra.md)
(`floci-az.services.entra.*`).

---

## Desviaciones intencionales

- **`clientSecretUrl` es sintético** — Azure real devuelve una URL de renovación de credenciales
  respaldada por Key Vault para `identities/default`; el emulador devuelve una URL de marcador de
  posición bajo la base del emulador.
- **Se acepta cualquier `api-version`** en ambos planos; el IMDS real rechaza versiones desconocidas.
- **`isolationScope` no se modela** en las user-assigned identities.
- **Las federated identity credentials son solo CRUD** — sin semántica de intercambio de tokens.
- **Las identidades desconocidas devuelven** `400 {"error":"invalid_request","error_description":"Identity not found"}`,
  igual que el comportamiento del IMDS real para identidades de usuario no asignadas.
- **La identidad IMDS system-assigned no está ligada a un recurso llamador** — en Azure real, IMDS
  se ejecuta en el recurso y devuelve la identidad de ese mismo recurso. El emulador no está
  adjunto a un recurso, por lo que los tokens system-assigned se siembran desde el
  `system-assigned-scope` configurado (por defecto: la suscripción por defecto). Las lecturas
  `identities/default` para *otros* ámbitos devuelven GUID diferentes (determinísticos); defina
  `system-assigned-scope` en el ámbito de su recurso cuando su código compare un `principalId`
  leído por ARM contra el `oid` del token.
- **El estado de las identidades es solo en memoria** — como el resto del plano de control ARM,
  las identidades y las federated credentials no persisten entre reinicios, sin importar
  `storage.mode`.
- **La eliminación de grupos de recursos no se propaga en cascada** — eliminar un grupo de recursos
  deja atrás sus identidades (coherente con los otros recursos ARM del emulador). Las identidades
  sí aparecen en `GET .../resourceGroups/{rg}/resources`, por lo que
  `prevent_deletion_if_contains_resources` funciona.