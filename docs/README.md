# Documentación — plataforma e-commerce en Kubernetes

Esta carpeta es la base de conocimiento del proyecto. El material **teórico**
(componentes, comandos, casos de uso) convive con el material
**operacional** (ADRs, runbooks, diagramas). Empezá acá y profundizá según
la tarea que necesites resolver.

## Navegación rápida

| Necesito… | Ir a |
|---|---|
| Entender la topología de la plataforma / por qué existe cada pieza | [`diagrams/architecture.mmd`](diagrams/architecture.mmd) + [`adr/`](adr/) |
| Desplegar desde cero (local o Azure) | [`runbooks/deploy-end-to-end.md`](runbooks/deploy-end-to-end.md) |
| Arreglar algo roto | [`runbooks/troubleshooting.md`](runbooks/troubleshooting.md) |
| Escalar, drenar, respaldar, revertir | [`runbooks/scaling-and-recovery.md`](runbooks/scaling-and-recovery.md) |
| Encontrar el script correcto o el valor de placeholder | [`../scripts/README.md`](../scripts/README.md) |
| Verificar que los manifiestos del repo siguen siendo válidos | [`../tests/README.md`](../tests/README.md) |
| Estudiar teoría de Kubernetes para el examen escrito | [`arquitectura/`](arquitectura/README.md) — 17 clases, plano de control primero |
| Probar comandos kubectl | [`comandos/`](comandos/README.md) — 9 fichas prácticas |
| Leer los apuntes de casos de uso del curso | [`casos-de-uso/`](casos-de-uso/README.md) |
| Ver los tutoriales de despliegue (GitOps, Helm vs Kustomize) | [`despliegue/`](despliegue/README.md) |
| Releer el plan original del proyecto | [`plan-ecommerce-k8s.md`](plan-ecommerce-k8s.md) |
| Ver las imágenes de diagramas | [`img/`](img/) |

## Los tres pilares nuevos (Fase 9)

- **[`adr/`](adr/)** — 10 Architecture Decision Records (MADR-light, uno
  por página) que explican el *por qué*: AKS una región, Argo CD app-of-apps,
  Kustomize para apps + Helm para charts de plataforma, Spring Cloud/Eureka,
  Keycloak, capa de datos gestionada, External Secrets → Key Vault, stack de
  observabilidad, PSA restricted + Kyverno, BFF + gateway.
- **[`runbooks/`](runbooks/)** — guías operacionales: troubleshooting
 orientado a síntomas, despliegue end-to-end (guion de la demo),
  escalado/recuperación con rollback de GitOps.
- **[`diagrams/`](diagrams/)** — diagramas Mermaid que reflejan el cableado
  real: topología de arquitectura, secuencia de GitOps (ci/cd.yml → Argo CD →
  admissions → runtime), y el modelo default-deny de NetworkPolicy.

## Cómo se conectan las capas

```
GitHub Actions (ci/cd.yml) ──► ACR ──► Argo CD (cluster/base/argocd)
        │                              │
        └──► cluster/overlays/{dev,staging,prod}  →  kustomize renderizado
                                            │
        services/*/k8s/base + overlays/prod ─┘  (17 servicios, layout uniforme)
                                            │
        observability/  security/  data/  ────┘  (Fases 3, 6, 8)
```

- **La fuente de verdad es Git** (ADR-0002): el clúster converge hacia
  `cluster/overlays/<env>`; nunca editar a mano un clúster en ejecución.
- **Placeholders** en `MAYÚSCULAS` (`acr.azurecr.io`, `<acr>`, `api.<domain>`,
  `rg-…`, `kv-…`) están documentados en `scripts/README.md` y
  `cluster/base/README.md` — completalos con las salidas de terraform, nunca
  commitees credenciales reales (Gitleaks corre en CI).
- **Los contratos se testean**: `tests/manifests/` verifica el layout de 17
  servicios, el cableado de overlays, la renderización con kustomize y la
  validez YAML antes de que algo toque un clúster.

## Nota sobre idiomas

Los capítulos teóricos (`arquitectura/`, `casos-de-uso/`, `comandos/`,
`despliegue/`) están en español — el idioma del examen para este trabajo
práctico. Los artefactos operacionales agregados en la Fase 9 (ADRs,
runbooks, diagramas, scripts, tests) también están en español.