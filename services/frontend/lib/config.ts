// Central environment configuration for the storefront.
//
// Two switches control how the app talks to the backend:
//
//   NEXT_PUBLIC_MOCK      "true"  (default) → mock mode: no backend required,
//                                             lib/api.ts returns mock data.
//                         "false"           → real mode: calls the backend
//                                             through lib/api.ts.
//   NEXT_PUBLIC_API_BASE  Absolute base URL of the API gateway, e.g.
//                         "http://localhost:8080". Defaults to the gateway.
//                         Set it to an empty string ("") to route requests
//                         through the Next.js rewrites() proxy instead of
//                         fetching the gateway directly (same-origin /api/*).

const DEFAULT_API_BASE = 'http://localhost:8080'

/** True when the app should run against mock data (no backend required). */
export function isMockMode(): boolean {
  return (process.env.NEXT_PUBLIC_MOCK ?? 'true') !== 'false'
}

/**
 * Base URL used for real API calls. Trailing slashes are stripped so callers
 * can safely concatenate paths like "/api/catalog/products".
 *
 * An empty value is intentional: it means "same-origin", so fetch('/api/...')
 * flows through the rewrites() proxy declared in next.config.mjs.
 */
export function apiBase(): string {
  const raw = process.env.NEXT_PUBLIC_API_BASE ?? DEFAULT_API_BASE
  return raw.replace(/\/+$/, '')
}