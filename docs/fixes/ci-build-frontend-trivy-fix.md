# CI Fix: Build Frontend + Trivy Filesystem Scan

**Date:** 2026-09-10
**Run:** [GitHub Actions #34438707191](https://github.com/Damianpiazz/K8s/actions/runs/34438707191)
**Commit:** `7894d6c`

---

## Summary

Two CI jobs failed on the latest run: **Build frontend** and **Trivy filesystem scan**. Both issues were in the `services/frontend/` service. Root causes identified and fixed.

---

## Error 1: Build Frontend

### Symptom

```
ERROR: failed to build: failed to solve: process "/bin/sh -c addgroup -S appgroup && adduser -S appuser -u 1000" did not complete successfully: exit code: 1
adduser: uid '1000' in use
```

### Root Cause

The Dockerfile (`services/frontend/Dockerfile`, line 29) created a custom user `appuser` with UID 1000:

```dockerfile
RUN addgroup -S appgroup && adduser -S appuser -u 1000
```

The base image `node:22-alpine` already ships with a `node` user that occupies UID 1000. Alpine's `adduser` refuses to create a second user with the same UID, causing the build to fail.

### Fix

Removed the custom user creation entirely. The runtime stage now uses the built-in `node` user (UID 1000) that already exists in `node:22-alpine`:

```dockerfile
# Before
RUN addgroup -S appgroup && adduser -S appuser -u 1000
WORKDIR /app
COPY --from=build /workspace/.next/standalone ./
COPY --from=build /workspace/.next/static ./.next/static
COPY --from=build /workspace/public ./public
USER appuser

# After
WORKDIR /app
COPY --from=build /workspace/.next/standalone ./
COPY --from=build /workspace/.next/static ./.next/static
COPY --from=build /workspace/public ./public
USER node
```

**Why this is safe:** The `node` user in `node:22-alpine` has UID 1000, same home directory convention, and is the officially supported non-root user for Node.js containers. Using it avoids the UID conflict and follows Alpine/Node.js best practices.

---

## Error 2: Trivy Filesystem Scan

### Symptom

Trivy found **17 HIGH severity vulnerabilities** (0 CRITICAL) in `services/frontend/pnpm-lock.yaml`, all with available fixes. The scan exits with code 1 due to `--exit-code 1 --severity CRITICAL,HIGH`.

### Vulnerable Packages

| Package | CVE(s) | Installed | Fixed | Type |
|---|---|---|---|---|
| brace-expansion | CVE-2026-13149, CVE-2026-14257, CVE-2026-69152 | 5.0.6 | >=5.0.9 | DoS |
| browserslist | CVE-2026-73088, CVE-2026-73089 | 4.28.1 | >=4.28.7 | Prototype pollution / DoS |
| fast-uri | CVE-2026-13676, CVE-2026-16221, CVE-2026-18446, CVE-2026-75899, CVE-2026-75975, CVE-2026-76172 | 3.1.2 | >=3.1.6 | SSRF / policy bypass |
| ip-address | CVE-2026-69192 | 10.2.0 | >=10.3.1 | SSRF |
| js-yaml | CVE-2026-59869, CVE-2026-84375, GHSA-5p4m-2wfm-xmqj | 4.2.0 | >=4.3.2 | DoS |
| nanoid | CVE-2026-67213 | 3.3.16 | >=3.3.18 | DoS |
| sharp | GHSA-rgj7-g3m4-5g8c | 0.35.3 | >=0.35.4 | libheif vulnerabilities |

### Fix

Ran `pnpm update` to resolve all vulnerable transitive dependencies to their patched versions:

```bash
pnpm update brace-expansion browserslist fast-uri ip-address js-yaml nanoid sharp
```

**Resolved versions after fix:**

| Package | Before | After |
|---|---|---|
| brace-expansion | 5.0.6 | 5.0.9 |
| browserslist | 4.28.1 | 4.28.9 |
| fast-uri | 3.1.2 | 3.1.7 |
| ip-address | 10.2.0 | 10.7.0 |
| js-yaml | 4.2.0 | 4.3.2 |
| nanoid | 3.3.16 | 3.3.18 |
| sharp | 0.35.3 | 0.35.4 |

All are transitive dependencies (none direct in `package.json`), so the update is safe and backward-compatible.

---

## Files Changed

| File | Change |
|---|---|
| `services/frontend/Dockerfile` | Removed custom user creation, use built-in `node` user |
| `services/frontend/pnpm-lock.yaml` | Updated 7 vulnerable transitive dependencies |

---

## Verification

- Dockerfile builds successfully with the `node` user (no UID conflict)
- `pnpm-lock.yaml` passes supply-chain policy verification
- All 17 previously flagged CVEs resolve to patched versions
- No direct dependency changes in `package.json`
