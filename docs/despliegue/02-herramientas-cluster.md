# Herramientas de administración del clúster

## Introducción

Una vez creado el clúster, se administra principalmente con **kubectl** y el archivo **kubeconfig**. Esta sección describe las herramientas base y cómo se asocian con el ecosistema.

## kubectl

`kubectl` es el **cliente de línea de comandos** que se comunica con el `kube-apiserver` por REST/TLS (ver [01-kube-apiserver.md](../arquitectura/01-kube-apiserver.md)). Toda la gestión de objetos pasa por él.

Su referencia completa está en [docs/comandos](../comandos/README.md).

## kubeconfig

El archivo **kubeconfig** (`~/.kube/config` por defecto) almacena los clústeres, credenciales y contextos (ver [17-kubeconfig.md](../arquitectura/17-kubeconfig.md)). Permite conmutar entre clústeres:

```text
kubectl config get-contexts                     # lista contextos
kubectl config use-context <context>            # cambia de contexto
kubectl config set-context --current --namespace=<ns>   # namespace por defecto
```

`kubectl` busca `KUBECONFIG` (variable de entorno) o `~/.kube/config`.

## Otras herramientas de administración

| Herramienta | Rol |
| --- | --- |
| **`kubectx` / `kubens`** | Atajos para conmutar contextos y namespaces rápidamente |
| **`k9s`** | Terminal UI (TUI) para navegar y gestionar recursos del clúster |
| **`kubectl` plugins (krew)** | Extensión de kubectl con plugins de la comunidad (ver `kubectl krew`) |
| **`stern`** | Tail de logs de múltiples Pods a la vez |

## Gestión con infraestructura como código (IaC)

El clúster y sus recursos pueden gestionarse de forma **declarativa y reproducible** con herramientas IaC:

| Herramienta | Qué gestiona | Nota |
| --- | --- | --- |
| **Pulumi** | El clúster y los recursos de Kubernetes | Usa lenguajes de programación (TS, Python, Go); ejemplos en [casos de uso](../casos-de-uso/README.md) |
| **Terraform** | Infraestructura de nube + clúster gestionado | Provisiona EKS/GKE/AKS y luego aplica los manifiestos |
| **Crossplane** | Control planes de proveedores en el clúster | Managed infra como recursos de Kubernetes |

> El patrón típico: **Terraform/Pulumi** crea el clúster (infraestructura), y luego **Helm/Kustomize/GitOps** despliegan las aplicaciones dentro (ver [04-helm-y-kustomize.md](04-helm-y-kustomize.md) y [05-gitops.md](05-gitops.md)).

## Operación del plano de control

Para producción (especialmente kubeadm self-hosted) hay herramientas específicas del plano de control:

| Herramienta | Uso |
| --- | --- |
| **`etcdctl`** | Backup/restore y operación de `etcd` |
| **`kubeadm upgrade`** | Actualizar el clúster entre versiones |
| **`crictl`** | Inspeccionar contenedores vía CRI (relacionado con [08-container-runtime.md](../arquitectura/08-container-runtime.md)) |

## Resumen

| Herramienta | Rol |
| --- | --- |
| `kubectl` | Cliente principal de la API |
| kubeconfig | Configuración de clústeres/contextos/credenciales |
| kubectx / kubens / k9s / stern | Productividad y operación diaria |
| Pulumi / Terraform | Infraestructura como código (crea el clúster) |
| etcdctl / kubeadm upgrade / crictl | Operación del plano de control |

**Siguiente**: [03-despliegue-aplicaciones.md](03-despliegue-aplicaciones.md) — cómo publicar aplicaciones en el clúster.