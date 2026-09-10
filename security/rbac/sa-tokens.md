# Tokens de service account — bound tokens (Kubernetes ≥ 1.24)

Este archivo es **solo documentación** (markdown, no un manifiesto). Explica
cómo se autentican los service accounts en este clúster y por qué NO debes
emitir tokens secret de larga vida.

## TL;DR

- Desde Kubernetes **1.24**, el API server ya no auto-crea un `Secret` de
  tipo `service-account-token` para cada ServiceAccount.
- Usá **bound service account tokens** (de corta vida, con audience, montados
  via projected volumes) en su lugar.
- Cada servicio de este repo ya sigue el patrón:
  `automountServiceAccountToken: false` en su ServiceAccount
  (`services/*/k8s/base/serviceaccount.yaml`, verificado en `catalog-svc`) —
  los pods de apps no hablan con el API server para nada, así que ni siquiera
  necesitan un token montado.

## El problema con los tokens de SA de larga vida

1. Un Secret de token de service account **no expira** y no tiene restricción
   de audience. Si se filtra (dump de imagen, logs, repo), es válido hasta que
   se rote manualmente.
2. El token otorga cualquier RBAC que tenga el ServiceAccount — típicamente
   cluster-wide en clústeres mal configurados.
3. El historial de Git es para siempre: un token commiteado una vez está
   comprometido para siempre.

## Qué usar en su lugar

### 1. Bound tokens proyectados (dentro de un Pod)

```yaml
# la exposición a la app es via un projected volume, no un Secret:
volumes:
  - name: kube-api-token
    projected:
      sources:
        - serviceAccountToken:
            path: token
            expirationSeconds: 3600     # 1h — auto-rotado por el kubelet
            audience: kubernetes.default.svc
containers:
  - name: app
    volumeMounts:
      - name: kube-api-token
        mountPath: /var/run/secrets/tokens
```

### 2. Bound token manual para debugging local (sin Secret)

```bash
kubectl create token catalog-svc -n ecommerce --duration=1h
```

### 3. Workload identity (recomendado en AKS para acceso cloud)

En vez de darles a los pods tokens K8s para recursos de Azure, usá **Azure
Workload Identity** — el issuer OIDC gestionado por AKS + credenciales
federadas. El diseño de external-secrets de este repo ya apunta ahí:
`cluster-secret-store.yaml` menciona `authType: WorkloadIdentity` como la
alternativa recomendada para el controlador de ESO (ver
`cluster/base/external-secrets/cluster-secret-store.yaml`).

## Cuándo ya se creó un token de larga vida (pre-1.24 o manual)

- Identificá con `kubectl get secrets -n <ns> | grep kubernetes.io/service-account-token`.
- Rotá inmediatamente: borrá el Secret (`kubectl delete secret <name>`), el
  controlador re-crea uno fresco (comportamiento pre-1.24) — o mové el
  workload a projected tokens.

## Referencias

- KEP-1205 (bound service account tokens)
- Documentación de Kubernetes: "Bound service account tokens" / "TokenRequest API"
- Documentación de Azure: "Azure AD Workload Identity" para AKS