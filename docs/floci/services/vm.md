# Azure Virtual Machines (VM)

Compatible con el SDK `azure-mgmt-compute`, la CLI `az vm`, el `azurerm_linux_virtual_machine` de Terraform
y cualquier cliente que hable ARM.

> **Modo simulado (por defecto): sin Docker.** Las VM se emulan como recursos ARM de solo plano de
> control: se aprovisionan al instante e informan el estado de energía.
>
> **Modo con respaldo de contenedor:** establece `FLOCI_AZ_SERVICES_VM_MOCKED=false` para respaldar cada VM con un
> contenedor Linux real. La imagen se resuelve desde `storageProfile.imageReference` (con respaldo a
> `ubuntu:22.04`), y las acciones de energía se mapean a Docker: `start` → iniciar, `powerOff`/`deallocate` →
> detener, `restart` → reiniciar, eliminar → quitar. Las VM se aprovisionan de forma asíncrona (`Creating` →
> `Succeeded` una vez que el contenedor se está ejecutando).

---

## Características

- **Ciclo de vida** — CreateOrUpdate, Get, Delete, List (por suscripción y por grupo de recursos), UpdateTags
- **Acciones de energía** — `start`, `powerOff`, `deallocate`, `restart`, `redeploy`, `reapply`
- **instanceView** — informa los estados `ProvisioningState/*` y `PowerState/*`
- **Integración de redes** — los recursos ARM de VM pueden hacer referencia a interfaces de red de `Microsoft.Network`.
  El emulador de Network provee los recursos ARM de VNet, subred, NIC, IP pública y NSG
  para que `azurerm_linux_virtual_machine` y sus dependencias se apliquen de extremo a extremo.
- **Operaciones de larga duración** — las acciones de energía devuelven `202` con un encabezado `Azure-AsyncOperation`
  que apunta a un endpoint de estado de la operación, de modo que los sondeos de los SDK se completan limpiamente.

---

## Endpoints

Todas las operaciones usan rutas ARM:

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines/{name}
GET    .../virtualMachines/{name}/instanceView
POST   .../virtualMachines/{name}/{start|powerOff|deallocate|restart|redeploy|reapply}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines
GET    /subscriptions/{sub}/providers/Microsoft.Compute/virtualMachines

# Network resources are handled by the Microsoft.Network emulator.
```

Consulta [Azure Virtual Network](network.md) para el alcance de VNet, subred, NIC, IP pública y NSG.

Use `?$expand=instanceView` en una operación Get para incrustar la vista de instancia bajo `properties.instanceView`.

---

## Inicio rápido

### 1 — Crear una VM

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.Compute/virtualMachines/my-vm?api-version=2024-11-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "hardwareProfile": {"vmSize": "Standard_B1s"},
      "storageProfile": {
        "imageReference": {
          "publisher": "Canonical",
          "offer": "0001-com-ubuntu-server-jammy",
          "sku": "22_04-lts",
          "version": "latest"
        },
        "osDisk": {"createOption": "FromImage", "caching": "ReadWrite"}
      },
      "osProfile": {"adminUsername": "azureuser", "computerName": "my-vm"},
      "networkProfile": {"networkInterfaces": [{"id": ".../networkInterfaces/my-nic"}]}
    }
  }'
```

La VM se devuelve con `properties.provisioningState = "Succeeded"` e inicia en el
estado `PowerState/running`.

### 2 — Acciones de energía

```bash
BASE="http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.Compute/virtualMachines/my-vm"
curl -s -X POST "$BASE/powerOff?api-version=2024-11-01"     # -> PowerState/stopped
curl -s -X POST "$BASE/start?api-version=2024-11-01"        # -> PowerState/running
curl -s -X POST "$BASE/deallocate?api-version=2024-11-01"   # -> PowerState/deallocated
```

### 3 — Leer el estado de energía

```bash
curl -s "$BASE/instanceView?api-version=2024-11-01"
# { "computerName": "my-vm", "osName": "Linux",
#   "statuses": [ {"code":"ProvisioningState/succeeded",...}, {"code":"PowerState/running",...} ] }
```

---

## Configuración

```yaml
floci-az:
  services:
    vm:
      enabled: true
      mocked: true              # true = no Docker, pure ARM state. false = container-backed
      default-image: "ubuntu:22.04"
```

| Variable de entorno | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_VM_ENABLED` | `true` | Habilitar/deshabilitar el servicio |
| `FLOCI_AZ_SERVICES_VM_MOCKED` | `true` | Modo simulado (sin Docker) |
| `FLOCI_AZ_SERVICES_VM_DEFAULT_IMAGE` | `ubuntu:22.04` | Imagen Docker de respaldo para referencias de imagen no resueltas |

---

## Notas y limitaciones

- El modo simulado no ejecuta un SO real — no hay SSH, ni agente invitado, y `runCommand` no se ejecuta.
- El modo con respaldo de contenedor (`mocked=false`) ejecuta una imagen base estándar mantenida viva con `tail -f /dev/null`;
  es un contenedor Linux real, no una VM/hipervisor de verdad — no hay kernel separado, ni cloud-init,
  y `osProfile.customData` / la inyección de claves SSH no se aplican.
- Las shells de dependencias de red reflejan las propiedades enviadas con `provisioningState = "Succeeded"`;
  las IP privadas de NIC y las IP públicas se sintetizan, no se asignan desde un pool de direcciones real.