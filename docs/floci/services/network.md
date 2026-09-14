# Azure Virtual Network

Compatible con clientes que hablan ARM, el proveedor AzureRM de Terraform y los recursos de OpenTofu que
aprovisionan dependencias básicas de `Microsoft.Network` para flujos locales de VM.

> **No requiere Docker.** Network es un emulador ARM del plano de control en proceso. Almacena el estado de los recursos
> y devuelve respuestas con forma de Azure, pero no crea enrutamiento real, aislamiento de paquetes, aplicación de
> firewall ni asignación de IP.

---

## Características

- **Redes virtuales** — CreateOrUpdate, Get, Delete y List por grupo de recursos
- **Subredes** — CreateOrUpdate, Get, Delete y List bajo una red virtual
- **Interfaces de red** — CreateOrUpdate, Get, Delete y List por grupo de recursos
- **Direcciones IP públicas** — CreateOrUpdate, Get, Delete y List por grupo de recursos
- **Grupos de seguridad de red** — CreateOrUpdate, Get, Delete y List por grupo de recursos; `securityRules` en línea y
  autónomas (endpoint secundario) con IDs de regla sintetizados, más los seis `defaultSecurityRules` reales de Azure
- **Balanceadores de carga** — CreateOrUpdate, Get, Delete y List; el `sku` de nivel superior viaja de ida y vuelta,
  las configuraciones de IP de frontend obtienen IDs e IP privadas sintetizados, y los `backendAddressPools` se gestionan
  mediante el endpoint secundario dedicado (`azurerm_lb_backend_address_pool`)
- **Application gateways** — CreateOrUpdate, Get, Delete y List; los sub-recursos secundarios (listeners, puertos, pools,
  reglas de enrutamiento, …) se reflejan con IDs ARM bien formados (sintetizados cuando faltan) y el gateway informa
  `operationalState = "Running"`
- **Zonas DNS privadas** — CreateOrUpdate, Get, Delete y List, con recordsets (A, AAAA, CNAME, MX, PTR, SOA, SRV, TXT);
  se siembra un record set SOA por defecto al crear, se rastrean los contadores de registros/enlaces y se aplica la
  concurrencia de ETag (`If-Match` / `If-None-Match`)
- **Enlaces de red virtual de DNS privado** — CreateOrUpdate, Get, Delete y List bajo una zona DNS privada; los enlaces
  informan `virtualNetworkLinkState = "Completed"`
- **Private endpoints** — CreateOrUpdate, Get, Delete y List; las `privateLinkServiceConnections` se aprueban
  automáticamente, se crea una interfaz de red de respaldo con una IP privada sintetizada y se admiten
  `privateDnsZoneGroups` anidados
- **Servicios de Private Link** — CreateOrUpdate, Get, Delete y List, con un `alias` sintetizado
- **Compatibilidad con Terraform/OpenTofu** — admite los recursos de Network necesarios para `azurerm_linux_virtual_machine`,
  `azurerm_private_dns_zone`, `azurerm_private_dns_zone_virtual_network_link`, `azurerm_private_endpoint`,
  `azurerm_network_security_group` / `azurerm_network_security_rule`, `azurerm_lb` (+ backend pool, probe, rule) y
  `azurerm_application_gateway`
- **Listado de grupos de recursos** — los recursos de Network aparecen en los listados de recursos ARM de los grupos de
  recursos

Los recursos creados devuelven `properties.provisioningState = "Succeeded"`. Las NIC sintetizan una IP privada
dinámica (`10.0.0.4`) cuando no se proporciona ninguna, y los recursos de IP pública sintetizan una IP pública dinámica
(`20.0.0.4`) cuando no se proporciona ninguna dirección. Crear un private endpoint también sintetiza una interfaz de red de
respaldo con una IP privada `10.0.0.4`.

---

## Endpoints

Todas las operaciones usan rutas ARM:

```text
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks/{name}

GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks/{vnet}/subnets
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks/{vnet}/subnets/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks/{vnet}/subnets/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks/{vnet}/subnets/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/networkInterfaces/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/networkInterfaces/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/networkInterfaces/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/publicIPAddresses/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/publicIPAddresses/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/publicIPAddresses/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/networkSecurityGroups/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/networkSecurityGroups/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/networkSecurityGroups/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}/{recordType}/{record}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}/recordsets
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}/virtualNetworkLinks/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}/virtualNetworkLinks/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}/virtualNetworkLinks/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateEndpoints/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateEndpoints/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateEndpoints/{name}
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateEndpoints/{pe}/privateDnsZoneGroups/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateEndpoints/{pe}/privateDnsZoneGroups/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateEndpoints/{pe}/privateDnsZoneGroups/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateLinkServices/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateLinkServices/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateLinkServices/{name}
```

---

## Terraform y OpenTofu

Las suites de compatibilidad ejercitan Network a través de la misma ruta del emulador local que se usa en CI:

```bash
make test-terraform-compat
make test-opentofu-compat
```

Si el puerto `4577` ya está en uso, ejecuta las suites contra otro puerto del emulador:

```bash
make PORT=4578 test-terraform
make PORT=4578 test-opentofu
```

La cobertura de Network se encuentra en:

- `compatibility-tests/compat-terraform`
- `compatibility-tests/compat-opentofu`

El alcance actual de Network es suficiente para que Terraform/OpenTofu creen y destruyan un grupo de recursos con VNet,
subred, NIC, IP pública, NSG, una VM que referencia la NIC y un stack de Private Link (zona DNS privada + enlace de red
virtual + private endpoint con un grupo de zonas DNS privadas).

---

## Configuración

```yaml
floci-az:
  services:
    network:
      enabled: true       # Microsoft.Network — VNet, subnets, NIC, public IP, NSG, and DNS zones
    arm:
      enabled: true       # central management plane; disabling it turns OFF all ARM-based services
```

| Variable de entorno | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_NETWORK_ENABLED` | `true` | Habilita/deshabilita todo Microsoft.Network (VNet, subredes, NIC, IP pública, NSG **y zonas DNS**). Cuando está deshabilitado, las llamadas a `/providers/Microsoft.Network/...` devuelven `404 ResourceNotFound`; el resto de ARM sigue funcionando. |
| `FLOCI_AZ_SERVICES_ARM_ENABLED` | `true` | Habilita/deshabilita el propio plano de administración ARM (`/providers`, `/subscriptions`, grupos de recursos). **Deshabilitarlo apaga todos los servicios basados en ARM** (vm, aks, sql, redis, acr, servicebus, apim, monitor, network, storage/keyvault ARM) — úsalo solo para apagar por completo el plano de administración. |

## Alcance y limitaciones

- Sin networking L2/L3 real, ni enrutamiento, peering, DNS, reenvío de paquetes ni service endpoints
- Sin aplicación de reglas NSG; los recursos NSG (incluidas las reglas por defecto) solo se almacenan como estado ARM
- Sin comportamiento de tabla de rutas ni NAT gateway; los balanceadores de carga y los application gateways son solo
  síntesis de estado ARM — no se balancea ni se enruta tráfico
- Los private endpoints y las zonas DNS privadas son solo estado ARM — se crea una NIC de respaldo con un `10.0.0.4`
  sintetizado y las conexiones se aprueban automáticamente, pero no hay tráfico de Private Link real, ni registro de
  nombres ni resolución DNS contra los registros de la zona privada
- Sin gestión real de direcciones IP; las IP privadas y públicas por defecto se sintetizan para la compatibilidad de
  SDK y proveedores
- Las eliminaciones son solo de estado; eliminar una VNet también elimina sus subredes secundarias del almacén en memoria

El objetivo es la paridad de API para los flujos de aprovisionamiento locales, especialmente los flujos de SDK, Azure CLI,
Terraform y OpenTofu que necesitan dependencias de Network antes de crear otros recursos de Azure.