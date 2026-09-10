# Sistema de e-commerce: casos de uso y acciones posibles

> **Alcance:** inventario completo, verificado contra el código fuente, de las acciones que un usuario o sistema puede realizar sobre la plataforma de microservicios (17 servicios Spring Boot + Keycloak, desplegados en AKS con Kustomize/Argo CD). El documento también incluye, al final, un **prompt de diseño de página (UI/UX Pro Max)** para construir la interfaz del storefront siguiendo las prácticas del skill `nicohodt/claude-code-ui-ux-skill`.

---

## 1. Resumen de la plataforma

La plataforma se compone de 17 servicios Spring Boot, un Keycloak (auth) y dos piezas de infraestructura (config server y discovery). La superficie de uso es de **dos capas**:

| Capa | Puerto | Responsabilidad |
|---|---|---|
| **API Gateway** | 8080 | Única entrada pública para clientes: rutas `/api/<dominio>` hacia cada servicio, CORS global. |
| **BFF Web (storefront)** | 8090 | Fachada para la tienda: agrega catálogo + carrito en 2 llamadas (`/api/bff/home`, `/api/bff/cart/{cartId}`). Degrada parcialmente si un backend cae. |
| **Servicios de dominio** | 8081–8096 | Lógica de negocio por dominio (catálogo, carrito, checkout, órdenes, pagos, inventario, envíos, devoluciones, notificaciones, búsqueda, recomendaciones, analytics). |
| **Auth (Keycloak)** | 8080 (ingress propio) | Realm `ecommerce`, clientes `ecommerce-web` (public/PKCE) y `ecommerce-api` (confidential/M2M), roles `admin` y `user`. |
| **Config / Discovery** | 8888 / 8761 | Config centralizada y registro de servicios (Eureka). |

**Rutas de gateway disponibles:** `/api/catalog/**`, `/api/cart/**`, `/api/orders/**`, `/api/payments/**`, `/api/notifications/**`, `/api/checkout/**`, `/api/bff/**`, `/api/search/**`, `/api/recommendations/**`, `/api/inventory/**`, `/api/shipping/**`, `/api/returns/**`, `/api/analytics/**`, y `/api/customers/**` (placeholder, servicio aún no desplegado).

---

## 2. Actores

| Actor | Qué puede hacer | Superficie |
|---|---|---|
| **Cliente anónimo** | Explorar catálogo, buscar, ver recomendaciones, operar un carrito de sesión, ver la home del storefront | BFF + catálogo/búsqueda/recomendaciones/carrito |
| **Cliente comprador** | Checkout, crear órdenes, pagar, crear envíos, rastrear, iniciar devoluciones, recibir notificaciones | checkout/orders/payments/shipping/returns/notifications |
| **Operador / admin** | CRUD de catálogo, ajuste de stock, aprobar/rechazar devoluciones, avanzar estados de envío, enviar notificaciones, consultar analytics | Todos los servicios administrativos |
| **Sistema interno** | Reservar/liberar stock, emitir eventos de analytics, consumir eventos de Kafka (notificaciones) | inventory/analytics/notification (Kafka) |
| **Ops / DevOps** | Config de servicios, descubrimiento, health/stats, secretos vía External Secrets | config-service, discovery-service, actuadores |

> **Nota de estado:** Keycloak expone OIDC completo para login (PKCE para la web, M2M para API), pero **nada en el stack Java valida JWTs todavía**: no existe ruta `/api/auth` en el gateway ni filtros de validación. El login es usable como emisor de tokens, no como guarda de autorización.

---

## 3. Catálogo de casos de uso por dominio

Formato: `MÉTODO /ruta` — acción (condiciones y reglas de negocio relevantes).

### 3.1 Catálogo (catalog-svc · 8081) — productos

| Acción | Endpoint | Reglas |
|---|---|---|
| Listar productos | `GET /api/catalog/products?category=` | Filtro por categoría, case-insensitive (devuelve 404 si la categoría no existe). |
| Ver detalle de producto | `GET /api/catalog/products/{id}` | |
| Crear producto | `POST /api/catalog/products` | 201. **Nunca confía en el id enviado por el cliente** (se fuerza nulo). |
| Actualizar producto | `PUT /api/catalog/products/{id}` | |
| Eliminar producto | `DELETE /api/catalog/products/{id}` | 204 / 404. |

### 3.2 Búsqueda (search-svc · 8091)

| Acción | Endpoint | Reglas |
|---|---|---|
| Buscar productos | `GET /api/search?q=&category=` | Ranking con scoring: nombre=3, descripción=1, tag=1. `q` vacío → lista vacía. Máx. 10 resultados. |
| Ver términos calientes | `GET /api/search/hot` | Top 5 términos de búsqueda más consultados. |

### 3.3 Recomendaciones (recommendation-svc · 8092)

| Acción | Endpoint | Reglas |
|---|---|---|
| Recomendaciones por cliente | `GET /api/recommendations?customerId=&limit=` | Default 5, máx. 10. Basado en compras compartidas + boost por categoría. |
| Productos similares | `GET /api/recommendations/{customerId}/similar?productId=&limit=` | Also-bought; si no hay datos, cae a misma categoría. |

### 3.4 Carrito (cart-svc · 8082)

| Acción | Endpoint | Reglas |
|---|---|---|
| Obtener/crear carrito | `GET /api/cart/{cartId}` | Get-or-create. `cartId` es un id de sesión opaco; **no hay merge-on-login** (el carrito no se vincula a identidad todavía). |
| Agregar/actualizar línea | `POST /api/cart/{cartId}/items` | Merge por `productId`: mismo producto → suma cantidades. `qty > 0` obligatorio. |
| Quitar línea | `DELETE /api/cart/{cartId}/items/{productId}` | |
| Ver total | `GET /api/cart/{cartId}/total` | |

### 3.5 Storefront (bff-web · 8090)

| Acción | Endpoint | Reglas |
|---|---|---|
| Home de la tienda | `GET /api/bff/home` | Grid de productos + hero en una sola llamada. |
| Carrito enriquecido | `GET /api/bff/cart/{cartId}` | Carrito + nombre/precio de cada producto. |
| Degradación parcial | (cualquiera de las anteriores) | Si un backend cae: respuesta parcial con `degraded: true` (grid vacío o ítem degradado), nunca 500. |

### 3.6 Checkout (checkout-svc · 8083)

| Acción | Endpoint | Reglas |
|---|---|---|
| Iniciar checkout | `POST /api/checkout` | Body `{cartId, customerId, paymentMethod}` → 201. Carga carrito → total → registra pedido en memoria. |
| Ver checkout por id | `GET /api/checkout/{id}` | |
| Listar checkouts | `GET /api/checkout` | |
| Resiliencia | — | Si cart-svc está caído → **carrito mock vacío** (nunca se pierde una venta por caída de otro servicio). |

### 3.7 Órdenes (order-svc · 8084)

| Acción | Endpoint | Reglas |
|---|---|---|
| Crear orden | `POST /api/orders` | 201. Validaciones: items no vacíos, `qty > 0`, **máx. 50 ítems**. Estado **forzado a CREATED**. |
| Listar órdenes | `GET /api/orders` | |
| Ver orden por id | `GET /api/orders/{id}` | |
| Órdenes de un cliente | `GET /api/orders/customer/{customerId}` | |

### 3.8 Pagos (payment-svc · 8085)

| Acción | Endpoint | Reglas |
|---|---|---|
| Procesar pago | `POST /api/payments` | Body `{orderId, amount, method, cardLast4}` → 201. `amount <= 0` → **DECLINED**. `cardLast4` terminada en `0000` → **DECLINED**. Caso contrario → **APPROVED**. |
| Ver pago por id | `GET /api/payments/{id}` | |
| Pagos de una orden | `GET /api/payments/order/{orderId}` | |
| Listar pagos | `GET /api/payments` | |

### 3.9 Inventario (inventory-svc · 8093)

| Acción | Endpoint | Reglas |
|---|---|---|
| Ver todo el stock | `GET /api/inventory` | |
| Ver stock de un producto | `GET /api/inventory/{productId}` | |
| Fijar stock disponible | `PUT /api/inventory/{productId}` | Set del on-hand. |
| **Reservar stock** | `POST /api/inventory/reserve` | 201; **409 si stock insuficiente**. `synchronized` (sin overbooking entre requests). |
| Liberar reserva | `POST /api/inventory/release` | |
| Confirmar reserva | `POST /api/inventory/confirm/{reservationId}` | Cierra el ciclo reserva → confirmación. |

### 3.10 Envíos (shipping-svc · 8094)

| Acción | Endpoint | Reglas |
|---|---|---|
| Crear envío | `POST /api/shipping` | Body `{orderId, address}` → 201. Carrier default: "Ecommerce Express". |
| Ver envío por id | `GET /api/shipping/{id}` | |
| Envíos de una orden | `GET /api/shipping/order/{orderId}` | |
| Actualizar estado | `PATCH /api/shipping/{id}/status` | Máquina de estados: `PENDING → SHIPPED` (asigna tracking `EC########`) → `DELIVERED`. **Cualquier otra transición → 409.** |

### 3.11 Devoluciones (returns-svc · 8095)

| Acción | Endpoint | Reglas |
|---|---|---|
| Iniciar devolución | `POST /api/returns` | Body `{orderId, productId, reason, quantity}` → 201. **Auto-APROBADA** si: motivo ∈ {defective, wrong-item, not-as-described} **y** dentro de ventana de 30 días **y** qty ≤ 10. Si no, `PENDING_REVIEW`. |
| Ver devolución | `GET /api/returns/{id}` | |
| Devoluciones de una orden | `GET /api/returns/order/{orderId}` | |
| Aprobar | `POST /api/returns/{id}/approve` | Idempotente sobre el mismo estado; transición opuesta → 409. |
| Rechazar | `POST /api/returns/{id}/reject` | Ídem. |

### 3.12 Notificaciones (notification-svc · 8086)

| Acción | Endpoint | Reglas |
|---|---|---|
| Crear notificación | `POST /api/notifications` | 201. Valida tipo. |
| Ver notificación | `GET /api/notifications/{id}` | |
| Listar notificaciones | `GET /api/notifications` | |
| Notificaciones automáticas | (Kafka, deshabilitado por defecto) | Si `notifications.kafka.enabled=true`, consume `order-events`/`payment-events` → `ORDER_CONFIRMED` / `PAYMENT_RECEIVED`. |
| Tipos válidos | — | `ORDER_CONFIRMED`, `PAYMENT_RECEIVED`, `PAYMENT_DECLINED`, `SHIPPED`, `DELIVERED`, `PROMOTIONAL`, `SYSTEM`. |

### 3.13 Analytics (analytics-svc · 8096)

| Acción | Endpoint | Reglas |
|---|---|---|
| Registrar evento | `POST /api/analytics/events` | 201. Tipo libre (`VIEW`, etc.). Buffer en memoria máx. 10 000 eventos. |
| Consultar eventos | `GET /api/analytics/events?type=` | Filtro opcional por tipo. |
| Top productos | `GET /api/analytics/reports/top-products?limit=` | Default 5. |
| Resumen | `GET /api/analytics/reports/summary` | |

### 3.14 Auth (Keycloak · 24) — identidad

| Acción | Endpoint | Reglas |
|---|---|---|
| Login web (PKCE) | `openid-connect` sobre `realms/ecommerce` | Cliente público `ecommerce-web`. |
| Login API (M2M) | Ídem, client credentials | Cliente confidencial `ecommerce-api`. |
| Descubrimiento OIDC | `GET /realms/ecommerce/.well-known/openid-configuration` | |
| Admin de realm | `/admin` | |

### 3.15 Infraestructura (config-service · discovery-service)

| Acción | Endpoint | Reglas |
|---|---|---|
| Servir config de un servicio | `GET /{app}/{profile}` (config-service) | P. ej. `/api-gateway/default`. |
| Health / métricas | `GET /actuator/{health,info,prometheus,metrics,env}` | |
| Dashboard de descubrimiento | `/` y `/eureka/` (discovery-service) | Eureka standalone, eviction 30 s. |

---

## 4. Viaje de extremo a extremo (compra completa)

1. **Home:** `GET /api/bff/home` → grid + hero.
2. **Búsqueda:** `GET /api/search?q=keyboard&category=peripherals` → resultados rankeados.
3. **Recomendaciones:** `GET /api/recommendations?customerId=C12&limit=5` → "otros también compraron".
4. **Carrito:** `POST /api/cart/sess-abc/items` (merge) → `GET /api/cart/sess-abc/total`.
5. **Checkout:** `POST /api/checkout {cartId, customerId, paymentMethod}` → 201.
6. **Orden:** `POST /api/orders` → estado `CREATED` (validación máx. 50 ítems en el camino).
7. **Pago:** `POST /api/payments {orderId, amount, method, cardLast4}` → APPROVED (o DECLINED con tarjeta `0000`).
8. **Inventario:** `POST /api/inventory/reserve` → 201 (o 409 sin stock).
9. **Envío:** `POST /api/shipping {orderId, address}` → PENDING; `PATCH .../status` → SHIPPED (tracking `EC########`) → DELIVERED.
10. **Notificación:** `POST /api/notifications` → `SHIPPED` (o automática vía Kafka si está habilitado).
11. **Devolución (si aplica):** `POST /api/returns` → auto-aprobada (motivo `defective`, dentro de 30 días, qty ≤ 10) o `PENDING_REVIEW` → `approve`/`reject`.
12. **Analytics:** `POST /api/analytics/events` en cada paso clave → `GET /api/analytics/reports/top-products`.

---

## 5. Resiliencia y degradación

- **BFF:** nunca responde 500 por caída de un backend — responde parcial con `degraded: true`.
- **Checkout:** si cart-svc cae, usa carrito mock vacío ("never lose a sale").
- No hay circuit breaker (resilience4j) ni rate limiter activo en el gateway (la dependencia de Redis está comentada).
- El resto de servicios en memoria: reinicio = pérdida de datos (salvo catalog/order/inventory que usan H2).

---

## 6. Limitaciones conocidas (importante)

1. **Auth no integrado:** los tokens de Keycloak no se validan en gateway ni servicios (sin guardas JWT).
2. **Ciclo de vida de órdenes acotado:** el enum `CREATED→PAID→SHIPPED→DELIVERED/CANCELLED` existe pero **no hay endpoints de transición**; el pago no cambia el estado de la orden.
3. **Pagos sin idempotencia:** un `POST /api/payments` repetido crea pagos duplicados.
4. **Reservas de inventario:** el TTL de 15 min es solo configuración; **no hay sweeper** que libere reservas vencidas.
5. **Búsqueda y recomendaciones usan catálogos semilla estáticos** (ids 1–8), no el catálogo vivo de catalog-svc.
6. **Persistencia mayormente en memoria** (cart/checkout/payment/notification/shipping/returns/analytics/search/recommendation); Redis y Kafka están presentes pero la mayoría inactivos.
7. **`/api/customers/**` es una ruta placeholder** (servicio no desplegado).

---

## 7. Prompt de diseño de página — método UI/UX Pro Max

Este prompt sigue el **workflow del skill `claude-code-ui-ux-skill` (ui-ux-pro-max)**: análisis de requisitos → generación de **design system** (pattern, style, colores, tipografía, efectos, anti-patrones) → búsquedas de refuerzo → guías de stack → **pre-delivery checklist**. Pegalo en tu asistente de IA (Claude Code, Cursor, OpenCode, etc.) con la skill instalada, o úsalo standalone; el asistente aplicará las mismas reglas.

```text
Eres un diseñador UI/UX senior que aplica la metodología UI/UX Pro Max
(skill claude-code-ui-ux-skill). NO escribas código todavía: primero generá
el design system completo y después la implementación.

## Paso 1 — Análisis de requisitos
- Producto: e-commerce tecnológico B2C (storefront + paneles de operador),
  backend Spring Boot ya definido: BFF en /api/bff (home y carrito), y rutas
  /api/catalog, /api/search, /api/recommendations, /api/cart, /api/checkout,
  /api/orders, /api/payments, /api/inventory, /api/shipping, /api/returns,
  /api/notifications, /api/analytics.
- Audiencia: consumidores 18–45, compras por impulso y por investigación,
  desktop + mobile.
- Keywords de estilo: e-commerce, retail, tecnología, moderno, limpio,
  orientado a conversión, confianza.
- Stack de implementación: detectar del proyecto; si no hay front aún,
  usar HTML + Tailwind (default del skill). No asumas otro stack.

## Paso 2 — Generar el design system (OBLIGATORIO, antes de escribir CSS)
Si la skill está instalada, ejecutá:
  python "${CLAUDE_PLUGIN_ROOT}/.claude/skills/ui-ux-pro-max/scripts/search.py" \
    "e-commerce retail technology storefront conversion trust" --design-system \
    --variance 5 --motion 5 --density 5 -p "Ecommerce Platform"
Debés entregar, con o sin script:
- PATTERN (layout de landing/tienda recomendado, p. ej. Hero-Centric + Social Proof)
- STYLE (uno de los 84 estilos — NO el default "AI purple", elegí el que
  matchee el producto)
- COLORS (paleta con Primary/Secondary/CTA/Background/Text; verifica contraste)
- TYPOGRAPHY (pareja de fuentes, p. ej. de las 74 curated pairings)
- KEY EFFECTS (sombras, transiciones 150–300 ms, hovers)
- ANTI-PATTERNS a evitar para e-commerce (NO: emojis como iconos, gradientes
  púrpura genéricos, hover-only, carruseles automáticos sin pausa, checkout
  de más de 3 pasos sin hints, texto < 12px)

## Paso 3 — Búsquedas de refuerzo (según necesidad)
- --domain landing  → estructura de hero + conversión
- --domain ux       → forms/checkout errors, nav, feedback de carga
- --domain product  → e-commerce patterns
- --domain color    → retail/e-commerce palettes
- --domain icons    → iconos SVG (Heroicons/Lucide) para shopping
- --domain chart    → paneles admin (analytics)

## Paso 4 — Guías de stack
- --stack html-tailwind (o el stack detectado). Aplicar las guías
  específicas del stack elegido en la implementación.

## Páginas a diseñar (todas con el mismo design system)
1. Home del storefront (usa GET /api/bff/home: grid productos + hero)
2. Detalle de producto (usa GET /api/catalog/products/{id})
3. Resultados de búsqueda (usa GET /api/search?q=&category=)
4. Carrito (usa GET /api/bff/cart/{cartId}; manejar degraded:true)
5. Checkout (usa POST /api/checkout) — 3 pasos máx., feedback claro
6. Confirmación de orden + estado (POST /api/orders, GET /api/orders/{id})
7. Pago (POST /api/payments; mostrar DECLINED explicado, sin culpar)
8. Tracking de envío (PATCH /api/shipping/{id}/status + tracking EC###)
9. Devoluciones (POST /api/returns + approve/reject)
10. Panel admin: inventario (PUT /api/inventory/{id}, reservas)
11. Panel admin: analytics (GET /api/analytics/reports/*) — dashboard
    data-dense con tooltips y legends accesibles

## Estado de degradación (OBLIGATORIO)
El sistema devuelve degraded:true cuando un backend cae. La UI debe
diseñar estados vacíos/parciales elegantes (skeleton, empty state con CTA,
nunca un error roto).

## Pre-delivery checklist (verificá CADA ítem antes de decir "done")
- Contraste texto/fondo ≥ 4.5:1 (WCAG AA); componentes ≥ 3:1
- Alt text, keyboard nav completa, focus states visibles
- Targets táctiles ≥ 44×44 px; spacing ≥ 8 px
- Loading feedback (skeletons/spinners) en toda acción async
- Lazy loading imágenes (WebP/AVIF), sin CLS (reserva de espacio)
- Iconos SVG (Heroicons/Lucide), NUNCA emojis como iconos
- Base 16px, line-height 1.5, sin texto < 12px
- Responsive: 375 / 768 / 1024 / 1440, sin scroll horizontal
- prefers-reduced-motion respetado; animaciones 150–300 ms
- Formularios con labels visibles, errores cerca del campo
- Nav predecible, back correcto, deep links funcionando
- Charts con legends + tooltips, no color-only

## Resultado esperado
Primero el design system completo en un bloque (PATTERN / STYLE / COLORS /
TYPOGRAPHY / EFFECTS / ANTI-PATTERNS / CHECKLIST), luego la implementación
por página usando tokens del design system (CSS vars), no hex sueltos.
```

### Persistencia del design system (práctica recomendada del skill)

Para reutilizar las decisiones entre sesiones, guardá el master y las páginas:

```bash
python "${CLAUDE_PLUGIN_ROOT}/.claude/skills/ui-ux-pro-max/scripts/search.py" \
  "e-commerce retail technology storefront" --design-system --persist \
  -p "Ecommerce Platform" --output-dir "<raíz-del-proyecto>"
```

Esto genera `design-system/<slug>/MASTER.md` y `design-system/<slug>/pages/`. En la próxima sesión de UI, la instrucción es: *"Leé `design-system/<slug>/MASTER.md`; si existe `pages/<página>.md`, sus reglas tienen prioridad; si no, usá el Master."* — exactamente el flujo Master + Overrides del skill.

### Referencia rápida de las reglas del skill (prioridades 1→10)

| Prioridad | Categoría | Checks obligatorios | Anti-patterns |
|---|---|---|---|
| 1 | Accesibilidad | Contraste 4.5:1, alt, keyboard, aria | Quitar focus rings, íconos sin label |
| 2 | Touch & interacción | ≥44×44 px, feedback de carga | Hover-only, cambios instantáneos |
| 3 | Performance | WebP/AVIF, lazy, CLS < 0.1 | Layout thrashing, CLS |
| 4 | Selección de estilo | Matchear producto, SVG icons | Mezclar flat/skeuomorphic, emoji-icons |
| 5 | Layout responsive | Mobile-first, sin scroll horizontal | Widths fijos en px, deshabilitar zoom |
| 6 | Tipografía y color | Base 16px, tokens semánticos | Texto < 12px, gray-on-gray, hex sueltos |
| 7 | Animación | 150–300 ms, meaning, reduced-motion | Decorativa, animar width/height |
| 8 | Formularios | Labels visibles, error cerca del campo | Placeholder-only, errores solo arriba |
| 9 | Navegación | Back predecible, deep links | Nav sobrecargada, back roto |
| 10 | Charts | Legends, tooltips, colores accesibles | Solo color para significar |

---

*Fuentes: código fuente de `services/` (controllers y routers verificados), `docs/adr/`, y skill open-source [nicohodt/claude-code-ui-ux-skill](https://github.com/nicohodt/claude-code-ui-ux-skill) (README + SKILL.md).*