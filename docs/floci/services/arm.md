# Azure Resource Manager (ARM)

El manejador central del plano de administración. Sirve la superficie genérica de ARM —
suscripciones, grupos de recursos y los listados de recursos/proveedores — que esperan el proveedor
de Terraform `hashicorp/azurerm`, OpenTofu y la CLI de Azure, y actúa como retorno (fallthrough) para
las rutas del plano de administración que no gestiona ningún manejador más específico (AKS, SQL, Redis,
Managed Identity y los demás proveedores `Microsoft.*`).

> **Solo HTTP, sin Docker.** Todo el estado de los recursos ARM está en memoria y es efímero; no persiste
> entre reinicios sea cual sea `storage.mode`, en consonancia con el comportamiento del resto de recursos
> del plano de control.

!!! note "Deshabilitar ARM desactiva el plano de administración"
    `arm.enabled: false` desactiva el enrutado de `/subscriptions` y `/providers`, del que dependen los
    servicios ARM específicos de proveedor (Redis, SQL, AKS, …). Conviene dejarlo habilitado salvo que se
    esté probando deliberadamente una configuración de solo plano de datos.

---

## Características

- **Suscripción** — `GET /subscriptions` (enumeración, usada por `az login`) y
  `GET /subscriptions/{sub}`; una única suscripción fija y un GUID de inquilino
- **Grupos de recursos** — CreateOrUpdate, Get, Delete, List
  (`/subscriptions/{sub}/resourceGroups/{rg}`); acepta tanto `resourceGroups` como la grafía en
  minúsculas `resourcegroups`
- **Cuentas de almacenamiento** — una envoltura ARM que conecta `Microsoft.Storage/storageAccounts` con
  los backends activos de [Blob](blob.md) y [Queue](queue.md), y devuelve la conocida clave de cuenta de desarrollo
- **Key Vaults** — una envoltura ARM para `Microsoft.KeyVault/vaults` cuyo `vaultUri` apunta al manejador
  [Key Vault](key-vault.md) activo
- **Listado de recursos y proveedores** — `GET /subscriptions/{sub}/resources`,
  `GET /subscriptions/{sub}/providers[/{namespace}]` y
  `POST .../{namespace}/checkNameAvailability`
- **Retorno de proveedor (fallthrough)** — las rutas del plano de administración de los proveedores sin un
  manejador dedicado se responden aquí para que las lecturas de Terraform y las búsquedas de dependencias se resuelvan

## Endpoints

```
GET    /subscriptions
GET    /subscriptions/{sub}

PUT    /subscriptions/{sub}/resourceGroups/{rg}
GET    /subscriptions/{sub}/resourceGroups/{rg}
DELETE /subscriptions/{sub}/resourceGroups/{rg}
GET    /subscriptions/{sub}/resourceGroups

GET    /subscriptions/{sub}/resources
GET    /subscriptions/{sub}/providers[/{namespace}]
POST   /subscriptions/{sub}/providers/{namespace}/checkNameAvailability

# ARM shells (bridge to the live data-plane handlers)
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Storage/storageAccounts/{name}
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.KeyVault/vaults/{name}
```

`azurerm_storage_account` y `azurerm_key_vault` se resuelven en esas dos rutas de proveedor; la cuenta de
almacenamiento devuelve la conocida clave de desarrollo y el `vaultUri` del cofre apunta al plano de datos
[Key Vault](key-vault.md) activo.

## Inicio rápido

Apunte Terraform al emulador con un bloque mínimo de proveedor `azurerm` (consulte la
[guía de Terraform](../terraform.md) para la configuración completa de omisión del registro de proveedores):

```hcl
provider "azurerm" {
  features {}
  skip_provider_registration = true
  # metadata_host / endpoints redirected at localhost:4577 — see the Terraform guide
}

resource "azurerm_resource_group" "example" {
  name     = "my-rg"
  location = "eastus"
}
```

O utilícelo directamente:

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/my-rg?api-version=2021-04-01" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus"}'
```

## Configuración

```yaml
floci-az:
  services:
    arm:
      enabled: true
```

| Propiedad | Variable de entorno | Valor por defecto | Descripción |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_ARM_ENABLED` | `true` | Habilita el plano de administración de ARM. Deshabilitarlo desactiva todos los servicios basados en ARM |

Los GUID de suscripción e inquilino son fijos
(`00000000-0000-0000-0000-000000000001` / `00000000-0000-0000-0000-000000000002`) y se comparten con la
emulación de [Entra ID](entra.md) y [Managed Identity](managed-identity.md).

## Desviaciones intencionales

- **Una única suscripción e inquilino fijos** — el emulador no modela varias suscripciones.
- **El estado de ARM está en memoria** — los grupos de recursos y las envolturas ARM no persisten entre reinicios.
- **La eliminación de grupos de recursos no se propaga en cascada** — al eliminar un grupo, los recursos creados
  dentro de él permanecen (siguen apareciendo en `GET .../resources`, de modo que
  `prevent_deletion_if_contains_resources` de Terraform funciona).
- **`checkNameAvailability` siempre reporta `nameAvailable: true`** — el emulador no realiza un seguimiento de
  la unicidad global de los nombres.
- **La autenticación Shared Key / ARM se acepta pero no se verifica** — se honra cualquier token de portador o
  Shared Key, en coherencia con la autenticación de desarrollo permisiva del resto del emulador.