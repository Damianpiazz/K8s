/** @type {import('next').NextConfig} */

// Rewrite proxy for real mode (NEXT_PUBLIC_MOCK=false).
// The browser talks to the same origin and Next.js forwards /api/bff/* to the
// BFF (:8090 surface) and /api/* to the API gateway. In mock mode no rewrites
// are defined: lib/api.ts resolves everything locally.
//
// PRODUCTION (standalone server): rewrites are DISABLED on purpose. The image
// is served behind its own Ingress which path-routes /api/bff → bff-web and
// /api → api-gateway (same-origin). Baking a proxy target here would point at
// localhost:8080 inside the container — dead. Dev (next dev) keeps the proxy.
async function rewrites() {
  const mockMode = (process.env.NEXT_PUBLIC_MOCK ?? 'true') !== 'false'
  if (mockMode) return []
  // Build-time gate: `next build` runs with NODE_ENV=production, so the prod
  // standalone server gets NO rewrites baked in (pass-through → Ingress).
  if (process.env.NODE_ENV === 'production') return []
  const base = (process.env.NEXT_PUBLIC_API_BASE || 'http://localhost:8080').replace(/\/+$/, '')
  return [
    { source: '/api/bff/:path*', destination: `${base}/api/bff/:path*` },
    { source: '/api/:path*', destination: `${base}/api/:path*` },
  ]
}

const nextConfig = {
  output: 'standalone',
  typescript: {
    ignoreBuildErrors: true,
  },
  images: {
    unoptimized: true,
  },
  rewrites,
}

export default nextConfig