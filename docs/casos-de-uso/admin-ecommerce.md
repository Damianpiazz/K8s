# Administración de la plataforma e-commerce: acciones y flujos

> **Alcance:** inventario completo, verificado contra el código fuente, de las acciones que un **administrador** puede realizar sobre la plataforma de microservicios (17 servicios Spring Boot + Keycloak, AKS/Kustomize). Complementa `sistema-ecommerce.md`: mientras ese documento cubre al cliente final, este cubre la operatoria: catálogo, inventario, devoluciones, envíos, notificaciones, analytics, consulta de órdenes y pagos, identidad e infraestructura.

---

## 1. Rol y superficie de administración

| Aspecto | Detalle |
|---|---|
| **Rol definido** | `admin` en el realm `ecommerce` de Keycloak (además de `user`). |
| **Clientes de identidad** | `ecommerce-web` (public/PKCE, login web) y `ecommerce-api` (confidential/M2M, integraciones). |
| **Acceso actual** | ⚠️ Los tokens de Keycloak **aún no se validan** en gateway ni servicios (sin filtros JWT). El rol `admin` es declarativo: la autorización por rol en API está pendiente. |
| **Superficie de uso** | Rutas del gateway `/api/<dominio>/**` + Keycloak admin + paneles de servicios (actuators). |
| **Datos de la tienda** | Sólidos (H2): catalog, order, inventory. En memoria (se pierden al reiniciar): checkout, payment, notification, shipping, returns, analytics, search, recommendation. |

**Secciones de admin cubiertas por el sistema:**
1. Gestión de catálogo (CRUD de productos) — `catalog-svc`
2. Gestión de inventario y reservas — `inventory-svc`
3. Revisión y resolución de devoluciones — `returns-svc`
4. Operación de envíos (embarque y entrega) — `shipping-svc`
5. Notificaciones y campañas — `notification-svc`
6. Analytics y reportes — `analytics-svc`
7. Consulta de órdenes y pagos — `order-svc`, `payment-svc`, `checkout-svc`
8. Identidad y usuarios — Keycloak
9. Operaciones / infraestructura — `config-service`, `discovery-service`, actuators

---

## 2. Acciones por dominio

### 2.1 Catálogo (catalog-svc · 8081) — CRUD de productos

| Acción | Endpoint | Reglas |
|---|---|---|
| Listar productos | `GET /api/catalog/products?category=` | Filtro por categoría, case-insensitive (404 si la categoría no existe). |
| Ver detalle | `GET /api/catalog/products/{id}` | |
| **Crear producto** | `POST /api/catalog/products` | 201. El id lo genera el servidor (**no confía en el id del cliente**). |
| **Actualizar producto** | `PUT /api/catalog/products/{id}` | |
| **Eliminar producto** | `DELETE /api/catalog/products/{id}` | 204 / 404. |

### 2.2 Inventario (inventory-svc · 8093) — stock y reservas

| Acción | Endpoint | Reglas |
|---|---|---|
| Ver todo el stock | `GET /api/inventory` | |
| Ver stock de un producto | `GET /api/inventory/{productId}` | |
| **Fijar stock disponible** | `PUT /api/inventory/{productId}` | Actualización manual (reposición/ajuste). |
| Ver reservas activas | (consulta de reservas según implementación) | Ciclo: `POST /reserve` → `POST /release` o `POST /confirm/{reservationId}`. |
| **Reservar stock** | `POST /api/inventory/reserve` | 201; **409 si stock insuficiente**. Sincronizado (`synchronized`): sin overbooking. |
| **Liberar reserva** | `POST /api/inventory/release` | |
| **Confirmar reserva** | `POST /api/inventory/confirm/{reservationId}` | Cierra el ciclo reserva → venta. |

> ⚠️ El TTL de reserva (15 min) existe solo en configuración: **no hay sweeper automático** que libere reservas vencidas.

### 2.3 Devoluciones (returns-svc · 8095) — revisión y resolución

| Acción | Endpoint | Reglas |
|---|---|---|
| Ver todas las devoluciones | `GET /api/returns` (listado según implementación) | |
| Ver devolución por id | `GET /api/returns/{id}` | |
| Ver devoluciones de una orden | `GET /api/returns/order/{orderId}` | |
| **Aprobar devolución** | `POST /api/returns/{id}/approve` | Idempotente sobre el mismo estado; **409 si intentás rechazar una ya aprobada** (y viceversa). |
| **Rechazar devolución** | `POST /api/returns/{id}/reject` | Ídem. |

**Flujo de revisión manual:** los casos que no cumplen la auto-aprobación quedan `PENDING_REVIEW`:
- motivo fuera de {`defective`, `wrong-item`, `not-as-described`}, **o**
- fuera de la ventana de 30 días, **o**
- cantidad > 10.
Esos son los que el admin debe resolver con `approve` / `reject`.

### 2.4 Envíos (shipping-svc · 8094) — embarque y entrega

| Acción | Endpoint | Reglas |
|---|---|---|
| Ver envío por id | `GET /api/shipping/{id}` | |
| Envíos de una orden | `GET /api/shipping/order/{orderId}` | |
| **Embarcar (PENDING → SHIPPED)** | `PATCH /api/shipping/{id}/status` con `{status: SHIPPED}` | Asigna tracking `EC########`. |
| **Entregar (SHIPPED → DELIVERED)** | `PATCH /api/shipping/{id}/status` con `{status: DELIVERED}` | |
| Transiciones inválidas | (cualquier otra) | **409 Conflict.** |

La máquina de estados es estricta: `PENDING → SHIPPED → DELIVERED`. El admin solo puede avanzar en ese orden.

### 2.5 Notificaciones (notification-svc · 8086) — comunicación y campañas

| Acción | Endpoint | Reglas |
|---|---|---|
| **Crear notificación manual** | `POST /api/notifications` | 201. Valida tipo. Útil para avisos (p. ej. promocionales, sistema). |
| Ver notificación | `GET /api/notifications/{id}` | |
| Listar notificaciones | `GET /api/notifications` | |
| Automáticas (Kafka) | (deshabilitado por defecto) | Con `notifications.kafka.enabled=true`: consume `order-events`/`payment-events` → `ORDER_CONFIRMED` / `PAYMENT_RECEIVED`. |

**Tipos válidos:** `ORDER_CONFIRMED`, `PAYMENT_RECEIVED`, `PAYMENT_DECLINED`, `SHIPPED`, `DELIVERED`, `PROMOTIONAL`, `SYSTEM`.

### 2.6 Analytics (analytics-svc · 8096) — reportes

| Acción | Endpoint | Reglas |
|---|---|---|
| Registrar evento | `POST /api/analytics/events` | 201 (también lo hace el cliente, pero el admin puede inyectar eventos de prueba). |
| Consultar eventos | `GET /api/analytics/events?type=` | Filtro opcional por tipo. |
| **Top productos** | `GET /api/analytics/reports/top-products?limit=` | Default 5 — qué está vendiendo/viendo más. |
| **Resumen** | `GET /api/analytics/reports/summary` | Vista rápida del estado. |

### 2.7 Órdenes y pagos (order-svc · 8084 / payment-svc · 8085 / checkout-svc · 8083) — consulta y control

| Acción | Endpoint | Reglas |
|---|---|---|
| Crear orden (asistido) | `POST /api/orders` | 201; items no vacíos, `qty > 0`, **máx. 50 ítems**; estado forzado `CREATED`. |
| Listar órdenes | `GET /api/orders` | |
| Ver orden por id | `GET /api/orders/{id}` | |
| Órdenes de un cliente | `GET /api/orders/customer/{customerId}` | |
| Ver pago por id | `GET /api/payments/{id}` | |
| Pagos de una orden | `GET /api/payments/order/{orderId}` | Verificar si una orden efectivamente se pagó. |
| Ver checkouts | `GET /api/checkout` y `GET /api/checkout/{id}` | Registro del flujo de compra. |

> ⚠️ **Limitación:** no existen endpoints para transicionar el estado de una orden (`CREATED → PAID → SHIPPED → DELIVERED/CANCELLED`). Un admin **no puede hoy** marcar una orden como pagada o cancelada desde la API.

### 2.8 Identidad (Keycloak 24 · realm `ecommerce`) — usuarios y accesos

| Acción | Endpoint / superficie | Reglas |
|---|---|---|
| Login web (PKCE) | flujo OpenID Connect | Cliente público `ecommerce-web`. |
| Login API/administración (M2M) | client credentials | Cliente confidencial `ecommerce-api`. |
| Admin del realm | `/admin` (consola Keycloak) | Gestión de usuarios, roles (`admin`/`user`), clients, secretos de client. |
| Descubrimiento OIDC | `GET /realms/ecommerce/.well-known/openid-configuration` | |

### 2.9 Infraestructura (config-service · 8888 / discovery-service · 8761 / actuators)

| Acción | Superficie | Detalle |
|---|---|---|
| Ver/recargar config de un servicio | `GET /{app}/{profile}` | P. ej. `/api-gateway/default`. |
| Salud de un servicio | `GET /actuator/health` (por servicio) | Estado individual. |
| Métricas | `GET /actuator/{info,prometheus,metrics,env}` | Base para dashboards/alertas. |
| Registry de servicios | `GET /eureka/` (dashboard) | Qué servicios están registrados y vivos. |

---

## 3. Flujos típicos de administración

**A. Reposición de stock**
1. `GET /api/inventory` → identificar producto con stock bajo.
2. `PUT /api/inventory/{productId}` → fijar nuevo on-hand.

**B. Resolver devoluciones pendientes**
1. `GET /api/returns/order/{orderId}` (o listado) → encontrar `PENDING_REVIEW`.
2. Decidir → `POST /api/returns/{id}/approve` o `POST /api/returns/{id}/reject`.
3. Ojo: si el estado ya fue resuelto, la operación inversa da **409**.

**C. Embarcar y entregar pedidos**
1. `GET /api/shipping/order/{orderId}` → confirmar estado `PENDING`.
2. `PATCH /api/shipping/{id}/status` con `{status: SHIPPED}` → tracking `EC########`.
3. (Al llegar) `PATCH /api/shipping/{id}/status` con `{status: DELIVERED}`.
4. Cualquier salto de orden → **409** (obligatorio pasar por SHIPPED).

**D. Publicar una notificación / campaña**
1. `POST /api/notifications` con tipo `PROMOTIONAL` (o `SYSTEM`, `ORDER_CONFIRMED`, etc.) y el contenido.
2. Verificar entrega: `GET /api/notifications`.

**E. Supervisión de ventas**
1. `GET /api/analytics/reports/top-products?limit=10` → qué productos dominan.
2. `GET /api/analytics/reports/summary` → resumen general.
3. `GET /api/payments/order/{orderId}` → confirmar cobro real de una orden.
4. `GET /api/orders/customer/{customerId}` → historial de un cliente.

---

## 4. Seguridad y estado actual

| Tema | Estado |
|---|---|
| Rol `admin` definido en Keycloak | ✅ Realm `ecommerce`, roles `admin`/`user`. |
| Validación de tokens en gateway/servicios | ❌ No implementada (sin filtros JWT). El rol es declarativo. |
| Protección de manifiestos en cluster | ✅ PSA restricted + NetworkPolicies (ver `docs/adr/0009`). |
| Secretos de producción | ✅ External Secrets → Azure Key Vault (ver `docs/adr/0007`). |
| Autorización por endpoint (solo admin vs solo user) | ❌ Pendiente — hoy cualquiera puede llamar las rutas administrativas. |

**Implicancia práctica:** la diferenciación `admin`/`user` hoy sirve para la UI y el futuro control de acceso, pero la API no la hace cumplir. Antes de exponer esta plataforma a producción, hay que integrar la validación JWT (ruta `/api/auth` + filtros en gateway/servicios).

---

## 5. Limitaciones relevantes para administración

1. **Sin transiciones de estado de orden:** no se puede marcar una orden como pagada/enviada/cancelada desde la API (`order-svc` no expone endpoints de transición; `payment-svc` no actualiza el estado de la orden).
2. **Sin idempotencia de pagos:** un `POST /api/payments` repetido crea **pagos duplicados** — el admin debe cuidar no reintentar sin verificar (`GET /api/payments/order/{orderId}`).
3. **Reservas sin expiración real:** el TTL de 15 min está configurado pero no hay sweeper; las reservas puedan quedar colgadas hasta `release` manual.
4. **Datos volátiles:** checkout/payment/notification/shipping/returns/analytics son en memoria → se pierden al reiniciar el pod; el historial administrativo no es duradero.
5. **Búsqueda y recomendaciones con catálogo estático (ids 1–8):** los reportes de búsqueda (`/api/search/hot`) reflejan términos contra datos semilla, no el catálogo vivo.
6. **Ruta `/api/customers/**` es placeholder** (servicio no desplegado): no hay gestión de clientes como dominio propio aún.

---

*Fuentes: código fuente de `services/` (controllers verificados), `docs/adr/` (0005 Keycloak, 0007 External Secrets, 0009 PSA), y `docs/casos-de-uso/sistema-ecommerce.md`.*