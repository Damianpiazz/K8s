# Setup: cómo crear un clúster de Kubernetes

## Introducción

Hay múltiples formas de crear un clúster de Kubernetes. La elección depende del objetivo: **desarrollo local**, **pruebas**, o **producción**. Todas dan como resultado el mismo plano de control y nodos trabajadores descritos en [arquitectura](../arquitectura/README.md), pero varían el nivel de control, la complejidad y el modo de administración.

## 1. Clústeres de desarrollo local

Ideales para aprender y probar: aportan un clúster rápido en una sola máquina.

### minikube

- Ejecuta un clúster de un solo nodo dentro de una máquina virtual (virtualbox, hyperv, driver de Docker).
- Trae addons listos (dashboard, metalLB, ingress, metrics-server, registry).
- Muy usado para desarrollo y para las certificaciones CKA/CKAD.

```text
minikube start
kubectl get nodes
minikube dashboard        # abre el dashboard web
```

### kind (Kubernetes IN Docker)

- Crea clústeres **multi-nodo** donde cada nodo (control plane y worker) es un **contenedor Docker**.
- Muy rápido, pensado para CI y para probar configuraciones multi-nodo localmente.

```text
kind create cluster --name demo
kubectl cluster-info
```

### k3s

- Distribución ligera de Kubernetes certificada (de Rancher).
- Un solo binario, bajo consumo; sirve tanto para desarrollo como para **edge/IoT** y nodos pequeños.
- Muy usado en el ejemplo de self-host de LLM visto en [casos-de-uso/ia-ml.md](../casos-de-uso/ia-ml.md) (clúster k3d).

```text
curl -sfL https://get.k3s.io | sh -
kubectl get nodes
```

## 2. Clústeres de producción: instalación manual

### kubeadm

- Es la herramienta de **bootstrap** oficial de Kubernetes para clústeres de producción.
- Inicializa el **plano de control** y genera los **certificados**, `kubeconfig`, `etcd` y los **Static Pods** de los componentes del control plane (ver [02-etcd.md](../arquitectura/02-etcd.md) — etcd corre como Static Pod en kubeadm).
- Permite construir clústeres **multi-nodo** y de **alta disponibilidad** (varios control planes con un balanceador).

```text
# Nodo control plane (primer nodo)
kubeadm init --apiserver-advertise-address=<IP>
kubectl apply -f <CNI>        # instalar la red (Calico, Cilium, Flannel)

# Nodos worker: unirse al clúster con el token
kubeadm join <control-plane-ip>:6443 --token <token> --discovery-token-ca-cert-hash <hash>
```

> "Kubernetes the Hard Way" de Kelsey Hightower es el ejercicio didáctico por excelencia para entender cada componente del setup manual, sin kubeadm.

## 3. Producción: clústeres gestionados (managed) en la nube

Los proveedores de nube ofrecen **Kubernetes como servicio gestionado**: el plano de control, la alta disponibilidad y el `etcd` los administra el proveedor. El usuario solo gestiona los **nodos trabajadores** y las aplicaciones.

| Plataforma | Servicio gestionado | Nota |
| --- | --- | --- |
| AWS | **EKS** (Elastic Kubernetes Service) | Control plane gestionado; node groups de EC2 o Fargate |
| Google Cloud | **GKE** (Google Kubernetes Engine) | Autopilot (sin gestión de nodos) o Standard |
| Azure | **AKS** (Azure Kubernetes Service) | Nodos en Azure VMs, integración con servicios de Azure |
| DigitalOcean | **DOKS** | K8s gestionado simple |
| Linode/Vultr | LKE / VKE | K8s gestionado accesible |

Ventajas de los gestionados:

- **Control plane fuera del alcance** del usuario: no hay que administrar `kube-apiserver`, `etcd`, scheduler ni controller-manager.
- **Alta disponibilidad** del plano de control incluida.
- Integración nativa con el `cloud-controller-manager` (ver [05-cloud-controller-manager.md](../arquitectura/05-cloud-controller-manager.md)): balanceadores, storage y nodos se gestionan desde la nube.

Los ejemplos de `pulumi/examples` (vistos en [casos-de-uso](../casos-de-uso/README.md)) despliegan estos clústeres gestionados como **infraestructura como código**: `aws-*-eks`, `gcp-*-gke`, `azure-*-aks`.

## 4. Otros métodos relevantes

| Método | Uso |
| --- | --- |
| **K3d** | kind + k3s: clúster ligero multi-nodo en Docker, común en desarrollo local |
| **MicroK8s** | K8s ligero de Canonical para desarrollo y edge |
| **k3s** | Edge/IoT y producción ligera |
| **KubeVirt** | Ejecutar VMs dentro de Kubernetes (no es un "setup" de clúster sino extensión) |

## Consideraciones al elegir

| Factor | Desarrollo local | kubeadm (producción) | Managed (nube) |
| --- | --- | --- | --- |
| Control sobre el plano de control | Total | Total | Ninguno (lo gestiona el proveedor) |
| Esfuerzo de mantenimiento | Bajo | Alto | Bajo |
| Costo | Nulo | Infraestructura propia | Consumo del proveedor |
| Uso típico | Aprender, probar | On-premise, control total | Producción en nube |

## Resumen

| Método | Tipo | Mejor para |
| --- | --- | --- |
| minikube / kind / k3s / k3d | Local | Aprender, desarrollo, CI |
| kubeadm | Producción self-hosted | On-premise, control total |
| EKS / GKE / AKS / DOKS | Managed | Producción en nube sin administrar el plano de control |

**Siguiente**: [02-herramientas-cluster.md](02-herramientas-cluster.md) — cómo administrar el clúster una vez creado.