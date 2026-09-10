# diagrams/ — diagramas Mermaid

Diagramas Mermaid estáticos que reflejan el cableado **real** del repo
(flujos de gitops, NetworkPolicies, topología de servicios). Son el
companion visual de `../adr/` (decisiones), `../runbooks/` (operaciones) y
`scripts/README.md` (pipelines).

| Archivo | Qué muestra | Fuente de verdad en el repo |
|---|---|---|
| [`architecture.mmd`](architecture.mmd) | Topología completa: ingress → gateway/bff → 17 servicios, capa de datos, observabilidad, Argo CD | `cluster/overlays/*`, `services/*/k8s/base`, `observability/`, `security/` |
| [`gitops-flow.mmd`](gitops-flow.mmd) | Secuencia: PR → CI gates → push a ACR → rewrite de newTag → sync de Argo CD → admission → runtime | `.github/workflows/{ci,cd}.yml`, `cluster/base/argocd/` |
| [`network-policies.mmd`](network-policies.mmd) | Modelo default-deny + permisos de ingress/egress por servicio | `security/network-policies/`, `services/*/k8s/base/networkpolicy.yaml` |

## Cómo renderizar

- **Rápido**: pegá el contenido del archivo en <https://mermaid.live> (usá
  el estilo de code block "?" que se muestra en los archivos).
- **Render local a PNG/SVG**: `npx -y @mermaid-js/mermaid-cli -i
  architecture.mmd -o architecture.svg`. Requiere Node 18+ — solo una vez,
  los archivos `.mmd` son el fuente committed.
- **GitHub**: `.mmd` se renderiza nativamente en code fences de Markdown; los
  links del README mantienen los archivos crudos navegables.

## Mantenerlos honestos

Estos diagramas son **contratos con el repo**, mismo espíritu que
`tests/manifests/`: si cambia un borde de NetworkPolicy, actualizá
`network-policies.mmd` en el mismo commit. Las convenciones de
`scripts/README.md` (placeholders `<acr>`, `api.<domain>`, `rg-…`) aplican
acá también — no credenciales reales, no hosts inventados.