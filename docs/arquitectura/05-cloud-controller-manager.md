# cloud-controller-manager

## Rol

Cuando Kubernetes se despliega en entornos de nube, el **cloud-controller-manager (CCM)** actúa como **puente entre las API de la plataforma de nube y el clúster de Kubernetes**.

Gracias a esta separación, los componentes centrales de Kubernetes pueden funcionar de forma independiente y los proveedores de nube se integran mediante sus propios binarios de controlador de nube (*cloud controller binaries*).

Es decir: el CCM habla con las API del proveedor para gestionar recursos como balanceadores de carga, rutas (routes) y metadatos de nodos.

```mermaid
flowchart LR
    K8S["Componentes centrales de Kubernetes"] <--> CCM["cloud-controller-manager"]
    CCM -->|"Node controller / Route controller / Service controller"| CLOUD["API del proveedor de nube"]
```

## Controladores principales

El CCM contiene un conjunto de controladores específicos del proveedor que aseguran el estado deseado de los componentes ligados a la nube (nodos, balanceadores de carga, almacenamiento, etc.):

### Node controller

- Actualiza la información relacionada con los nodos consultando la API del proveedor.
- Ejemplos: etiquetado y anotaciones de nodos, obtención de hostname, salud de los nodos.

### Route controller

- Configura las **rutas de red** en la plataforma de nube.
- Es lo que permite que los Pods de distintos nodos se comuniquen entre sí.

### Service controller

- Despliega **balanceadores de carga** para los Services de Kubernetes.
- Asigna direcciones IP, entre otras tareas.

## Beneficio arquitectónico

El CCM centraliza **toda** la lógica específica de la nube. Esto mantiene el plano de control de Kubernetes **independiente del proveedor**: el núcleo no necesita conocer los detalles internos de AWS, Azure, GCP u otros.

## Resumen

| Aspecto | Detalle |
| --- | --- |
| Función | Puente entre Kubernetes y el proveedor de nube |
| Controladores | Node controller, Route controller, Service controller |
| Recursos gestionados | Metadatos de nodos, rutas de red, balanceadores de carga |
| Beneficio | Núcleo de Kubernetes independiente del proveedor |