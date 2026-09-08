# ADR-0004: Spring Cloud + Eureka for service discovery (and Config Server)

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: platform team

## Context

17 Spring Boot 3.3.x services must find each other without hard-coded
addresses: the api-gateway routes to business services, and services resolve
each other's instances inside the ecommerce namespace. The reference repo
(walidhabbach) ships Eureka + Spring Cloud Config, and the services already
import `spring-cloud-starter-netflix-eureka-client` (services/README.md
convention). Spring Cloud 2023.0.x is the BOM in use.

## Decision

- **Eureka** (`discovery-service`, port 8761) as the service registry:
  services register via the eureka client and resolve peers by logical name.
- **Spring Cloud Config** (`config-service`, port 8888) as the central
  configuration server — env-agnostic defaults come from it; per-env
  overrides ride the `ecommerce-env-config` ConfigMap (envFrom) instead of
  config-server profiles.
- The api-gateway (Spring Cloud Gateway) uses discovery to route
  (`lb://<service>` style lookups), so a new service needs no gateway route
  table change for name resolution.
- Both infrastructure services are first-class citizens of the uniform
  layout: `k8s/base` + `k8s/overlays/prod` + HPA/PDB/NetworkPolicy.

## Consequences

- Services reach peers via stable logical names even when Pod IPs change —
  NetworkPolicies allow ecommerce peer egress by label, so discovery traffic
  flows (catalog-svc policy comment: `config-service (:8888),
  discovery-service (:8761)`).
- Eureka is a stateful-ish registry: on restart it rebuilds from
  heartbeats; the deployment keeps 1 replica in dev and 3 in prod (overlay).
- One more moving part to watch in `troubleshooting.md` ("Eureka not
  registering" section) and one more dependency for each service to start
  before joining the registry.
- Config-on-Git vs ConfigMap: the project deliberately uses ConfigMaps for
  env-specific settings so secrets and env values stay in GitOps artifacts,
  not in a config server's backend.

## Alternatives considered

- **Kubernetes native DNS only**: `svc.namespace.svc.cluster.local` works
  but gives no instance-level health or LB-aware selection from the Spring
  side; kept as the fallback mental model.
- **Consul**: same job, extra operator (ACL tokens, gossip) with no
  advantage for a single-region TP.
- **istio (service mesh)**: real service discovery + mTLS, but far too heavy
  for the demo scope; PSA/NetworkPolicies already cover the isolation story.