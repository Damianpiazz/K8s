// Mock-mode data (the demo dataset the app used before the backend integration).
//
// Values mirror what the real services return from their seed data:
// product names, descriptions and category values are data (English, matching
// the backend seed catalog); every UI string rendered by components lives in
// the components themselves in neutral Spanish.

import type {
  AnalyticsEvent,
  AnalyticsSummary,
  CartView,
  CustomerRow,
  HomeResponse,
  InventoryItem,
  Notification,
  Order,
  Payment,
  Product,
  ProductSearchHit,
  ProductSummary,
  Recommendation,
  ReturnRequest,
  ShippingOrder,
  TopProduct,
} from './types'

/** Sentinel label for the "show everything" category filter (UI chrome). */
export const ALL_CATEGORIES = 'Todos'

/** Category strip shown in mock mode (mirrors the backend seed categories). */
export const categories: string[] = [
  ALL_CATEGORIES,
  'Peripherals',
  'Audio',
  'Displays',
  'Accessories',
  'Desk setup',
]

export const initialProducts: Product[] = [
  { id: 1, name: 'Nimbus Mechanical Keyboard', category: 'Peripherals', price: 149, stock: 18, description: 'Low-profile switches, wireless freedom, and a focused typing feel.', color: 'keyboard' },
  { id: 2, name: 'Aether Studio Headphones', category: 'Audio', price: 229, stock: 7, description: 'Spatial sound and all-day comfort for deep work.', color: 'headphones' },
  { id: 3, name: 'Orbit 4K Monitor', category: 'Displays', price: 499, stock: 12, description: 'A color-accurate 27-inch canvas for every idea.', color: 'monitor' },
  { id: 4, name: 'Vector USB-C Hub', category: 'Accessories', price: 69, stock: 42, description: 'One compact dock for your entire desk.', color: 'hub' },
  { id: 5, name: 'Flux Desk Light', category: 'Desk setup', price: 119, stock: 21, description: 'Adaptive light for crisp work from day to night.', color: 'lamp' },
  { id: 6, name: 'Pulse Smart Speaker', category: 'Audio', price: 89, stock: 9, description: 'Room-filling sound in a quiet footprint.', color: 'speaker' },
]

/** 12-month sales bars for the ops "Sales overview" chart. */
export const chartData: number[] = [62, 74, 58, 86, 78, 92, 88, 100, 82, 95, 90, 98]

function toSummary(product: Product): ProductSummary {
  return { id: product.id, name: product.name, description: product.description, price: product.price, category: product.category }
}

/** GET /api/bff/home mock payload. */
export const mockHome: HomeResponse = {
  products: initialProducts.map(toSummary),
  hero: toSummary(initialProducts[0]),
  degraded: false,
}

/** Display names for the demo customers (frontend-only lookup). */
export const mockCustomerNames: Record<string, string> = {
  'cust-alex': 'Alex Morgan',
  'cust-jordan': 'Jordan Lee',
  'cust-sam': 'Sam Rivera',
}

/** GET /api/orders mock payload. */
export const mockOrders: Order[] = [
  { id: 260918, ref: 'NV-260918', customerId: 'cust-alex', total: 647, payment: 'PAID', status: 'CREATED', items: [{ productId: 1, quantity: 1, price: 149 }, { productId: 2, quantity: 2, price: 249 }] },
  { id: 260901, ref: 'NV-260901', customerId: 'cust-jordan', total: 229, payment: 'PAID', status: 'SHIPPED', items: [{ productId: 2, quantity: 1, price: 229 }] },
  { id: 260897, ref: 'NV-260897', customerId: 'cust-sam', total: 149, payment: 'DECLINED', status: 'CANCELLED', items: [{ productId: 1, quantity: 1, price: 149 }] },
]

/** GET /api/payments mock payload. */
export const mockPayments: Payment[] = [
  { id: 8821, orderId: 260918, amount: 647, method: 'card', status: 'APPROVED' },
  { id: 8819, orderId: 260901, amount: 229, method: 'card', status: 'APPROVED' },
  { id: 8812, orderId: 260897, amount: 149, method: 'card', status: 'DECLINED' },
]

/** GET /api/returns mock payload (backend has no list endpoint; see api.ts). */
export const mockReturns: ReturnRequest[] = [
  { id: 104, orderId: 'NV-260901', productId: 2, reason: 'defective', quantity: 1, status: 'PENDING_REVIEW' },
  { id: 103, orderId: 'NV-260897', productId: 1, reason: 'outside policy window', quantity: 1, status: 'PENDING_REVIEW' },
]

/** GET /api/shipping/order/{orderId} mock payloads (flattened). */
export const mockShipments: ShippingOrder[] = [
  { id: 22, orderId: 'NV-260918', status: 'PENDING' },
  { id: 21, orderId: 'NV-260901', status: 'SHIPPED', trackingNumber: 'EC48290173', carrier: 'Ecommerce Express' },
]

/** GET /api/notifications mock payload. */
export const mockNotifications: Notification[] = [
  { id: 1, type: 'ORDER_CONFIRMED', recipient: 'cust-alex@nova.local', subject: 'Pedido NV-260918 confirmado', body: 'Tu pedido fue confirmado y está en preparación.' },
  { id: 2, type: 'PAYMENT_RECEIVED', recipient: 'cust-alex@nova.local', subject: 'Pago recibido', body: 'Recibimos tu pago de $647.' },
]

/** GET /api/analytics/events mock payload (newest first). */
export const mockAnalyticsEvents: AnalyticsEvent[] = [
  { id: 5, type: 'PURCHASE', productId: 3, customerId: 'cust-alex', value: 647, timestamp: new Date(Date.now() - 2 * 60_000).toISOString() },
  { id: 4, type: 'ADD_TO_CART', productId: 2, customerId: 'cust-jordan', value: null, timestamp: new Date(Date.now() - 4 * 60_000).toISOString() },
  { id: 3, type: 'SEARCH', productId: null, customerId: null, value: null, timestamp: new Date(Date.now() - 8 * 60_000).toISOString() },
  { id: 2, type: 'VIEW', productId: 4, customerId: null, value: null, timestamp: new Date(Date.now() - 11 * 60_000).toISOString() },
]

/** GET /api/analytics/reports/top-products mock payload. */
export const mockTopProducts: TopProduct[] = [
  { productId: 3, count: 42 },
  { productId: 1, count: 31 },
  { productId: 2, count: 18 },
]

/** GET /api/analytics/reports/summary mock payload. */
export const mockAnalyticsSummary: AnalyticsSummary = { VIEW: 58, ADD_TO_CART: 34, PURCHASE: 21, SEARCH: 47, CLICK: 26 }

/** GET /api/search/hot mock payload. */
export const mockHotSearches: string[] = ['monitor', 'keyboard', 'Audio collection']

/** GET /api/search?q=… mock payload. */
export const mockSearchHits: ProductSearchHit[] = [
  { id: 1, name: 'Nimbus Mechanical Keyboard', description: 'Low-profile switches…', category: 'Peripherals', score: 3 },
  { id: 3, name: 'Orbit 4K Monitor', description: 'A color-accurate 27-inch canvas…', category: 'Displays', score: 2.4 },
  { id: 2, name: 'Aether Studio Headphones', description: 'Spatial sound…', category: 'Audio', score: 1.8 },
]

/** GET /api/recommendations mock payload. */
export const mockRecommendations: Recommendation[] = [
  { productId: 3, name: 'Orbit 4K Monitor', category: 'Displays', score: 2.4, reason: 'also-bought' },
  { productId: 5, name: 'Flux Desk Light', category: 'Desk setup', score: 2.1, reason: 'same-category' },
  { productId: 4, name: 'Vector USB-C Hub', category: 'Accessories', score: 1.7, reason: 'also-bought' },
]

/** GET /api/inventory mock payload (derived from the seed catalog). */
export const mockInventory: InventoryItem[] = initialProducts.map((product, index) => ({
  id: index + 1,
  productId: product.id,
  sku: `NOVA-${String(product.id).padStart(3, '0')}`,
  name: product.name,
  stockLevel: product.stock,
  reserved: 0,
}))

/** GET /api/bff/cart/{cartId} mock payload. */
export function mockCartView(cartId: string): CartView {
  return {
    cartId,
    items: [
      { productId: 1, name: 'Nimbus Mechanical Keyboard', quantity: 1, unitPrice: 149, lineTotal: 149 },
      { productId: 2, name: 'Aether Studio Headphones', quantity: 2, unitPrice: 229, lineTotal: 458 },
    ],
    total: 607,
    degraded: false,
  }
}

/** Ops "Customers" tab: aggregated rows (mock). */
export const mockCustomers: CustomerRow[] = [
  { customerId: 'cust-alex', name: 'Alex Morgan', orderCount: 3, lifetimeTotal: 1284 },
  { customerId: 'cust-jordan', name: 'Jordan Lee', orderCount: 2, lifetimeTotal: 458 },
  { customerId: 'cust-sam', name: 'Sam Rivera', orderCount: 1, lifetimeTotal: 149 },
]

/** Static admin modules (informational views without a backing endpoint). */
export interface AdminModuleConfig {
  eyebrow: string
  title: string
  description: string
  rows: string[]
  action: string
}

export const adminModuleConfigs: Record<string, AdminModuleConfig> = {
  search: {
    eyebrow: 'search-svc · recommendation-svc',
    title: 'Búsqueda y recomendaciones',
    description: 'Vista operativa de solo lectura: términos calientes y estado de recomendaciones.',
    rows: ['monitor · 42 búsquedas · en ascenso', 'keyboard · 31 búsquedas · estable', 'Audio collection · 18 búsquedas · estable'],
    action: 'Reindexar catálogo',
  },
  carts: {
    eyebrow: 'cart-svc · GET /api/cart',
    title: 'Carritos',
    description: 'Monitoreo de carritos activos y abandonados hasta que se habilite el purge.',
    rows: ['cart_9f31 · Alex Morgan · 2 ítems · activo', 'cart_8c12 · invitado · 1 ítem · 28m inactivo', 'cart_7a03 · Jordan Lee · 3 ítems · 2h inactivo'],
    action: 'Purgar carritos abandonados',
  },
  users: {
    eyebrow: 'Keycloak · ecommerce realm',
    title: 'Usuarios y roles',
    description: 'La gestión de identidad vive en Keycloak. Esta vista confirma la guía de administración.',
    rows: ['admin@nova.local · admin · activo', 'operator@nova.local · admin · activo', 'realm de clientes · user · gestionado en Keycloak'],
    action: 'Abrir Keycloak admin',
  },
  settings: {
    eyebrow: 'platform configuration',
    title: 'Configuración',
    description: 'Políticas operativas y estado de integración de la superficie de administración.',
    rows: ['Reservation TTL · 15 minutos', 'Máquina de estados de órdenes · aplicada en la UI', 'Validación JWT · pendiente en el gateway', 'Persistencia · H2 y en memoria'],
    action: 'Guardar cambios de política',
  },
}

/** Ops "Identity & infra" tab: service status rows. */
export const mockInfraRows: string[] = [
  'Keycloak realm ecommerce · rol admin',
  'api-gateway · validación JWT pendiente',
  'catalog-svc · UP',
  'inventory-svc · UP',
  'payment-svc · UP',
  'analytics-svc · UP',
  'config-service · UP',
  'discovery-service · 8 registrados',
]