# Runbooks — index

Operational playbooks for the e-commerce platform. Each runbook is written
for the person holding a pager/keyboard at 3 AM — symptom first, fix second,
with the exact commands. Why-runbooks exist, not how: the ADRs (`../adr/`)
capture the decisions; these are the operational consequences.

| Runbook | When to open it |
|---|---|
| [`troubleshooting.md`](troubleshooting.md) | Symptom-driven: CrashLoopBackOff, ImagePullBackOff, TLS, Kyverno/`latest`, PSA restricted, Argo CD OutOfSync, HPA, Eureka. Start here for "something is broken". |
| [`deploy-end-to-end.md`](deploy-end-to-end.md) | Green-field deployment: local (minikube/kind) and cloud (dev/staging/prod) with the exact scripts, in order. Doubles as the demo script. |
| [`scaling-and-recovery.md`](scaling-and-recovery.md) | Platform behavior under load and failure: HPA/KEDA, PDB drains, node loss, managed-data backups, GitOps rollback. |

## Quick navigation

**"A Pod won't start"** → `troubleshooting.md` §1–2 (CrashLoopBackOff,
ImagePullBackOff).

**"Certs/HTTPS broken"** → `troubleshooting.md` §3.

**"Argo CD red"** → `troubleshooting.md` §6.

**"I need to scale / drain / back up"** → `scaling-and-recovery.md` §1–4.

**"Roll it back"** → `scaling-and-recovery.md` §5.

**"I'm deploying for the first time"** → `deploy-end-to-end.md`.

**"Which secrets/env values do I fill?"** → `../README.md` (docs index) +
`scripts/README.md` placeholders table + `../adr/0007`.

## Conventions shared by all runbooks

- Command blocks are PowerShell 5.1 / bash — the helper scripts provide a
  `.ps1` and a `.sh` twin for each task (`scripts/README.md` matrix).
- Placeholders in `UPPER_CASE` are documented in `scripts/README.md`
  (e.g. `<acr>`, `api.<domain>`, `rg-…`) — never commit real values.
- GitOps rule: **the cluster always converges to Git** (ADR-0002). If a
  manual `kubectl` change "fixes" something, Argo CD will revert it; fix the
  repo instead.
- Kyverno's `disallow-latest-tag` and PSA `restricted` are features, not
  incidents — the runbooks say where they bite and how to work with them.