# diagrams/ — Mermaid diagrams

Static Mermaid diagrams that mirror the **actual** repo wiring (gitops flows,
NetworkPolicies, service topology). They are the visual companion to
`../adr/` (decisions), `../runbooks/` (operate) and `scripts/README.md`
(pipelines).

| File | What it shows | Source of truth in the repo |
|---|---|---|
| [`architecture.mmd`](architecture.mmd) | Full topology: ingress → gateway/bff → 17 services, data plane, observability, Argo CD | `cluster/overlays/*`, `services/*/k8s/base`, `observability/`, `security/` |
| [`gitops-flow.mmd`](gitops-flow.mmd) | Sequence: PR → CI gates → ACR push → newTag rewrite → Argo CD sync → admission → runtime | `.github/workflows/{ci,cd}.yml`, `cluster/base/argocd/` |
| [`network-policies.mmd`](network-policies.mmd) | Default-deny model + per-service ingress/egress allowances | `security/network-policies/`, `services/*/k8s/base/networkpolicy.yaml` |

## How to render

- **Quick**: paste the file content into <https://mermaid.live> (use the
  "?" code block style shown in the files).
- **Local render to PNG/SVG**: `npx -y @mermaid-js/mermaid-cli -i
  architecture.mmd -o architecture.svg`. Requires Node 18+ — one-time only,
  the `.mmd` files are the checked-in source.
- **GitHub**: `.mmd` renders natively in Markdown code fences; the README
  links keep the raw files browsable.

## Keeping them honest

These diagrams are **contracts with the repo**, same spirit as
`tests/manifests/`: if a NetworkPolicy edge changes, update
`network-policies.mmd` in the same commit. The `scripts/README.md`
conventions (placeholders `<acr>`, `api.<domain>`, `rg-…`) apply here too —
no real credentials, no invented hosts.