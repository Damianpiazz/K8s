# Configuración de Azure CLI y SDK

## Wrapper de CLI azfloci

La herramienta `azfloci` es una CLI Python complementaria que actúa como proxy transparente para la CLI oficial de Azure (`az`).

### Configuración

```bash
# Optional: alias azfloci as az for a seamless experience
alias az='python3 /path/to/floci-az/azfloci/azfloci.py'

# Initialize or get connection string info
az setup
```

## Azure CLI

Si prefieres usar la CLI `az` estándar sin el wrapper, debes proporcionar la cadena de conexión en cada comando:

```bash
az storage container create --name mycontainer --connection-string "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=<devstoreaccount1-key>;BlobEndpoint=http://localhost:4577/devstoreaccount1;"
```

## SDKs

Floci-AZ es compatible con los SDK oficiales de Azure. Usa la cadena de conexión de desarrollo estándar:

```
DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=<devstoreaccount1-key>;BlobEndpoint=http://localhost:4577/devstoreaccount1;QueueEndpoint=http://localhost:4577/devstoreaccount1-queue;TableEndpoint=http://localhost:4577/devstoreaccount1-table;
```

### Enrutamiento de estilo path

Floci-AZ usa enrutamiento de estilo path:

| Servicio | Endpoint |
|---|---|
| Blob | `http://localhost:4577/{accountName}` |
| Queue | `http://localhost:4577/{accountName}-queue` |
| Table | `http://localhost:4577/{accountName}-table` |
| Functions | `http://localhost:4577/{accountName}-functions` |
| App Configuration | `http://localhost:4577/{accountName}-appconfig` |

### App Configuration

El SDK de App Configuration exige un endpoint HTTPS en su cadena de conexión. Usa un wrapper de transporte `ForceHttp` para redirigir el tráfico hacia el emulador local:

=== "Python"

    ```python
    from azure.appconfiguration import AzureAppConfigurationClient
    from azure.core.pipeline.transport import RequestsTransport

    class ForceHttpTransport(RequestsTransport):
        def send(self, request, **kwargs):
            request.url = request.url.replace("https://", "http://", 1)
            return super().send(request, **kwargs)

    conn_str = "Endpoint=https://localhost:4577/devstoreaccount1-appconfig;Id=devstoreaccount1;Secret=placeholder"
    client = AzureAppConfigurationClient.from_connection_string(conn_str, transport=ForceHttpTransport())
    ```

=== "Java"

    ```java
    static class ForceHttpPolicy implements HttpPipelinePolicy {
        @Override
        public Mono<HttpResponse> process(HttpPipelineCallContext ctx, HttpPipelineNextPolicy next) {
            try {
                URL url = new URL(ctx.getHttpRequest().getUrl().toString());
                ctx.getHttpRequest().setUrl(new URL("http", url.getHost(), url.getPort(), url.getFile()).toString());
            } catch (Exception ignored) {}
            return next.process();
        }
    }

    String connStr = "Endpoint=https://localhost:4577/devstoreaccount1-appconfig;Id=devstoreaccount1;Secret=placeholder";
    ConfigurationClient client = new ConfigurationClientBuilder()
            .connectionString(connStr)
            .addPolicy(new ForceHttpPolicy())
            .buildClient();
    ```

Consulta la [página del servicio App Configuration](../services/app-config.md) para ver ejemplos completos de SDK.