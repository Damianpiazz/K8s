# ADR-0009: PSA restricted + políticas de Kyverno como cadena de admisión

- **Estado**: aceptado
- **Fecha**: 2026-09-07
- **Decisores**: equipo de plataforma

## Contexto

La fase de seguridad requiere gates duros sobre lo que puede correr en el
namespace del tenant: no contenedores root, no workloads privilegiados, no
tags `latest` de imagen, no labels/recursos faltantes. Kubernetes trae una
cadena de admisión built-in (Pod Security Admission) y la plataforma ya
instala Kyverno (`cluster/base/kyverno/policies/`) — los dos deben combinarse
sin pelearse.

## Decisión

- **PSA `restricted`** aplicado via labels de namespaces
  (`security/pod-security/pod-security-labels.yaml`) en **ecommerce + data**;
  `observability`/`security`/`platform` quedan en audit+warn (los workloads
  de charts todavía no cumplen restricted).
- **ClusterPolicies de Kyverno** (Enforce en namespaces del tenant):
  `require-labels` (app + env en cada Pod), `disallow-latest-tag`,
  `require-resources` (Audit hasta que cada workload declare recursos); un
  guard `disallow-plain-secrets` en Audit (ADR-0007) completa la capa.
- Los deployments de servicios cumplen: `runAsNonRoot: true`,
  `runAsUser: 1000`, `allowPrivilegeEscalation: false`, drop de TODAS las
  capabilities, `seccompProfile: {type: RuntimeDefault}` — verificado por
  `tests/manifests/test-service-contract.py` para los 17 servicios.
- **default-deny NetworkPolicy**
  (`security/network-policies/default-deny-all.yaml`) cubre los pods sin
  política por servicio; las políticas por servicio siguen funcionando via
  semántica de unión de políticas.

## Consecuencias

- Cadena de admisión por Pod en `ecommerce`: PSA (gate duro) → chequeos de
  labels/recursos de imagen de Kyverno → aislamiento de NetworkPolicy en
  runtime.
- Los charts/tooling que violan restricted deben quedarse fuera de los
  namespaces del tenant — por eso los namespaces de plataforma son
  audit-only (interacción documentada en `security/README.md`).
- `disallow-latest-tag` rechaza applies manuales con imágenes `:latest` —
  una restricción de flujo de trabajo documentada (correr el pipeline de CD o
  `deploy-cd-manual.ps1` primero).
- `runAsUser: 1000` + `readOnlyRootFilesystem: false` es legal bajo
  restricted (los servicios escriben /tmp para el Tomcat embebido); sin
  escalada de privilegios.

## Alternativas consideradas

- **Solo Kyverno (sin PSA)**: mecanismo único, pero el PSA built-in es
  gratis, sin webhooks y el curso lo pide explícitamente; ambos quedan
  demostrados y documentados — respuesta más rica para el profesor.
- **OPA/Gatekeeper**: equivalente a Kyverno pero el repo ya trae Kyverno con
  applications de charts; cambiarlo tocaría la capa de plataforma.
- **Enforce restricted en todos los namespaces**: falla inmediatamente en los
  workloads de charts (limitación verificada) — la matriz audit/warn es el
  término medio honesto hasta que cada chart sea restricted-compliant.