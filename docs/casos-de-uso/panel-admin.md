# Panel de administración completo (estilo Medusa) — Spec

> **Objetivo:** definir el panel de back-office del e-commerce, inspirado en el admin de Medusa (módulos omnicanal: dashboard, catálogo, inventario, órdenes, clientes, pagos, envíos, devoluciones, notificaciones, analytics), pero **adaptado 1:1 a las clases y servicios que existen hoy** en `services/` (17 microservicios Spring Boot + Keycloak). Este documento es un spec de diseño: no toca código. Complementa `admin-ecommerce.md` (acciones actuales) y `sistema-ecommerce.md` (casos de uso del cliente).

---

## 1. Visión

Un panel de administración único para operar toda la plataforma:

| Principio | Decisión |
|---|---|
| **Inspiración** | Medusa Admin: navegación lateral por módulos, tablas con filtros, formularios en drawer/dialog, badges de estado, dashboard con métricas. |
| **Adaptación** | Cada módulo del panel mapea a una **entidad real** del repo (clases verificadas en §3). Nada inventado. |
| **Estado actual** | El backend hoy es **read-mostly**: las acciones de escritura existen pero son parciales (ver matriz CRUD §4). El spec define los gaps de API necesarios para un panel realmente completo (§5). |
| **Frontend** | SPA nueva (`admin-web`), separada del storefront. No toca `bff-web`. |
| **Auth** | Keycloak con rol `admin` (PKCE). ⚠️ La validación JWT aún no existe en el stack (ver §8). |

---

## 2. Arquitectura propuesta

```
┌─────────────────────────────┐
│  admin-web (SPA React)      │  Nuevo. NO toca bff-web.
│  React + TypeScript + Vite  │
│  TanStack Query + Tailwind  │
└──────────────┬──────────────┘
               │ OIDC PKCE (Keycloak, rol admin)
┌──────────────▼──────────────┐
│  api-gateway :8080          │  Rutas /api/<dominio>/** (reutiliza locator)
└──┬─────┬─────┬─────┬─────┬──┘
   ▼     ▼     ▼     ▼     ▼
catalog inventory order   ...   services (17)
```

| Capa | Tecnología | Responsabilidad |
|---|---|---|
| `admin-web` | React + TS + Vite + TanStack Query + Tailwind | Interfaz del panel. Equivalente ligero al admin de Medusa (React/Vite/Refine). |
| Keycloak | realm `ecommerce`, nuevo client público `ecommerce-admin` (PKCE) | Login y rol `admin`. **Pendiente:** validar JWT en gateway/servicios. |
| `api-gateway` :8080 | Spring Cloud Gateway | Reverse proxy hacia los services. Reutiliza las rutas `/api/*` existentes. |
| Services | 17 microservicios | Entregan los datos (endpoints §4 y gaps §5). |

**Alternativas descartadas:** (a) acceso directo a services desde el navegador (inseguro, expone puertos internos); (b) reutilizar `bff-web` (mezcla storefront con back-office, acopla dos audiencias); (c) panel dentro de Keycloak admin (funcionalidades de negocio no existen ahí).

---

## 3. Mapa entidad → módulo (todo verificado contra el código)

| # | Módulo del panel | Entidades / clases reales | Servicio (puerto) | Persistencia |
|---|---|---|---|---|
| 1 | Dashboard | `AnalyticsEvent`, `TopProduct`, `Summary` | analytics-svc :8096 | en memoria |
| 2 | Catálogo | `Product` (@Entity: id, name, description, price, stock, **category String**) | catalog-svc :8081 | H2/JPA |
| 3 | Categorías | **no existe** (`category` es texto libre en `Product`) | — | — |
| 4 | Inventario y reservas | `InventoryItem` (@Entity: id, productId, sku, name, stockLevel, reserved), `Reservation` (id, productId, quantity, expiresAt, status), `ReservationStatus` {RESERVED, RELEASED, CONFIRMED} | inventory-svc :8093 | H2/JPA (items) + memoria (reservas) |
| 5 | Órdenes | `Order` (@Entity: id, customerId, items, total, status, createdAt), `OrderItem` (@Embeddable: productId, quantity, price), `OrderStatus` {CREATED, PAID, SHIPPED, DELIVERED, CANCELLED} | order-svc :8084 | H2/JPA |
| 6 | Pagos | `Payment` (id, orderId, amount, method, status, createdAt), `PaymentStatus` {APPROVED, DECLINED} | payment-svc :8085 | en memoria |
| 7 | Envíos | `ShippingOrder` (id, orderId, address, status, carrier, trackingNumber, createdAt), `Address` (line1, city, postalCode, country), `ShippingStatus` {PENDING, SHIPPED, DELIVERED, FAILED} | shipping-svc :8094 | en memoria |
| 8 | Devoluciones | `ReturnRequest` (id, orderId, productId, reason, quantity, createdAt, status), `ReturnStatus` {RETURN_REQUESTED, APPROVED, REJECTED, PENDING_REVIEW} | returns-svc :8095 | en memoria |
| 9 | Notificaciones | `Notification` (id, type, recipient, subject, body, createdAt), `NotificationType` {ORDER_CONFIRMED, PAYMENT_RECEIVED, PAYMENT_DECLINED, SHIPPED, DELIVERED, PROMOTIONAL, SYSTEM} | notification-svc :8086 | en memoria |
| 10 | Clientes | **no hay entidad** — `customerId` es un String arbitrario; no existe servicio de clientes | — | — |
| 11 | Pagos/Checkout | `CheckoutRecord` (id, cartId, customerId, paymentMethod, total, status, createdAt) | checkout-svc :8083 | en memoria |
| 12 | Búsqueda y recomendaciones | `ProductSearchHit` (id, name, description, category, score), `Recommendation` (productId, name, category, score, reason) | search-svc :8090 / recommendation-svc :8091 | en memoria (catálogo semilla ids 1–8) |
| 13 | Carritos | `Cart` (id, items List<CartItem>), `CartItem` (productId, quantity, unitPrice) | cart-svc :8087 | en memoria |
| 14 | Usuarios y roles | Keycloak: usuarios, roles `admin`/`user`, clients (`ecommerce-web`, `ecommerce-api`) | auth (Keycloak 24) | Keycloak DB |

---

## 4. Matriz CRUD por entidad — qué puede hacer el panel HOY

> ✅ existe · ❌ falta · ◐ parcial / read-only

| Entidad | C | R | U | D | Endpoints actuales | Notas |
|---|---|---|---|---|---|---|
| `Product` | ✅ | ✅ | ✅ | ✅ | `POST/GET/GET{id}/PUT/DELETE /api/catalog/products` | CRUD completo. |
| `Category` | ❌ | ❌ | ❌ | ❌ | — | Es un String libre en Product. |
| `InventoryItem` | ❌ | ✅ | ✅ | ❌ | `GET /api/inventory`, `GET{productId}`, `PUT{productId}` | Stock vía PUT. Sin crear/eliminar items. |
| `Reservation` | ✅ | ❌ | ◐ | ❌ | `POST reserve/release`, `POST confirm/{id}` | No hay listado ni cancelación; TTL 15 min sin sweeper. |
| `Order` | ✅ | ✅ | ❌ | ❌ | `POST/GET/GET{id}/GET customer/{id}` | Estado forzado CREATED; **sin transiciones**. |
| `OrderItem` | ◐ | ✅ | ❌ | ❌ | (embebido en Order) | Solo se crea junto a la Order (máx. 50 items). |
| `Payment` | ✅ | ✅ | ❌ | ❌ | `POST/GET/GET{id}/GET order/{orderId}` | Immutable; sin refund. Sin idempotencia. |
| `CheckoutRecord` | ✅ | ✅ | ❌ | ❌ | `POST/GET/GET{id}` | |
| `ShippingOrder` | ✅ | ◐ | ✅ | ❌ | `POST`, `GET{id}`, `GET order/{orderId}`, `PATCH{id}/status` | **Sin listar todos** (`GET /api/shipping` no existe). |
| `ReturnRequest` | ✅ | ◐ | ✅ | ❌ | `POST`, `GET{id}`, `GET order/{orderId}`, `approve/reject` | **Sin listar todos**; approve/reject idempotentes (409 opuesto). |
| `Notification` | ✅ | ✅ | ❌ | ❌ | `POST/GET/GET{id}` | Sin DELETE (la lista crece infinito). |
| `AnalyticsEvent` | ✅ | ✅ | ❌ | ❌ | `POST events`, `GET events?type=` | Sin purge de eventos incorrectos. |
| `ProductSearchHit` | — | ✅ | — | — | `GET /api/search?q/category`, `GET /hot` | Read-only. |
| `Recommendation` | — | ✅ | — | — | `GET /api/recommendations`, `/similar` | Read-only. |
| `Cart` | ✅ | ◐ | ✅ | ✅ | `GET{cartId}`, `GET total`, `POST items`, `DELETE items/{productId}` | Solo por cartId conocido; sin listado admin ni purge. |
| `User` (Keycloak) | ✅ | ✅ | ✅ | ✅ | Consola Keycloak `/admin` | Gestión en la consola, no en la API. |

**Conclusión:** el panel puede ser **read-mostly desde el día 1** sobre todos los servicios (dashboard, catálogo, órdenes, pagos, inventario, analytics, envíos por orden, devoluciones por orden). La superficie **escritura completa** requiere los gaps de §5.

---

## 5. Gaps de API para un panel completo (roadmap de backend)

Priorizados por impacto admin:

| # | Gap | Endpoint propuesto | Por qué |
|---|---|---|---|
| 1 | **Transiciones de estado de orden** | `PATCH /api/orders/{id}/status` `{status: PAID\|SHIPPED\|DELIVERED\|CANCELLED}` | El admin debe poder embarcar/cancelar/marcar pagada. Validar máquina de estados `CREATED→PAID→SHIPPED→DELIVERED/CANCELLED` (409 en salto inválido). **Solo rol admin.** |
| 2 | **Categorías** | `Category` (@Entity) + `GET/POST/PUT/DELETE /api/catalog/categories` y `categoryId` en `Product` | Hoy la categoría es texto libre: la tienda no la puede gobernar. |
| 3 | **Reservas: listar y cancelar** | `GET /api/inventory/reservations?status=` + `POST /api/inventory/reservations/{id}/cancel` + sweeper de TTL | Sin esto no se ve stock comprometido ni se liberan reservas vencidas (el TTL 15 min hoy es decorativo). |
| 4 | **Envíos: listado global** | `GET /api/shipping?status=` | El módulo Envíos necesita una cola de trabajo, no consulta por orden. |
| 5 | **Devoluciones: listado global** | `GET /api/returns?status=` | La cola `PENDING_REVIEW` es la tarea principal del admin. |
| 6 | **Notificaciones: eliminar** | `DELETE /api/notifications/{id}` (o `DELETE /api/notifications`) | Higiene de la bandeja; hoy crece sin límite. |
| 7 | **Analytics: purgar** | `DELETE /api/analytics/events` (filtro por tipo opcional) | Corregir ingestas de prueba/erróneas. |
| 8 | **Clientes** | Módulo mínimo: `GET /api/customers` real o agregación (órdenes + analytics por customerId) | Hoy no hay listado de clientes — el panel más completo de Medusa lo tiene. Requiere decisión de producto. |
| 9 | **Reembolsos de pago** | `POST /api/payments/{id}/refund` (idempotente) + `PaymentStatus {REFUNDED}` | `Payment` es inmutable y no tiene refund. |
| 10 | **Auth real** | Ruta `/api/auth` + filtros JWT en gateway/servicios con rol `admin` | Sin esto el "panel admin" no tiene candado. Prerrequisito para exponer cualquier endpoint de §5. |
| 11 | **Carritos: visibilidad admin** | `GET /api/cart` (lista) + purge de carritos abandonados | Detección de carritos viejos / limpieza. |

---

## 6. Diseño de módulos (especificación de pantallas)

Patrón común: **sidebar de módulos** + **tabla con filtros** + **detalle en página** + **formularios en drawer** + **badges de estado** + **toasts de confirmación**.

### 6.1 Dashboard (`analytics-svc`)
- KPI cards: órdenes totales, pagos aprobados/rechazados, stock crítico, devoluciones pendientes.
- Charts: `GET /api/analytics/reports/top-products?limit=10` (top vendidos), `Summary`.
- Tabla "Actividad reciente": `GET /api/analytics/events?type=PURCHASE`.

### 6.2 Catálogo (`catalog-svc`) — CRUD completo
- Lista: `GET /api/catalog/products?category=` → columnas id, nombre, categoría, precio, stock, acciones (editar/eliminar).
- Crear/editar (drawer form): `POST` / `PUT /api/catalog/products/{id}` — campos name, description, price, stock, category.
- Eliminar: confirm dialog → `DELETE` (toast 204).

### 6.3 Inventario y reservas (`inventory-svc`)
- Tabla stock: `GET /api/inventory` → columnas productId, sku, name, stockLevel, reserved; acción "Ajustar stock" → `PUT /api/inventory/{productId}`.
- Pestaña Reservas (gap #3): tabla de reservas activas con botón cancelar.

### 6.4 Órdenes (`order-svc`)
- Lista: `GET /api/orders` + filtro por cliente: `GET /api/orders/customer/{customerId}`.
- Detalle: ítems, total, estado; **acciones de transición** (gap #1): botón *Marcar pagada*, *Embarcar*, *Entregar*, *Cancelar* según estado actual.
- Badges de estado: CREATED(azul) → PAID(violeta) → SHIPPED(ámbar) → DELIVERED(verde) / CANCELLED(rojo).

### 6.5 Pagos (`payment-svc`)
- Lista: `GET /api/payments`, por orden: `GET /api/payments/order/{orderId}`.
- Badges: APPROVED (verde) / DECLINED (rojo). Acción futura: Reembolsar (gap #9).

### 6.6 Envíos (`shipping-svc`)
- Cola de trabajo (con gap #4): `GET /api/shipping?status=` → pestañas PENDING / SHIPPED / DELIVERED / FAILED.
- Acción "Marcar enviado" → `PATCH /api/shipping/{id}/status` `{status: SHIPPED}` (tracking `EC########`); "Marcar entregado" → DELIVERED. 409 si se salta la máquina de estados.

### 6.7 Devoluciones (`returns-svc`)
- Cola (con gap #5): pestaña `PENDING_REVIEW` primero → aprobar/rechazar (`POST /{id}/approve|reject`).
- Detalle del motivo/ventana de 30 días/qty>10 que disparó la revisión manual.

### 6.8 Notificaciones (`notification-svc`)
- Lista: `GET /api/notifications` (con gap #6: delete).
- "Nueva notificación": `POST /api/notifications` con selector de `NotificationType` (7 tipos) + destinatario + asunto + cuerpo.

### 6.9 Analytics (`analytics-svc`)
- Eventos: `GET /api/analytics/events?type=` (VIEW/ADD_TO_CART/PURCHASE/SEARCH/CLICK).
- Reportes: top-products (chart de barras) + summary.

### 6.10 Búsqueda / Recomendaciones / Carritos (read-only o futuro)
- Search: ver término calientes `GET /api/search/hot`.
- Recomendaciones: `GET /api/recommendations?customerId=` (vista de prueba).
- Carritos: con gap #11, listado y purge.

### 6.11 Usuarios y accesos
- Redirigir a consola Keycloak `/admin`; en el panel solo mostrar el usuario logueado y su rol.

---

## 7. Prompt de diseño UI/UX Pro Max para el panel

Igual metodología que `sistema-ecommerce.md` (skill `nicohodt/claude-code-ui-ux-skill` → UI/UX Pro Max). Prompt listo para pegar:

```markdown
# Admin Panel — UI/UX Pro Max
Diseñá el design system y las páginas del panel de administración de un e-commerce universitario (stack: React + TypeScript + Vite + Tailwind), inspirado en Medusa Admin.

## Páginas obligatorias
admin/login, admin/dashboard, admin/products (lista + form drawer), admin/inventory (stock + reservas), admin/orders (lista + detalle con acciones de transición), admin/payments, admin/shipping (cola por estado), admin/returns (cola de revisión), admin/notifications (lista + crear), admin/analytics (charts), admin/users (link a Keycloak), admin/settings.

## Reglas de negocio a reflejar en la UI
- Órdenes solo avanzan CREATED→PAID→SHIPPED→DELIVERED/CANCELLED: mostrar badges de estado y habilitar/deshabilitar acciones según la máquina de estados.
- Envíos: solo PENDING→SHIPPED(EC########)→DELIVERED; 409 en saltos → toast de error.
- Devoluciones: cola PENDING_REVIEW primero (aprobar/rechazar son idempotentes).
- Pagos APPROVED/DECLINED; cardLast4 0000 → DECLINED.
- Inventario: ajuste de stock manual (PUT) + reservas con TTL 15 min configurable.
- Sin idempotencia en pagos: los botones de crear deben deshabilitarse en el envío y verificar antes de reintentar (GET /payments/order/{id}).

## Flujo del skill (aplicar completo)
1. Analizar el contexto (usuario: admin técnico; tareas: CRUD + colas de aprobación + supervisión).
2. Generar design system: PATTERN (dashboard admin, sidebar, tablas densas), STYLE, COLORS (contraste AA, estados semánticos), TYPOGRAPHY, EFFECTS, ANTI-PATTERNS.
3. Buscar referencias con la herramienta de búsqueda del skill (--domain admin dashboard / tables / status badges).
4. Chequear guías de stack (React + Tailwind).
5. Pre-delivery checklist (contraste 4.5:1, target 44×44px, CLS<0.1, reduced-motion, SVG icons sin emoji, error states y empty states).
6. Persistir con --design-system --persist → design-system/admin-panel/MASTER.md + pages/admin/*.
```

---

## 8. Limitaciones y decisiones de diseño

1. **Sin candado real:** la validación JWT **no existe** en el stack (ni gateway ni services). El panel SOLO debe exponerse en producción después de implementar auth (gap #10). Hasta entonces, es una herramienta de desarrollo/demo.
2. **In-memory:** checkout, payment, notification, shipping, returns, analytics, search, recommendation y reservas viven en memoria → los datos del panel se pierden al reiniciar el pod. Refrescar y proyectar: el panel es un espejo, no la fuente de verdad (la fuente de verdad durable hoy: catalog, order, inventory vía H2).
3. **Doble POST:** sin idempotencia en pagos, la UI debe serializar (botón loading, query primero) para no duplicar cobros.
4. **Catálogo semilla:** búsqueda y recomendaciones operan sobre ids 1–8 estáticos, no el catálogo vivo; los paneles de search/recs son ilustrativos hasta conectar `search-svc` con `catalog-svc`.
5. **No tocar `bff-web`:** el storefront usa sus endpoints `/api/bff/**`; el panel consume el gateway directo.
6. **Frontend nuevo ≠ feature sencilla:** es un cambio grande (frontend + gaps de backend + auth). Conviene un ciclo SDD (`/sdd-new panel-admin`) con slices: Fase 0 scaffolding → Fase 1 read-only → Fase 2 acciones existentes → Fase 3 gaps → Fase 4 auth+clientes+refunds.

---

## 9. Fases de implementación sugeridas

| Fase | Entregable | Depende de |
|---|---|---|
| **F0** | Scaffold `admin-web` (Vite+React+TS+Tailwind), layout con sidebar, login PKCE (visual), ruta al panel | — |
| **F1** | Módulos read-only: dashboard, catálogo, órdenes, pagos, inventario, analytics | F0 |
| **F2** | Acciones admin existentes: PATCH envíos, approve/reject devoluciones, POST notificaciones, PUT stock | F1 |
| **F3** | Gaps #1–#7 (transiciones orden, categorías, listas de reservas/envíos/devoluciones, deletes) | F2 |
| **F4** | Gaps #8–#11 (clientes, refunds, auth real, carritos admin) — **prerrequisito para producción** | F3 |

---

*Fuentes: código fuente de `services/` (clases de dominio y controllers verificados por sub-agente), `docs/casos-de-uso/admin-ecommerce.md`, `docs/casos-de-uso/sistema-ecommerce.md`, memoria de investigación (mapa de capacidades).*