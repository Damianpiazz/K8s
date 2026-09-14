# Terraform / OpenTofu (azurerm provider)

Puedes ejecutar el proveedor `hashicorp/azurerm` contra floci-az para IaC local-first —
`plan` / `apply` / `destroy` contra el emulador en lugar de Azure real.

> **Se requiere TLS.** El proveedor de azurerm descubre la nube a través de **HTTPS**
> (`GET https://<host>/metadata/endpoints`). floci-az sirve HTTP plano por defecto, por lo que
> **debes** habilitar TLS o el proveedor falla antes de enviar una sola solicitud de recursos. Este es
> el error de configuración más común (consulta [Solución de problemas](#solución-de-problemas)).

---

## 1 — Iniciar floci-az con TLS habilitado

```yaml
# docker-compose.yml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock   # for container-backed services (SQL, Postgres, …)
      - ./data:/app/data                            # persist the generated cert across restarts
    environment:
      FLOCI_AZ_TLS_ENABLED: "true"
```

floci-az sirve HTTP y HTTPS en el **mismo** puerto (4577) mediante un proxy de detección de protocolo.
En el arranque se genera un certificado autofirmado que se sirve en `GET /_floci/tls-cert`.

## 2 — Confiar en el certificado

El proveedor valida la cadena TLS, por lo que la máquina que ejecuta `tofu`/`terraform` debe
confiar en el certificado autofirmado:

```bash
curl -sf http://localhost:4577/_floci/tls-cert -o floci-az.crt
# Linux: copy into the system trust store, then refresh
sudo cp floci-az.crt /usr/local/share/ca-certificates/ && sudo update-ca-certificates
```

(Según el almacén de confianza de tu sistema operativo; en un runner de CI normalmente se instala de la misma manera.)

## 3 — Configurar el proveedor

```hcl
terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = ">= 3.0"   # works with the 3.x and 4.x provider lines
    }
  }
}

provider "azurerm" {
  features {}
  skip_provider_registration = true
  use_cli                    = false

  environment   = "stack"            # use a custom cloud described by metadata_host
  metadata_host = "localhost:4577"   # floci-az serves /metadata/endpoints over HTTPS here

  subscription_id = "00000000-0000-0000-0000-000000000001"
  tenant_id       = "00000000-0000-0000-0000-000000000002"
  client_id       = "00000000-0000-0000-0000-000000000003"
  client_secret   = "fake-secret"    # credentials are not validated in dev auth mode
}
```

Luego funciona el flujo habitual:

```bash
tofu init
tofu apply
tofu destroy
```

Consulta el directorio `compatibility-tests/compat-opentofu` del repositorio para ver un ejemplo
completo y verificado por CI (grupo de recursos, almacenamiento, Key Vault, VNet, VM, Redis, ACR, PostgreSQL Flexible Server).

---

## Solución de problemas

**`http: server gave HTTP response to HTTPS client`** (o `tls: first record does not look
like a TLS handshake`) cuando el proveedor se configura:

```
Configuring cloud environment from Metadata Service at localhost:4577
```

→ TLS no está habilitado. floci-az responde a la sonda de metadatos HTTPS del proveedor con HTTP plano.
**Solución:** define `FLOCI_AZ_TLS_ENABLED=true`, reinicia y confía en el certificado (pasos 1–2 anteriores).

**`x509: certificate signed by unknown authority`**

→ TLS está habilitado, pero la máquina que ejecuta el proveedor no confía en el certificado
autofirmado. Repite el paso 2, o apunta `SSL_CERT_FILE` al `floci-az.crt` descargado.

**`GET /_floci/tls-cert` devuelve `"tlsEnabled": false`**

→ Confirma que TLS está desactivado. Actívalo como en el paso 1.