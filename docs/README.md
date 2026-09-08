# Documentation — e-commerce platform on Kubernetes

This folder is the project's knowledge base. **Theoretical** material
(components, commands, use cases — Spanish, written for the exam) sits next
to **operational** material (ADRs, runbooks, diagrams — English, written for
whoever operates or extends the platform). Start here, then go deep where the
task takes you.

## Quick navigation

| I need to… | Go to |
|---|---|
| Understand the platform topology / why each piece exists | [`diagrams/architecture.mmd`](diagrams/architecture.mmd) + [`adr/`](adr/) |
| Deploy from scratch (local or Azure) | [`runbooks/deploy-end-to-end.md`](runbooks/deploy-end-to-end.md) |
| Fix something broken | [`runbooks/troubleshooting.md`](runbooks/troubleshooting.md) |
| Scale, drain, back up, roll back | [`runbooks/scaling-and-recovery.md`](runbooks/scaling-and-recovery.md) |
| Find the right script or placeholder value | [`../scripts/README.md`](../scripts/README.md) |
| Check the repo's manifests are still valid | [`../tests/README.md`](../tests/README.md) |
| Study Kubernetes theory for the written exam | [`arquitectura/`](arquitectura/README.md) — 17 lessons, control plane first |
| Test-drive kubectl commands | [`comandos/`](comandos/README.md) — 9 hands-on sheets |
| Read the course's use-case notes | [`casos-de-uso/`](casos-de-uso/README.md) |
| See the deployment tutorials (GitOps, Helm vs Kustomize) | [`despliegue/`](despliegue/README.md) |
| Re-read the original project plan | [`plan-ecommerce-k8s.md`](plan-ecommerce-k8s.md) |
| Look at the diagram images | [`img/`](img/) |

## The three new pillars (Phase 9)

- **[`adr/`](adr/)** — 10 Architecture Decision Records (MADR-light, one
  page each) explaining the *why*: AKS single region, Argo CD app-of-apps,
  Kustomize for apps + Helm for platform charts, Spring Cloud/Eureka,
  Keycloak, managed data plane, External Secrets → Key Vault, observability
  stack, PSA restricted + Kyverno, BFF + gateway.
- **[`runbooks/`](runbooks/)** — operational playbooks: symptom-first
  troubleshooting, end-to-end deployment (the demo script), scaling/recovery
  with GitOps rollback.
- **[`diagrams/`](diagrams/)** — Mermaid diagrams mirroring the real wiring:
  architecture topology, GitOps sequence (ci/cd.yml → Argo CD → admissions →
  runtime), and the NetworkPolicy default-deny model.

## How the layers connect

```
GitHub Actions (ci/cd.yml) ──► ACR ──► Argo CD (cluster/base/argocd)
        │                              │
        └──► cluster/overlays/{dev,staging,prod}  →  kustomize rendered
                                            │
        services/*/k8s/base + overlays/prod ─┘  (17 services, uniform layout)
                                            │
        observability/  security/  data/  ────┘  (Phases 3, 6, 8)
```

- **Source of truth = Git** (ADR-0002): the cluster converges to
  `cluster/overlays/<env>`; never hand-edit a running cluster.
- **Placeholders** in `UPPER_CASE` (`acr.azurecr.io`, `<acr>`, `api.<domain>`,
  `rg-…`, `kv-…`) are audited in `scripts/README.md` and
  `cluster/base/README.md` — fill them from terraform outputs, never commit
  real credentials (Gitleaks runs in CI).
- **Contracts are tested**: `tests/manifests/` proves the 17-service layout,
  overlay wiring, kustomize renderability and YAML validity before anything
  hits a cluster.

## Language note

Theory chapters (`arquitectura/`, `casos-de-uso/`, `comandos/`,
`despliegue/`) are in Spanish — the exam language for this practical work.
Operational artifacts added in Phase 9 (ADRs, runbooks, diagrams, scripts,
tests) are in English, matching the repo's code and CI convention.