# Casos de uso de Kubernetes

## Introducción

Kubernetes no es solo un orquestador de contenedores: es la plataforma base para ejecutar **cualquier carga de trabajo contenedorizada** de manera reproducible, escalable y automatizada. Esta sección documenta casos de uso concretos, basados en ejemplos reales de la comunidad:

- [kubernetes/examples](https://github.com/kubernetes/examples) — ejemplos oficiales del proyecto Kubernetes.
- [pulumi/examples](https://github.com/pulumi/examples) — ejemplos de infraestructura como código (Pulumi + `@pulumi/kubernetes`).

Cada documento describe: el problema que resuelve, los componentes de Kubernetes que intervienen, la arquitectura de referencia, y consideraciones de diseño. Se usan bloques de código y diagramas para ilustrar los patrones.

## Catálogo de casos de uso

| Documento | Categoría | Problema que resuelve |
| --- | --- | --- |
| [microservicios.md](microservicios.md) | Microservicios | Orquestar múltiples servicios que se comunican entre sí, con balanceo y escalado |
| [ia-ml.md](ia-ml.md) | IA / Machine Learning | Inferencia de LLMs, serving de modelos, scheduling de GPUs |
| [bases-de-datos.md](bases-de-datos.md) | Bases de datos | Ejecutar BBDD con estado: identidad estable y almacenamiento persistente |
| [web-y-cms.md](web-y-cms.md) | Web / CMS | Aplicaciones web multicapa: frontend, backend, bases de datos |
| [ci-cd.md](ci-cd.md) | CI/CD | Ejecutar pipelines de integración/despliegue continuo dentro del clúster |
| [observabilidad.md](observabilidad.md) | Observabilidad | Monitoreo, métricas y despliegues escalonados con validación |
| [batch-y-almacenamiento.md](batch-y-almacenamiento.md) | Batch / Almacenamiento | Procesos batch, tareas programadas y volúmenes persistentes |

## Relación con la arquitectura

Para entender cómo se concretan estos casos de uso, se recomienda repasar el modelo de objetos y componentes en [`../arquitectura/README.md`](../arquitectura/README.md):

| Concepto de arquitectura | Caso de uso donde aparece |
| --- | --- |
| Deployment, ReplicaSet, DaemonSet ([12-workloads.md](../arquitectura/12-workloads.md)) | Microservicios, web, CI/CD, observabilidad |
| StatefulSet ([12-workloads.md](../arquitectura/12-workloads.md)) | Bases de datos |
| Service, Ingress, Gateway API ([13/14](../arquitectura/13-services.md)) | Exposición de tráfico en casi todos los casos |
| PersistentVolume / PVC / CSI ([06-kubelet.md](../arquitectura/06-kubelet.md)) | BBDD, CI/CD, batch, almacenamiento |
| Job / CronJob ([12-workloads.md](../arquitectura/12-workloads.md)) | Batch, backups, tareas programadas |
| Dynamic Resource Allocation / GPUs ([03-kube-scheduler.md](../arquitectura/03-kube-scheduler.md)) | IA / ML |
| Custom controllers / Operators ([16-extensiones.md](../arquitectura/16-extensiones.md)) | Prometheus (operador), operación de BBDD |

## Cómo leer cada documento

Cada caso de uso sigue la misma plantilla:

1. **Problema** — el reto de negocio/técnico que aborda.
2. **Solución en Kubernetes** — qué primitivas se usan y por qué.
3. **Arquitectura de referencia** — diagrama y componentes involucrados.
4. **Ejemplo real** — muestra concreta de `kubernetes/examples` o `pulumi/examples`.
5. **Consideraciones** — tradeoffs, riesgos y mejores prácticas.