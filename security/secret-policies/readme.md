# security/secret-policies — secretos como código: política + guía

## Cómo funcionan REALMENTE los secretos en esta plataforma (verificado)

1. **Azure Key Vault** guarda las credenciales (el clúster está en AKS).
2. **External Secrets Operator (ESO)** las sincroniza a Kubernetes Secrets via
   el **ClusterSecretStore** `azure-keyvault`
   (`cluster/base/external-secrets/cluster-secret-store.yaml`, authType
   ManagedIdentity o WorkloadIdentity).
3. Los servicios consumen el `Secret` generado con el estilo habitual de
   `envFrom.secretRef` (o volume mount). Ver el ejemplo funcionando
   (`cluster/base/external-secrets/example-external-secret.yaml`) que crea
   `ecommerce-db-credentials/db-password` desde Key Vault.

**Regla: ninguna credencial se escribe a mano en este repo.** Eso es lo que
enforcea `disallow-plain-secrets.yaml` (en modo Audit por defecto).

## Qué contiene este directorio

| Archivo | Kind | Modo | Propósito |
|---|---|---|---|
| `disallow-plain-secrets.yaml` | Kyverno ClusterPolicy | **Audit** (default) | Marca Secrets Opaque cuyos nombres de clave parecen credenciales (password/token/api_key/...) creados a mano en el namespace `ecommerce`. |
| `readme.md` (este archivo) | doc | — | Instrucciones de integración + tuning. |

### Por qué Audit y no Enforce (desviación deliberada del brief)

El brief ofrecía una política Enforce pero decía explícitamente "los
estudiantes deben tuneearla". Juzgué **Enforce demasiado invasivo as-shipped**
por tres razones verificadas:

1. **Colisión con ESO**: los ExternalSecrets crean Secrets reales con nombres
   de clave como `db-password` → las propias claves objetivo de la política.
   En Enforce, con `creationPolicy: Owner` en
   `example-external-secret.yaml`, el Secret del controlador sería
   **rechazado en la admisión y la plataforma se rompe**.
2. **Precedente del repo**: `require-resources` (cluster/base/kyverno) se
   entrega en Audit hasta que cada workload cumpla — misma regla conservadora.
3. **Contexto de formación**: la lista regex de claves es un punto de partida;
   un estudiante debe tuneearla a su inventario real de claves antes de que
   pueda bloquear de forma segura.

Loop de control disponible para estudiantes: mirar los policy reports
(`kubectl get policyreport -n ecommerce`) → tuneear → descomentar el exclude
de `app.kubernetes.io/managed-by: external-secrets-operator` → dar vuelta a
`validationFailureAction: Enforce` → observar la admisión. Documentá el
cambio en tu reporte del TP — ese ES el entregable.

## Interacción con las piezas de plataforma de la Fase 3

- **ClusterSecretStore / ejemplos** (`cluster/base/external-secrets/`): fuente
  de verdad de dónde vienen las credenciales. Este directorio solo *custodia*
  el resultado.
- **Kyverno** (`cluster/base/kyverno/policies/`): las tres políticas
  existentes apuntan a Pods; esta apunta a Secrets — complementarias, sin
  overlap.
- **`security/README.md`**: interacción de PSA + Kyverno (orden de la cadena
  de admisión).

## Checklist de tuning antes de Enforce

1. `kubectl get secrets -n ecommerce -o json | jq -r '.items[].data | keys[]'`
   — inventariá los nombres de clave reales; extendé la lista base de regex
   (p. ej. `client_secret`, `connection_string`) o achicala.
2. Descomentá el bloque de exclude para Secrets gestionados por ESO (verificá
   el label real que ESO setea en tu versión: `kubectl get secret
   ecommerce-db-credentials -n ecommerce -o jsonpath='{.metadata.labels}'`).
3. Cambiá `validationFailureAction: Enforce` en un namespace de
   **dev/staging** primero (el namespaceSelector actual pinnea `ecommerce` —
   ensanchá por env).
4. Re-corré el pipeline de CD; si algún Secret generado por un controlador
   queda bloqueado, el loop de audit muestra exactamente qué clave matcheó —
   tuneá, no deshabilites.