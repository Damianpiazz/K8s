# NOVA · Frontend (Next.js)

Tienda de demostración y panel operativo para el e-commerce de microservicios.
El frontend funciona en **dos modos**:

| Modo | `NEXT_PUBLIC_MOCK` | Comportamiento |
|------|--------------------|----------------|
| **Mock** (por defecto) | `true` | Todos los datos y reglas de negocio se resuelven localmente en `lib/api.ts` / `lib/mock-data.ts`. No necesita backend. |
| **Real** | `false` | Consume los microservicios vía el gateway API (`:8080`) y el BFF (`:8090`). |

## Requisitos

- Node.js ≥ 20 (probado con Node 24)
- pnpm vía Corepack: `corepack pnpm install`

## Puesta en marcha

```bash
corepack pnpm install
corepack pnpm dev        # http://localhost:3000
```

Compilación de producción:

```bash
corepack pnpm build
corepack pnpm start
```

## Configuración

Copia `.env.example` a `.env.local` y ajusta los valores:

| Variable | Por defecto | Descripción |
|----------|-------------|-------------|
| `NEXT_PUBLIC_MOCK` | `true` | `true` → demo local; `false` → servicios reales. |
| `NEXT_PUBLIC_API_BASE` | `http://localhost:8080` | Base del gateway API (modo real). Si se deja vacío, las llamadas `/api/*` se reenvían por rewrites de Next.js. |

En modo real el navegador habla con el mismo origen y `next.config.mjs` reenvía:

- `/api/bff/*` → `http://localhost:8090/api/bff/*` (BFF)
- `/api/*` → `http://localhost:8080/api/*` (Gateway → microservicios)

## Arquitectura de datos

- La **tienda** (`app/page.tsx`) usa el BFF (`GET /api/bff/home`, `GET /api/bff/cart/{cartId}`), con degradación controlada.
- El **panel operativo** (`OpsDashboard`) consulta los microservicios directamente a través del gateway: catálogo, carrito, pedidos, pagos, notificaciones, inventario, envíos, devoluciones, analítica, búsqueda y recomendaciones.
- `lib/api.ts` expone una función tipada por endpoint; cada una resuelve la respuesta real o el mock según el modo. Los mensajes de error se traducen en la frontera de UI (`uiErrorMessage`, español neutro).

## Reglas de negocio reflejadas

- Pago rechazado si `cardLast4 === '0000'`.
- Envíos: `PENDING → SHIPPED → DELIVERED`; transiciones inválidas → `409`.
- Devoluciones: aprobar una rechazada (o viceversa) → `409`; operaciones repetidas son idempotentes.
- Inventario: reservar sin stock disponible → `409`.
- Carrito: máximo 50 unidades por producto.

## Notas sobre endpoints inexistentes

- No existe `GET /api/shipping` (lista global): en modo real se hace fan-out sobre los pedidos (`/api/shipping/order/{orderId}`).
- No existe `GET /api/returns` (lista global): se obtienen por fan-out sobre los pedidos (`/api/returns/order/{orderId}`).
- No existe endpoint de clientes: el panel de clientes se deriva de `customerId` de los pedidos.
- No existe transición de estado de pedido ni endpoint de exportación de informe (el botón «Exportar informe» explica la alternativa en pantalla).

## Despliegue en Kubernetes

### Modelo de enrutamiento

En producción el frontend se despliega detrás de su propio **Ingress** (nginx +
cert-manager + external-dns). El enrutamiento por path en el Ingress reemplaza
los `rewrites()` de Next.js (que solo funcionan en modo dev/localhost):

| Path | Servicio destino | Puerto |
|------|-----------------|--------|
| `/api/bff/*` | `bff-web` | 8090 |
| `/api/*` | `api-gateway` | 8080 |
| `/*` | `frontend` | 3000 |

El navegador llama a `/api/*` en el mismo origen; el Ingress distribuye al
servicio correspondiente. **No se necesitan `rewrites()` en prod** porque
`NEXT_PUBLIC_API_BASE` queda vacío (same-origin).

### Docker build

```bash
docker build \
  --build-arg NEXT_PUBLIC_MOCK=false \
  -t acr.azurecr.io/frontend:latest \
  services/frontend
```

La imagen usa **Next standalone** (`output: 'standalone'` en `next.config.mjs`):
un servidor Node.js autocontenido en `/app/server.js` con todas las dependencias
mínimas empaquetadas. Las imágenes estáticas (`.next/static`) y los archivos
públicos (`public/`) se copian al directorio standalone.

### CI/CD

El pipeline (`ci.yml` + `cd.yml`) incluye `frontend` en la matriz de servicios.
El build usa `services/frontend` como contexto Docker (el `reusable-build.yml`
resuelve `context: services/frontend` automáticamente). Las imágenes se publican
en ACR y los tags se actualizan en los overlays de kustomize.

### Modo de ejecución (resumen)

| Entorno | `NEXT_PUBLIC_MOCK` | Ruta al backend |
|---------|--------------------|-----------------|
| **Desarrollo** (localhost) | `true` (default) | Mock local — sin backend |
| **Dev real** (localhost) | `false` | `rewrites()` → `localhost:8080/8090` |
| **Producción** (cluster) | `false` (baked en la imagen) | Ingress path routing → `bff-web` / `api-gateway` |

## Idiomas

- Interfaz y mensajes: **español neutro**.
- Datos semilla del catálogo (nombres de producto, categorías, descripciones): **inglés**, porque reflejan el seed de `catalog-svc` del backend.