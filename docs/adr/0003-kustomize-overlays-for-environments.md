# ADR-0003: Kustomize overlays for the application layer; Helm for platform charts

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: platform team

## Context

Two templating layers coexist in the platform: **platform components**
(ingress-nginx, cert-manager, external-secrets, kyverno, keda,
kube-prometheus-stack) are installed from upstream Helm charts with many
values; the **17 services** are plain Kubernetes manifests that differ per
environment only by a few small patches (replicas, resources, image tag).
Using Helm for the services too would add chart boilerplate to 17 repos
without buying real value.

## Decision

- **Helm** is reserved for platform charts, installed through Argo CD
  Applications (`cluster/base/argocd/applications/*.yaml` point at chart
  repositories) — the "platform layer" of `cluster/base`.
- **Kustomize** is the tool for everything first-party:
  - `cluster/base` + `cluster/overlays/{dev,staging,prod}` for the platform
    resources (ClusterIssuer, ClusterSecretStore, env-config, namespaces);
  - `services/<svc>/k8s/base` + `services/<svc>/k8s/overlays/prod` for each
    service (uniform layout per `services/README.md`).
- Overlays layering: `dev`/`staging` reuse each service's `k8s/base`,
  `prod` uses the thin `k8s/overlays/prod` (replicas + resources patch).
- The CD pipeline mutates environment overlays by rewriting `images[].newTag`
  via `yq` — a kustomize-native, declarative contract.

## Consequences

- Uniform 17-service layout → the checks in `tests/manifests/test-service-contract.py`
  can enforce the whole fleet from one test.
- Overlay `issuer-default.yaml` per env lets Let's Encrypt staging vs prod
  switch by environment without patching base (documented in
  `cluster/overlays/README.md`).
- Plain YAML means the repo is reviewable without chart templating knowledge;
  braces never appear in `resources:` paths.
- Cost: per-service overlays only exist for prod; dev/staging share base —
  a deliberate three-env asymmetry, verified by `test-overlay-wiring.py`.

## Alternatives considered

- **Helm for services too**: uniform tooling, but 17 chart.yaml + values.yaml
  trees to maintain and no benefit for the small per-env delta; the CD
  `newTag` rewrite would also need `helm upgrade` semantics per service.
- **Helmfile for everything**: non-GitOps drift source; documented as the
  comparison path in `cluster/base/helmfile.yaml` only.
- **Plain kubectl apply, no templating**: would duplicate the per-env
  differences 3× across 17 services — rejected.