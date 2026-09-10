# ADR-0001: Usar Azure Kubernetes Service (AKS), una región por ambiente

- **Estado**: aceptado
- **Fecha**: 2026-09-07
- **Decisores**: equipo de plataforma (trabajo práctico universitario)

## Contexto

La plataforma e-commerce necesita un servicio gestionado de Kubernetes para la
ruta de demo (presupuesto friendly, tiers Free/Basic) a la vez que sea
reproducible desde Terraform. El enunciado del proyecto apunta explícitamente
a Azure como nube por defecto (AKS + ACR + servicios de datos gestionados),
con EKS/GKE como ejercicio de portabilidad a través de la estructura
`infra/terraform/modules/{eks,aks,gke}`.

Cada ambiente (dev/staging/prod) tiene **su propio clúster y su propio grupo
de recursos de Azure** (`infra/terraform/envs/<env>`), así que un fallo de
demo en dev nunca puede afectar a prod, y los valores de `terraform.tfvars`
por ambiente (tamaño de VM, cantidad de nodos, SKUs) mantienen un radio de
impacto claro.

## Decisión

- Usar **AKS** como proveedor de clúster (`infra/terraform/modules/aks`),
  una única región (`westeurope` en los ejemplos committed de `terraform.tfvars`).
- Un clúster **por ambiente**; el mismo árbol GitOps de `cluster/` despliega
  a los tres a través de overlays.
- Versión de Kubernetes fijada en los tfvars del ambiente (ejemplo: `1.30`),
  SKU Free tier para dev, un node pool con autoscaling.
- ACR es el registry de imágenes, creado por ambiente junto al clúster.

## Consecuencias

- Plano de control gestionado: no hay bootstrapping de etcd/TLS kubelet para
  operar (la documentación teórica de `docs/arquitectura/` igual lo cubre
  para el examen escrito).
- Entra ID RBAC (`enable_aad`) simplifica la autenticación del clúster;
  funciona con cuentas locales mientras se aprende.
- Los costos se mantienen acotados: AKS Free-tier + nodos dev `Standard_B2s`
  + SKUs Basic para servicios de datos entran en el presupuesto del TP; en
  prod solo se subirían los SKUs.
- Una sola región significa que no hay historia de HA multi-región —
  aceptable para un TP; un SLA real necesitaría al menos una segunda región
  o un plan de DR.

## Alternativas consideradas

- **EKS / GKE**: existe una estructura de módulo equivalente en el repo pero
  el curso, el repo de referencia (walidhabbach) y la nube de demo son Azure
  — mantener EKS/GKE solo como ejercicio de portabilidad evita dispersarse.
- **kubeadm en VMs**: control total del plano de control (estilo
  kubernetes-the-hard-way) pero demasiada operatoria para una demo con 17
  servicios; descartado a favor de AKS gestionado.
- **AKS multi-región**: correcto para prod real, excesivo y fuera de
  presupuesto acá.
