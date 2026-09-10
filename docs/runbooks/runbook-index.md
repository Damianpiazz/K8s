# Runbooks — índice

Guías operacionales para la plataforma e-commerce. Cada runbook está escrito
para la persona que sostiene el pager/teclado a las 3 AM — síntoma primero,
fix segundo, con los comandos exactos. Existen los runbooks para el *cómo*,
no el *por qué*: los ADRs (`../adr/`) capturan las decisiones; estos son las
consecuencias operacionales.

| Runbook | Cuándo abrirlo |
|---|---|
| [`troubleshooting.md`](troubleshooting.md) | Orientado a síntomas: CrashLoopBackOff, ImagePullBackOff, TLS, Kyverno/`latest`, PSA restricted, Argo CD OutOfSync, HPA, Eureka. Empezá acá si "algo está roto". |
| [`deploy-end-to-end.md`](deploy-end-to-end.md) | Despliegue green-field: local (minikube/kind) y cloud (dev/staging/prod) con los scripts exactos, en orden. Funciona también como guion de la demo. |
| [`scaling-and-recovery.md`](scaling-and-recovery.md) | Comportamiento de la plataforma bajo carga y falla: HPA/KEDA, drenajes de PDB, pérdida de nodo, backups de datos gestionados, rollback de GitOps. |

## Navegación rápida

**"Un Pod no arranca"** → `troubleshooting.md` §1–2 (CrashLoopBackOff,
ImagePullBackOff).

**"Certs/HTTPS rotos"** → `troubleshooting.md` §3.

**"Argo CD en rojo"** → `troubleshooting.md` §6.

**"Necesito escalar / drenar / respaldar"** → `scaling-and-recovery.md` §1–4.

**"Revertirlo"** → `scaling-and-recovery.md` §5.

**"Estoy desplegando por primera vez"** → `deploy-end-to-end.md`.

**"¿Qué secretos/valores de env completo?"** → `../README.md` (índice de docs) +
tabla de placeholders de `scripts/README.md` + `../adr/0007`.

## Convenciones compartidas por todos los runbooks

- Los bloques de comandos son PowerShell 5.1 / bash — los scripts helper
  proveen un gemelo `.ps1` y `.sh` para cada tarea (matriz de `scripts/README.md`).
- Los placeholders en `MAYÚSCULAS` están documentados en `scripts/README.md`
  (p. ej. `<acr>`, `api.<domain>`, `rg-…`) — nunca commitees valores reales.
- Regla de GitOps: **el clúster siempre converge hacia Git** (ADR-0002). Si un
  cambio manual de `kubectl` "arregla" algo, Argo CD lo va a revertir; arreglá
  el repo en su lugar.
- El `disallow-latest-tag` de Kyverno y el PSA `restricted` son features, no
  incidentes — los runbooks dicen dónde muerden y cómo trabajar con ellos.