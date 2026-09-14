# Azure Communication Services — Email

Compatible con el SDK `azure-communication-email`, el plano de administración ARM
`Microsoft.Communication` y cualquier cliente HTTP. Floci AZ emula el plano de datos **Email** de
ACS (`POST /emails:send` más sondeo de estado) y **captura cada mensaje en memoria** para su
inspección local: un buzón estilo Mailpit para las pruebas. Nunca se entrega un correo electrónico
real.

> **Solo HTTP: sin Docker.** El envío, el sondeo de estado, la inspección y los recursos ARM
> ocurren todos en proceso. No hay sidecar.

---

## Características

- **Enviar correo** — `POST /emails:send` acepta el payload completo de ACS (`senderAddress`,
  `content` con `subject`/`plainText`/`html`, `recipients` `to`/`cc`/`bcc`, `attachments`,
  `replyTo`, `headers`) y devuelve `202 Accepted` con headers `Operation-Location` y `Retry-After`
  para el sondeo. Un header de petición **`Operation-Id`** suministrado por el llamador se adopta
  como id de la operación; si no se suministra, el emulador genera un UUID, igual que ACS.
- **Estado de la operación** — `GET /emails/operations/{operationId}` informa el estado de la
  operación de larga duración como `{"id":…,"status":…,"error":null}`, la misma forma que devuelve
  ACS. El emulador completa de inmediato, por lo que el estado es `Succeeded`.
- **Buzón de inspección** — `GET /emailMessages` lista cada mensaje capturado, `GET
  /emailMessages/{operationId}` devuelve uno completo (incluido el cuerpo original de la petición)
  y `DELETE /emailMessages` limpia el buzón.
- **Plano de administración ARM** — `Microsoft.Communication/communicationServices`,
  `.../emailServices` y `.../emailServices/{name}/domains/{domain}` CreateOrUpdate, Get, Delete y
  List.

---

## Endpoints

```
POST   /emails:send                              # data-plane send → 202 + Operation-Location
GET    /emails/operations/{operationId}          # operation status (Succeeded)

GET    /emailMessages                            # list captured messages
GET    /emailMessages/{operationId}              # single captured message detail
DELETE /emailMessages                            # clear the mailbox

PUT/GET/DELETE .../providers/Microsoft.Communication/communicationServices/{name}
PUT/GET/DELETE .../providers/Microsoft.Communication/emailServices/{name}
PUT/GET/DELETE .../providers/Microsoft.Communication/emailServices/{name}/domains/{domain}
```

Las rutas de envío y de sondeo también se sirven cuando el SDK apunta al formato de host de ACS
`https://{resource}.communication.azure.com` (use `FLOCI_AZ_HOSTNAME` o el archivo de hosts para
apuntar ese nombre al emulador), o mediante el sufijo con estilo de ruta `/{account}-email/`.

---

## Inicio rápido

### Enviar un correo (SDK de Python)

```python
from azure.communication.email import EmailClient

# In dev mode the access key is not validated
client = EmailClient.from_connection_string(
    "endpoint=http://localhost:4577/;accesskey=<any-base64-key>")

poller = client.begin_send({
    "senderAddress": "DoNotReply@example.com",
    "content": {"subject": "Hello", "plainText": "Hi from floci-az!"},
    "recipients": {"to": [{"address": "dev@example.com"}]},
})
result = poller.result()
print(result["id"], result["status"])   # operationId, Succeeded
```

### Enviar con curl

```bash
curl -s -X POST "http://localhost:4577/emails:send?api-version=2023-03-31" \
  -H "Content-Type: application/json" \
  -d '{
    "senderAddress": "DoNotReply@example.com",
    "content": {"subject": "Hello", "plainText": "Hi!"},
    "recipients": {"to": [{"address": "dev@example.com"}]}
  }'
# → 202 Accepted, Operation-Location: .../emails/operations/{operationId}
```

### Inspeccionar el buzón capturado

```bash
# List everything that was "sent"
curl -s "http://localhost:4577/emailMessages"
# → {"value":[{"operationId":"...","status":"Succeeded","subject":"Hello","toCount":1, ...}], "count":1}

# Fetch one message in full (includes the original request body)
curl -s "http://localhost:4577/emailMessages/<operationId>"

# Clear the mailbox between tests
curl -s -X DELETE "http://localhost:4577/emailMessages"
```

Esto facilita comprobar en las pruebas que el código envió el asunto, los destinatarios y el cuerpo
correctos — sin un servidor SMTP ni un recurso ACS real.

---

## Configuración

```yaml
floci-az:
  services:
    email:
      enabled: true
```

| Variable de entorno | Por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_EMAIL_ENABLED` | `true` | Habilita o deshabilita el servicio |

---

## Notas y limitaciones

- **Sin entrega.** Los mensajes se capturan solo en memoria; no se envía nada por SMTP ni ningún
  destinatario externo recibe nada. El buzón no se persiste y se limpia al reiniciar.
- **Las operaciones siempre se completan de inmediato.** No hay demora de transición
  `Running`→`Succeeded` ni simulación de fallos. `Retry-After: 3` aún se devuelve en el `202` para
  coincidir con ACS, pero el primer sondeo ya informa `Succeeded`.
- **Autenticación permisiva.** La access key de la cadena de conexión y el header `Authorization`
  se aceptan pero no se validan (modo de desarrollo), igual que el resto del emulador.
- **Los recursos ARM son solo estado.** `communicationServices`/`emailServices`/`domains` devuelven
  el estado de aprovisionamiento `Succeeded` con propiedades sintetizadas (`hostName`,
  `dataLocation`); la verificación de dominios (registros TXT SPF/DKIM) no se emula.