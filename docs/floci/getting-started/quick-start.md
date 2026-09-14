# Inicio rápido

## Docker Compose

```yaml title="docker-compose.yml"
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - ./data:/app/data
      - /var/run/docker.sock:/var/run/docker.sock  # required for Azure Functions
```

```bash
docker compose up -d
```

Todos los servicios están disponibles de inmediato en `http://localhost:4577`.

!!! tip "¿No usas Azure Functions?"
    Omite el montaje del socket de Docker y define `FLOCI_AZ_SERVICES_FUNCTIONS_ENABLED=false` para una configuración más simple.

## Docker Run

```bash
docker run -d --name floci-az \
  -p 4577:4577 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  floci/floci-az:latest
```

## Verificar que está en ejecución

```bash
curl http://localhost:4577/health
```

## Cadenas de conexión

**Blob / Queue / Table:**

```
DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=<devstoreaccount1-key>;BlobEndpoint=http://localhost:4577/devstoreaccount1;QueueEndpoint=http://localhost:4577/devstoreaccount1-queue;TableEndpoint=http://localhost:4577/devstoreaccount1-table;
```

**App Configuration** — consulta [Configuración de Azure CLI y SDK](azure-setup.md) para conocer el transporte ForceHttp requerido por el SDK de App Config.

## Siguientes pasos

- [Configurar mediante variables de entorno →](../configuration/docker-compose.md)
- [Modos de almacenamiento →](../configuration/storage.md)
- [Referencia de servicios →](../services/index.md)