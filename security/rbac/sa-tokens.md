# Service account tokens — bound tokens (Kubernetes ≥ 1.24)

This file is **documentation only** (markdown, not a manifest). It explains how
service accounts authenticate in this cluster and why you must NOT mint
long-lived secret tokens.

## TL;DR

- Since Kubernetes **1.24**, the API server no longer auto-creates a `Secret`
  of type `service-account-token` for every ServiceAccount.
- Use **bound service account tokens** (short-lived, audience-scoped, mounted
  via projected volumes) instead.
- Every service in this repo already follows the pattern:
  `automountServiceAccountToken: false` on its ServiceAccount
  (`services/*/k8s/base/serviceaccount.yaml`, verified on `catalog-svc`) —
  app pods do not speak to the API server at all, so they do not even need a
  token mounted.

## The problem with long-lived SA tokens

1. A service-account token Secret has **no expiry** and no audience
   restriction. If it leaks (image dump, logs, repo), it is valid until
   manually rotated.
2. The token grants whatever RBAC the ServiceAccount has — typically
   cluster-wide in misconfigured clusters.
3. Git history is forever: a token committed once is compromised forever.

## What to use instead

### 1. Projected bound tokens (inside a Pod)

```yaml
# exposure to the app is via a projected volume, not a Secret:
volumes:
  - name: kube-api-token
    projected:
      sources:
        - serviceAccountToken:
            path: token
            expirationSeconds: 3600     # 1h — auto-rotated by kubelet
            audience: kubernetes.default.svc
containers:
  - name: app
    volumeMounts:
      - name: kube-api-token
        mountPath: /var/run/secrets/tokens
```

### 2. Manual bound token for local debugging (no Secret involved)

```bash
kubectl create token catalog-svc -n ecommerce --duration=1h
```

### 3. Workload identity (recommended on AKS for cloud access)

Instead of giving pods K8s tokens for Azure resources, use **Azure Workload
Identity** — the AKS-managed OIDC issuer + federated credentials. This repo's
external-secrets design already heads there: `cluster-secret-store.yaml`
mentions `authType: WorkloadIdentity` as the recommended alternative for the
ESO controller (see `cluster/base/external-secrets/cluster-secret-store.yaml`).

## When a long-lived token was already created (pre-1.24 or manual)

- Identify with `kubectl get secrets -n <ns> | grep kubernetes.io/service-account-token`.
- Rotate immediately: delete the Secret (`kubectl delete secret <name>`), the
  controller re-creates a fresh one (pre-1.24 behavior) — or move the workload
  to projected tokens.

## References

- KEP-1205 (bound service account tokens)
- Kubernetes docs: "Bound service account tokens" / "TokenRequest API"
- Azure docs: "Azure AD Workload Identity" for AKS