# ADR-0001: Use Azure Kubernetes Service (AKS), one region per environment

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: platform team (university practical work)

## Context

The e-commerce platform needs a managed Kubernetes offering for the demo
path (budget-friendly free/Basic tiers) while remaining reproducible from
Terraform. The project brief explicitly targets Azure as the default cloud
(AKS + ACR + managed data services), with EKS/GKE kept as a portability
exercise through the `infra/terraform/modules/{eks,aks,gke}` structure.

Each environment (dev/staging/prod) gets **its own cluster and its own Azure
resource group** (`infra/terraform/envs/<env>`), so a failed demo in dev can
never affect prod, and per-env `terraform.tfvars` values (VM size, node
counts, SKUs) keep a clear blast-radius boundary.

## Decision

- Use **AKS** as the cluster provider (`infra/terraform/modules/aks`),
  single region (`westeurope` in the committed `terraform.tfvars` examples).
- One cluster **per environment**; the same `cluster/` GitOps tree deploys to
  all three via overlays.
- Kubernetes version pinned in the env tfvars (example: `1.30`), Free tier
  SKU for dev, one autoscaled node pool.
- ACR is the image registry, created per environment alongside the cluster.

## Consequences

- Managed control plane: no etcd/TLS kubelet bootstrapping to operate (the
  `docs/arquitectura/` theory docs still cover it for the written exam).
- Entra ID RBAC (`enable_aad`) simplifies cluster auth; falls back to local
  accounts while learning.
- Costs stay bounded: Free-tier AKS + `Standard_B2s` dev nodes + Basic SKUs
  for data services fit the TP budget; prod would raise SKUs only.
- Single region means no multi-region HA story — acceptable for a TP; a real
  SLA would need at least a second region or a DR plan.

## Alternatives considered

- **EKS / GKE**: equivalent module structure exists in the repo but the
  course, the reference repo (walidhabbach) and the demo cloud are Azure —
  keeping EKS/GKE as a portability exercise only avoids spreading thin.
- **kubeadm on VMs**: full control-plane ownership (kubernetes-the-hard-way
  style) but far too much ops for a demo with 17 services; rejected in favor
  of managed AKS.
- **Multi-region AKS**: right for real prod, overkill and over-budget here.