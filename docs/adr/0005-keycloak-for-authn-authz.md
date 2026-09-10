# ADR-0005: Keycloak para autenticación y autorización (auth service)

- **Estado**: aceptado
- **Fecha**: 2026-09-07
- **Decisores**: equipo de plataforma

## Contexto

Los usuarios (navegador → api-gateway) necesitan autenticación y
autorización basada en tokens a través de la plataforma. El servicio `auth`
es un deployment de primera clase en el layout uniforme
(`services/auth/k8s/base/deployment.yaml`), y el repo de referencia trae un
componente de auth estilo Keycloak. El gateway debe validar bearer tokens por
ruta sin que cada servicio reimplemente verificación JWT.

## Decisión

- **Keycloak** corre como el servicio `auth` (imagen `acr.azurecr.io/auth`,
  puerto de contenedor 8080, env de bootstrap admin
  `KEYCLOAK_ADMIN`/`KEYCLOAK_ADMIN_PASSWORD` obtenidos del Secret
  `keycloak-admin-credentials`).
- Las credenciales de admin vienen de un Secret placeholder hoy; el camino de
  producción es External Secrets → Azure Key Vault (ADR-0007) para que la
  contraseña de consola nunca esté en Git.
- El **api-gateway** termina la validación de tokens (JWT en el edge): las
  rutas se protegen en el gateway, los servicios detrás de la política de
  pares de ecommerce confían en el gateway (coincide con la regla de ingress
  de NetworkPolicy por servicio "solo el api-gateway puede llamar a la API de
  catalog").

## Consecuencias

- Único identity provider: realms, clients y roles se configuran una vez y
  cada servicio hereda la historia.
- El Pod de auth debe mantenerse sano o falla cualquier ruta de login — recibe
  el mismo tratamiento de HPA/PDB/probes que cualquier otro servicio, y
  `troubleshooting.md` cubre sus causas de CrashLoopBackOff (secret de admin
  mal configurado, DB/backing store ausente).
- Keycloak en modo `start-dev` es conveniente para la demo pero no está
  endurecido para producción; el overlay de prod debería cambiar al modo de
  producción + Postgres gestionado (ADR-0006) antes de tráfico real.

## Alternativas consideradas

- **Servicio Spring Security + JWT custom**: control total, pero reimplementa
  realms/clients/roles que Keycloak ya trae; más código para demostrar, menos
  impresionante.
- **Azure AD B2C / Entra ID**: la elección gestionada natural para un
  producto Azure real (flujos específicos de entra, tiers de costo) pero
  ataría la demo local de minikube/kind a la conectividad de Azure —
  descartado por portabilidad del TP.
- **Sin auth en el gateway (por servicio)**: cada servicio necesitaría el
  mismo filtro JWT; descartado — ese es exactamente el trabajo del gateway
  (ADR-0010).