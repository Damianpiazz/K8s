# Microsoft Graph

Una porción limitada de Microsoft Graph en `/v1.0/...`, añadida junto con la
[fase 2 de Entra ID](entra.md) ([#120](https://github.com/floci-io/floci-az/issues/120)):
descubrimiento de service principals (usado por el provider azurerm) y administración de
pertenencia a grupos — no es un emulador de Graph de propósito general. Los datos del directorio
(usuarios, grupos, pertenencia) se comparten con [Microsoft Entra ID](entra.md): el reclamo `oid`
de un token y una búsqueda en Graph resuelven a la misma identidad del directorio, y la
[semilla de desarrollo](entra.md#inquilino-por-defecto-y-credenciales-de-desarrollo) (usuario de
desarrollo + grupo de desarrollo) también está disponible aquí.

## Endpoints

| Ruta | Propósito |
|---|---|
| `GET /v1.0/servicePrincipals?$filter=appId eq '{id}'` | Descubrimiento de service principals (bootstrap del provider azurerm) |
| `POST /v1.0/users/{id}/getMemberGroups` | Ids de objeto de los grupos a los que pertenece directamente un usuario |
| `POST /v1.0/groups/{id}/members/$ref` | Agrega un miembro a un grupo |
| `DELETE /v1.0/groups/{id}/members/{id}/$ref` | Elimina un miembro de un grupo |

`{id}` en `users/{id}` acepta tanto el id de objeto del usuario como su userPrincipalName, tal como
hace el Graph real. Solo se modela la pertenencia **directa** — no hay transitividad de grupos
anidados.

### `getMemberGroups`

```bash
curl -s -X POST http://localhost:4577/v1.0/users/dev-user@floci-az.local/getMemberGroups \
  -H "Content-Type: application/json" \
  -d '{"securityEnabledOnly": false}'
```

```json
{
  "@odata.context": "https://graph.microsoft.com/v1.0/$metadata#Collection(Edm.String)",
  "value": ["44444444-4444-4444-4444-444444444444"]
}
```

`securityEnabledOnly: true` filtra el resultado a los grupos con `securityEnabled: true` (el grupo
de desarrollo sembrado es security-enabled). Un id de usuario/UPN que no resuelve a un usuario del
directorio devuelve `404 Request_ResourceNotFound`.

### `members/$ref`

El `@odata.id` del cuerpo de la petición apunta al Graph *real*
(`https://graph.microsoft.com/v1.0/directoryObjects/{id}`) — el emulador analiza el id final en
lugar de validar el host, ya que esa forma de URL es la que los SDK/tools envían sin importar a qué
endpoint de Graph apunten:

```bash
curl -s -X POST http://localhost:4577/v1.0/groups/44444444-4444-4444-4444-444444444444/members/\$ref \
  -H "Content-Type: application/json" \
  -d '{"@odata.id": "https://graph.microsoft.com/v1.0/directoryObjects/<member-object-id>"}'

curl -s -X DELETE \
  http://localhost:4577/v1.0/groups/44444444-4444-4444-4444-444444444444/members/<member-object-id>/\$ref
```

Ambos devuelven `204 No Content` en caso de éxito; un id de grupo que no existe devuelve
`404 Request_ResourceNotFound`.

## Configuración

```yaml
floci-az:
  services:
    graph:
      enabled: true   # Microsoft Graph slice at /v1.0/...
```

| Parámetro | Variable de entorno | Por defecto |
|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_GRAPH_ENABLED` | `true` |

## Fuera de alcance

El CRUD completo de Graph (applications, service principals más allá del descubrimiento, roles de
directorio, conditional access, ...) y la administración del registro de aplicaciones siguen
pendientes ([#23](https://github.com/floci-io/floci-az/issues/23)).