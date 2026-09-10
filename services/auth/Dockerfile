# auth — Keycloak with a bundled `ecommerce` realm.
#
# Keycloak is NOT built from source: this image simply bundles the realm
# definition and starts the stock Keycloak distribution.
#
# Build (CI does this):
#   docker build -t acr.azurecr.io/auth:latest .
#
# ── Dev (default): start-dev with realm import ─────────────────────────────
#   - Hot reload, dev database (H2), no TLS inside the pod (TLS at Ingress)
#   - Imports src/realm-export.json on first start
#
# ── Prod (override ENTRYPOINT via pod spec or `--entrypoint` in YAML) ─────
#   /opt/keycloak/bin/kc.sh start \
#     --import-realm \
#     --hostname=https://api.<domain> \
#     --proxy-headers=xforwarded \
#     --db=postgres \
#     --db-url-host=${DB_HOST} --db-username=${DB_USER} --db-password=${DB_PASSWORD} \
#     --cache-stack=kubernetes \
#     --health-enabled=true
#   Keep at least 2 replicas; HA needs the distributed cache (JGroups), so a
#   StatefulSet or the Keycloak Operator is the real production path.

FROM quay.io/keycloak/keycloak:24.0.5

USER root
COPY src/realm-export.json /opt/keycloak/data/import/realm-export.json
RUN chown -R keycloak:keycloak /opt/keycloak/data/import
USER keycloak

# Keycloak listens on 8080 inside the container (matches Service targetPort).
EXPOSE 8080

ENTRYPOINT ["/opt/keycloak/bin/kc.sh", "start-dev", "--import-realm"]