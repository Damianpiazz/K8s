# Azure Kubernetes Service (AKS)

Compatible con el SDK `azure-mgmt-containerservice`, la CLI `az aks`, `azurerm_kubernetes_cluster` de Terraform y cualquier cliente compatible con ARM.

> **Requiere Docker** (en el modo real): cada clúster de AKS se asigna a un contenedor `rancher/k3s`.
> Configure `FLOCI_AZ_SERVICES_AKS_MOCKED=true` para un simulacro ligero que omite Docker por completo.

---

## Características

- **Clústeres** — CreateOrUpdate, Get, Delete, List (por suscripción y por grupo de recursos), UpdateTags
- **Grupos de agentes** — List, Get, CreateOrUpdate, Delete
- **Credenciales** — `listClusterAdminCredential`, `listClusterUserCredential` devuelven un kubeconfig codificado en base64
- **Modo k3s real** — se inicia un contenedor k3s con privilegios por clúster; se extrae y se devuelve un kubeconfig con la AC real
- **Modo simulado** — los clústeres pasan inmediatamente a `Succeeded` con un kubeconfig sintético; no se requiere Docker
- **Nombres basados en instanceId** — cada clúster recibe un prefijo UUID de 8 caracteres, lo que evita colisiones de nombres de contenedor entre grupos de recursos

---

## Endpoints

Todas las operaciones usan rutas ARM:

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters
GET    /subscriptions/{sub}/providers/Microsoft.ContainerService/managedClusters
POST   .../managedClusters/{name}/listClusterAdminCredential
POST   .../managedClusters/{name}/listClusterUserCredential
GET    .../managedClusters/{name}/agentPools
GET    .../managedClusters/{name}/agentPools/{poolName}
PUT    .../managedClusters/{name}/agentPools/{poolName}
DELETE .../managedClusters/{name}/agentPools/{poolName}
```

---

## Inicio rápido

### 1 — Crear un clúster

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerService/managedClusters/my-cluster?api-version=2024-04-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "kubernetesVersion": "1.29",
      "dnsPrefix": "my-cluster-dns",
      "agentPoolProfiles": [
        {
          "name": "nodepool1",
          "count": 1,
          "vmSize": "Standard_DS2_v2",
          "osType": "Linux",
          "mode": "System"
        }
      ]
    }
  }'
```

En el modo real, `provisioningState` es `"Creating"` hasta que k3s está listo (de 30 a 90 s). Consulte con GET hasta que aparezca `"Succeeded"`.
En el modo simulado, la respuesta muestra `"Succeeded"` de inmediato.

### 2 — Consultar (poll) hasta que esté listo (solo en modo real)

```bash
while true; do
  STATE=$(curl -s "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerService/managedClusters/my-cluster?api-version=2024-04-01" \
          | python3 -c "import sys,json; print(json.load(sys.stdin)['properties']['provisioningState'])")
  echo "provisioningState: $STATE"
  [ "$STATE" = "Succeeded" ] && break
  sleep 5
done
```

### 3 — Obtener el kubeconfig

```bash
KUBECONFIG_B64=$(curl -s -X POST \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerService/managedClusters/my-cluster/listClusterAdminCredential?api-version=2024-04-01" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['kubeconfigs'][0]['value'])")

echo "$KUBECONFIG_B64" | base64 -d > ~/.kube/my-cluster.yaml
kubectl --kubeconfig ~/.kube/my-cluster.yaml get nodes
```

En el modo real, el kubeconfig apunta al servidor de API de k3s activo. En el modo simulado, apunta a `https://localhost:6443` con `insecure-skip-tls-verify: true`.

### 4 — Eliminar el clúster

```bash
curl -s -X DELETE \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerService/managedClusters/my-cluster?api-version=2024-04-01"
# returns 202 Accepted; the k3s container and its volume are removed immediately
```

---

## Integración con SDK

=== "Java"

    ```java
    // pom.xml:
    // <dependency>
    //   <groupId>com.azure.resourcemanager</groupId>
    //   <artifactId>azure-resourcemanager-containerservice</artifactId>
    //   <version>2.40.0</version>
    // </dependency>

    import com.azure.core.credential.TokenCredential;
    import com.azure.core.management.AzureEnvironment;
    import com.azure.core.management.profile.AzureProfile;
    import com.azure.identity.DefaultAzureCredentialBuilder;
    import com.azure.resourcemanager.containerservice.ContainerServiceManager;
    import com.azure.resourcemanager.containerservice.models.KubernetesCluster;

    AzureProfile profile = new AzureProfile(
        "tenant-id", "subscription-id", AzureEnvironment.AZURE);
    TokenCredential credential = new DefaultAzureCredentialBuilder()
        .authorityHost("http://localhost:4577/")  // point to floci-az
        .build();

    ContainerServiceManager manager = ContainerServiceManager
        .authenticate(credential, profile);

    KubernetesCluster cluster = manager.kubernetesClusters()
        .define("my-cluster")
        .withRegion("eastus")
        .withExistingResourceGroup("my-rg")
        .withDefaultVersion()
        .withSystemAssignedManagedServiceIdentity()
        .defineAgentPool("nodepool1")
            .withVirtualMachineSize(ContainerServiceVMSizeTypes.STANDARD_DS2_V2)
            .withAgentPoolMode(AgentPoolMode.SYSTEM)
            .withAgentPoolType(AgentPoolType.VIRTUAL_MACHINE_SCALE_SETS)
            .withOSType(OSType.LINUX)
            .withAgentPoolVirtualMachineCount(1)
            .attach()
        .create();
    ```

=== "Python"

    ```python
    from azure.identity import DefaultAzureCredential
    from azure.mgmt.containerservice import ContainerServiceClient
    from azure.mgmt.containerservice.models import (
        ManagedCluster, ManagedClusterAgentPoolProfile, ContainerServiceVMSizeTypes
    )

    credential = DefaultAzureCredential()
    client = ContainerServiceClient(
        credential=credential,
        subscription_id="my-sub",
        base_url="http://localhost:4577",
    )

    poller = client.managed_clusters.begin_create_or_update(
        resource_group_name="my-rg",
        resource_name="my-cluster",
        parameters=ManagedCluster(
            location="eastus",
            kubernetes_version="1.29",
            dns_prefix="my-cluster-dns",
            agent_pool_profiles=[
                ManagedClusterAgentPoolProfile(
                    name="nodepool1",
                    count=1,
                    vm_size=ContainerServiceVMSizeTypes.STANDARD_DS2_V2,
                    mode="System",
                )
            ],
        ),
    )
    cluster = poller.result()
    print(cluster.provisioning_state)
    ```

=== "Azure CLI"

    ```bash
    az aks create \
      --subscription my-sub \
      --resource-group my-rg \
      --name my-cluster \
      --location eastus \
      --node-count 1 \
      --generate-ssh-keys \
      --output table

    az aks get-credentials \
      --subscription my-sub \
      --resource-group my-rg \
      --name my-cluster \
      --file ~/.kube/my-cluster.yaml
    ```

---

## Modo real frente a modo simulado

| | Modo real (`mocked=false`) | Modo simulado (`mocked=true`) |
|---|---|---|
| Requiere Docker | Sí | No |
| `provisioningState` tras la creación | `Creating` → se consulta hasta `Succeeded` | `Succeeded` de inmediato |
| Kubeconfig | Extraído de k3s: AC real, URL real del servidor | Sintético: `insecure-skip-tls-verify: true` |
| Conectividad con `kubectl` | Sí: apunta al servidor de API de k3s activo | No: k3s no está en ejecución |
| Contenedor | `floci-az-aks-{instanceId}` (k3s con privilegios) | Ninguno |
| Caso de uso | Desarrollo local, pruebas de integración | Pruebas unitarias, CI sin Docker |

---

## Configuración

```yaml
floci-az:
  services:
    aks:
      enabled: true
      mocked: false             # true = no Docker; false = real k3s (default)
      default-image: "rancher/k3s:latest"
      api-server-base-port: 6443
      api-server-max-port: 7443
      keep-running-on-shutdown: false
```

| Variable de entorno | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_AKS_ENABLED` | `true` | Habilitar o deshabilitar el servicio de AKS |
| `FLOCI_AZ_SERVICES_AKS_MOCKED` | `false` | `true` = omitir Docker, kubeconfig sintético |
| `FLOCI_AZ_SERVICES_AKS_DEFAULT_IMAGE` | `rancher/k3s:latest` | Imagen Docker de k3s |
| `FLOCI_AZ_SERVICES_AKS_API_SERVER_BASE_PORT` | `6443` | Inicio del rango de puertos host para los servidores de API de k3s |
| `FLOCI_AZ_SERVICES_AKS_API_SERVER_MAX_PORT` | `7443` | Fin del rango de puertos host para los servidores de API de k3s |
| `FLOCI_AZ_SERVICES_AKS_KEEP_RUNNING_ON_SHUTDOWN` | `false` | Mantener los contenedores de k3s en ejecución cuando floci-az se detiene |

---

## Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
      - "6443-6450:6443-6450"   # k3s API server ports (one per cluster)
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock   # required for real k3s mode
    environment:
      FLOCI_AZ_SERVICES_AKS_MOCKED: "false"
      # k3s containers bind to host ports 6443–7443 via Docker daemon.
      # Publish the range you need above.
```

> **Puertos de los sidecars:** los contenedores de k3s enlazan un puerto host dentro del rango `6443–7443`
> directamente a través del demonio Docker. Publique el rango de puertos en el servicio `floci-az` si su
> aplicación necesita alcanzar el servidor de API de k3s desde fuera de Docker.

---

## Arquitectura

```
┌──────────────────────────────────────────────────────────────┐
│  Your App                                                    │
│                                                              │
│  ARM REST calls ──────► floci-az :4577 ──► AksHandler        │
│  (create cluster,                         (state, routing)   │
│   list clusters,                                             │
│   get credentials)                                           │
│                                                              │
│  kubectl / k8s client ────────────────────────────────────►  │
│                               k3s container :6443            │
│                               (floci-az-aks-{instanceId})    │
└──────────────────────────────────────────────────────────────┘
```

El plano de administración (API de ARM) pasa por floci-az en el puerto 4577.
El plano de datos (kubectl, API de Kubernetes) se conecta **directamente** al contenedor de k3s en su puerto asignado: floci-az no forma parte de la ruta de datos.