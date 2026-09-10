// API integration layer.
//
// Every call goes through apiGet / apiMutate, which split on the mode:
//   - mock mode: resolves the provided mock payload after a short delay
//   - real mode: fetches the gateway (or the same-origin rewrites() proxy)
//
// Mock branches reproduce the backend business rules that matter to the UI
// (409s, state machines, decline rule) so the demo behaves exactly like the
// real API. Error messages stay English here; components map them to neutral
// Spanish at the UI boundary.

import { apiBase, isMockMode } from './config'
import {
  initialProducts,
  mockAnalyticsEvents,
  mockAnalyticsSummary,
  mockCartView,
  mockHome,
  mockHotSearches,
  mockInventory,
  mockNotifications,
  mockOrders,
  mockPayments,
  mockRecommendations,
  mockReturns,
  mockSearchHits,
  mockShipments,
  mockTopProducts,
} from './mock-data'
import type {
  AnalyticsEvent,
  AnalyticsSummary,
  Cart,
  CartItem,
  CartTotal,
  CartView,
  CheckoutRequest,
  CheckoutResponse,
  HomeResponse,
  InventoryItem,
  Notification,
  NotificationRequest,
  Order,
  OrderItem,
  Payment,
  PaymentRequest,
  PaymentStatus,
  Product,
  ProductSearchHit,
  Recommendation,
  Reservation,
  ReturnRequest,
  ShippingOrder,
  ShipmentCreateRequest,
  TopProduct,
} from './types'

const MOCK_DELAY_MS = 250

// Monotonic counters so mock-mode creations get unique ids across calls.
let mockProductIdCounter = Math.max(0, ...initialProducts.map((p) => p.id))
let mockNotificationIdCounter = Math.max(0, ...mockNotifications.map((n) => n.id))

/** Error thrown for HTTP failures or network problems on real calls. */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

function mockDelay<T>(value: T): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), MOCK_DELAY_MS))
}

async function readJson(res: Response): Promise<unknown> {
  const text = await res.text()
  if (!text) return undefined
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

/** GET helper: mock mode returns mockData; real mode fetches apiBase + path. */
export async function apiGet<T>(path: string, mockData?: T): Promise<T> {
  if (isMockMode()) return mockDelay(mockData as T)
  let res: Response
  try {
    res = await fetch(`${apiBase()}${path}`, { cache: 'no-store' })
  } catch {
    throw new ApiError(0, `Network error calling GET ${path}`)
  }
  if (!res.ok) {
    throw new ApiError(res.status, `GET ${path} → HTTP ${res.status}`)
  }
  return (await readJson(res)) as T
}

/** Mutation helper: POST / PUT / PATCH / DELETE with the same mock/real split. */
export async function apiMutate<T>(
  method: 'post' | 'put' | 'patch' | 'delete',
  path: string,
  body?: unknown,
  mockResponse?: T,
): Promise<T> {
  if (isMockMode()) {
    await mockDelay(mockResponse as T)
    return mockResponse as T
  }
  let res: Response
  try {
    res = await fetch(`${apiBase()}${path}`, {
      method: method.toUpperCase(),
      headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      cache: 'no-store',
    })
  } catch {
    throw new ApiError(0, `Network error calling ${method.toUpperCase()} ${path}`)
  }
  if (!res.ok) {
    throw new ApiError(res.status, `${method.toUpperCase()} ${path} → HTTP ${res.status}`)
  }
  return (await readJson(res)) as T
}

// ── catalog-svc ────────────────────────────────────────────────────────────────
/** GET /api/catalog/products?category=… */
export function getProducts(category?: string): Promise<Product[]> {
  const query = category ? `?category=${encodeURIComponent(category)}` : ''
  const mock = category
    ? initialProducts.filter((p) => p.category.toLowerCase() === category.toLowerCase())
    : initialProducts
  return apiGet(`/api/catalog/products${query}`, mock)
}

/** GET /api/catalog/products/{id} */
export function getProduct(id: number): Promise<Product> {
  return apiGet(`/api/catalog/products/${id}`, initialProducts.find((p) => p.id === id))
}

/** POST /api/catalog/products (server assigns the id). */
export function createProduct(input: Omit<Product, 'id'>): Promise<Product> {
  mockProductIdCounter += 1
  const mock = { ...input, id: mockProductIdCounter } as Product
  return apiMutate('post', '/api/catalog/products', input, mock)
}

/** PUT /api/catalog/products/{id} */
export function updateProduct(id: number, input: Omit<Product, 'id'>): Promise<Product> {
  const mock = { ...input, id } as Product
  return apiMutate('put', `/api/catalog/products/${id}`, input, mock)
}

/** DELETE /api/catalog/products/{id} → 204 */
export function deleteProduct(id: number): Promise<void> {
  return apiMutate('delete', `/api/catalog/products/${id}`, undefined, undefined)
}

// ── cart-svc ───────────────────────────────────────────────────────────────────
/** GET /api/cart/{cartId} */
export function getCart(cartId: string): Promise<Cart> {
  return apiGet(`/api/cart/${cartId}`, { id: cartId, items: [] as CartItem[] })
}

/** POST /api/cart/{cartId}/items {productId, quantity, unitPrice} */
export function addToCart(cartId: string, item: CartItem): Promise<Cart> {
  const mock = { id: cartId, items: [item] }
  return apiMutate('post', `/api/cart/${cartId}/items`, item, mock)
}

/** DELETE /api/cart/{cartId}/items/{productId} → 204 */
export function removeFromCart(cartId: string, productId: number): Promise<void> {
  return apiMutate('delete', `/api/cart/${cartId}/items/${productId}`, undefined, undefined)
}

/** GET /api/cart/{cartId}/total */
export function getCartTotal(cartId: string): Promise<CartTotal> {
  return apiGet(`/api/cart/${cartId}/total`, { cartId, total: 0, itemCount: 0 })
}

// ── order-svc ──────────────────────────────────────────────────────────────────
export interface OrderCreateRequest {
  customerId: string
  items: OrderItem[]
  total: number
}

/** POST /api/orders → 201 (status forced CREATED by the backend). */
export function createOrder(payload: OrderCreateRequest): Promise<Order> {
  const mock: Order = {
    id: 260_000 + Math.floor(Math.random() * 9_000),
    ref: `NV-${260_000 + Math.floor(Math.random() * 9_000)}`,
    customerId: payload.customerId,
    items: payload.items,
    total: payload.total,
    status: 'CREATED',
  }
  return apiMutate('post', '/api/orders', payload, mock)
}

/** GET /api/orders */
export function getOrders(): Promise<Order[]> {
  return apiGet('/api/orders', mockOrders)
}

/** GET /api/orders/{id} */
export function getOrder(id: number): Promise<Order> {
  return apiGet(`/api/orders/${id}`, mockOrders.find((o) => o.id === id))
}

/** GET /api/orders/customer/{customerId} */
export function getOrderByCustomer(customerId: string): Promise<Order[]> {
  return apiGet(`/api/orders/customer/${customerId}`, mockOrders.filter((o) => o.customerId === customerId))
}

// ── payment-svc ────────────────────────────────────────────────────────────────
/**
 * POST /api/payments → 201. Business rule reproduced in mock mode:
 * cardLast4 ending in "0000" (or amount <= 0) → DECLINED, otherwise APPROVED.
 */
export function processPayment(request: PaymentRequest): Promise<Payment> {
  const declined = request.cardLast4?.endsWith('0000') || request.amount <= 0
  const mock: Payment = {
    id: 9_000 + Math.floor(Math.random() * 999),
    orderId: request.orderId,
    amount: request.amount,
    method: request.method,
    status: declined ? 'DECLINED' : 'APPROVED',
  }
  return apiMutate('post', '/api/payments', request, mock)
}

/** GET /api/payments */
export function getPayments(): Promise<Payment[]> {
  return apiGet('/api/payments', mockPayments)
}

/** GET /api/payments/{id} */
export function getPayment(id: number): Promise<Payment> {
  return apiGet(`/api/payments/${id}`, mockPayments.find((p) => p.id === id))
}

/** GET /api/payments/order/{orderId} */
export function getPaymentByOrder(orderId: number): Promise<Payment[]> {
  return apiGet(`/api/payments/order/${orderId}`, mockPayments.filter((p) => p.orderId === orderId))
}

// ── notification-svc ───────────────────────────────────────────────────────────
/** GET /api/notifications */
export function getNotifications(): Promise<Notification[]> {
  return apiGet('/api/notifications', mockNotifications)
}

/** POST /api/notifications → 201 (backend validates the type). */
export function createNotification(request: NotificationRequest): Promise<Notification> {
  mockNotificationIdCounter += 1
  const mock: Notification = {
    id: mockNotificationIdCounter,
    type: request.type,
    recipient: request.recipient,
    subject: request.subject,
    body: request.body,
  }
  return apiMutate('post', '/api/notifications', request, mock)
}

// ── inventory-svc ──────────────────────────────────────────────────────────────
/** GET /api/inventory */
export function getInventory(): Promise<InventoryItem[]> {
  return apiGet('/api/inventory', mockInventory)
}

/** GET /api/inventory/{productId} */
export function getInventoryItem(productId: number): Promise<InventoryItem> {
  return apiGet(`/api/inventory/${productId}`, mockInventory.find((i) => i.productId === productId))
}

/** PUT /api/inventory/{productId} {quantity} → sets the on-hand stock level. */
export function updateStock(productId: number, stockLevel: number): Promise<InventoryItem> {
  const mock = { ...(mockInventory.find((i) => i.productId === productId) ?? mockInventory[0]), stockLevel }
  return apiMutate('put', `/api/inventory/${productId}`, { quantity: stockLevel }, mock)
}

/**
 * POST /api/inventory/reserve → 201, or 409 when the available stock is
 * insufficient. `available` is the frontend's current stock snapshot, used by
 * the mock branch to reproduce the backend's 409.
 */
export function reserveStock(productId: number, quantity: number, available?: number): Promise<Reservation> {
  const insufficient = available !== undefined && quantity > available
  if (insufficient) {
    return mockDelayThenThrow(409, `Insufficient stock for product ${productId}`)
  }
  const mock: Reservation = {
    id: Math.floor(Math.random() * 100_000),
    productId,
    quantity,
    status: 'RESERVED',
    createdAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
  }
  return apiMutate('post', '/api/inventory/reserve', { productId, quantity }, mock)
}

/** POST /api/inventory/release {reservationId} */
export function releaseReservation(reservationId: number): Promise<Reservation> {
  return apiMutate('post', '/api/inventory/release', { reservationId }, { id: reservationId, productId: 0, quantity: 0, status: 'RELEASED' })
}

/** POST /api/inventory/confirm/{reservationId} */
export function confirmReservation(reservationId: number): Promise<Reservation> {
  return apiMutate('post', `/api/inventory/confirm/${reservationId}`, undefined, { id: reservationId, productId: 0, quantity: 0, status: 'CONFIRMED' })
}

// ── shipping-svc ───────────────────────────────────────────────────────────────
/** GET /api/shipping/{id} */
export function getShipment(id: number): Promise<ShippingOrder> {
  return apiGet(`/api/shipping/${id}`, mockShipments.find((s) => s.id === id))
}

/** GET /api/shipping/order/{orderId} */
export function getShipmentsForOrder(orderId: string): Promise<ShippingOrder[]> {
  return apiGet(`/api/shipping/order/${orderId}`, mockShipments.filter((s) => s.orderId === orderId))
}

/**
 * All shipments across orders. The backend only exposes by-order lookups, so
 * the real branch fans out over the order list (same semantics as returns).
 */
export async function getShipments(current?: ShippingOrder[]): Promise<ShippingOrder[]> {
  if (isMockMode()) return mockDelay(current ?? mockShipments)
  const orders = await getOrders()
  const perOrder = await Promise.all(orders.map((order) => getShipmentsForOrder(String(order.id))))
  return perOrder.flat().sort((a, b) => b.id - a.id)
}

/** POST /api/shipping {orderId, address} → 201 (PENDING). */
export function createShipment(request: ShipmentCreateRequest): Promise<ShippingOrder> {
  const mock: ShippingOrder = {
    id: 30 + Math.floor(Math.random() * 99),
    orderId: request.orderId,
    address: request.address,
    status: 'PENDING',
    carrier: 'Ecommerce Express',
  }
  return apiMutate('post', '/api/shipping', request, mock)
}

/**
 * PATCH /api/shipping/{id}/status {status}. Strict state machine reproduced in
 * mock mode: PENDING → SHIPPED (tracking EC######## assigned) → DELIVERED;
 * any other transition throws 409, mirroring the backend.
 */
export async function updateShipmentStatus(
  id: number,
  status: 'SHIPPED' | 'DELIVERED',
  current?: ShippingOrder[],
): Promise<ShippingOrder> {
  if (isMockMode()) {
    const row = (current ?? mockShipments).find((s) => s.id === id)
    if (!row || (row.status === 'PENDING' && status !== 'SHIPPED') || (row.status === 'SHIPPED' && status !== 'DELIVERED') || row.status === 'DELIVERED') {
      return mockDelayThenThrow(409, `Invalid transition ${row?.status ?? 'unknown'} → ${status}`)
    }
    const updated: ShippingOrder = status === 'SHIPPED'
      ? { ...row, status, trackingNumber: `EC${Math.floor(10_000_000 + Math.random() * 89_999_999)}` }
      : { ...row, status }
    return mockDelay(updated)
  }
  return apiMutate<ShippingOrder>('patch', `/api/shipping/${id}/status`, { status }, undefined)
}

// ── returns-svc ────────────────────────────────────────────────────────────────
/**
 * All return requests. The backend exposes only by-order lookups, so the real
 * branch fans out over the order list instead of faking a list endpoint.
 */
export async function getReturns(current?: ReturnRequest[]): Promise<ReturnRequest[]> {
  if (isMockMode()) return mockDelay(current ?? mockReturns)
  const orders = await getOrders()
  const perOrder = await Promise.all(orders.map((order) => apiGet<ReturnRequest[]>(`/api/returns/order/${order.ref ?? String(order.id)}`, [])))
  return perOrder.flat().sort((a, b) => b.id - a.id)
}

/** POST /api/returns/{id}/approve — idempotent; approving a rejected case → 409. */
export async function approveReturn(id: number, current?: ReturnRequest[]): Promise<ReturnRequest> {
  if (isMockMode()) {
    const row = (current ?? mockReturns).find((r) => r.id === id)
    if (!row) return mockDelayThenThrow(404, `Return ${id} not found`)
    if (row.status === 'REJECTED') return mockDelayThenThrow(409, `Return ${id} already rejected`)
    if (row.status === 'APPROVED') return mockDelay(row)
    return mockDelay({ ...row, status: 'APPROVED' })
  }
  return apiMutate<ReturnRequest>('post', `/api/returns/${id}/approve`, undefined, undefined)
}

/** POST /api/returns/{id}/reject — idempotent; rejecting an approved case → 409. */
export async function rejectReturn(id: number, current?: ReturnRequest[]): Promise<ReturnRequest> {
  if (isMockMode()) {
    const row = (current ?? mockReturns).find((r) => r.id === id)
    if (!row) return mockDelayThenThrow(404, `Return ${id} not found`)
    if (row.status === 'APPROVED') return mockDelayThenThrow(409, `Return ${id} already approved`)
    if (row.status === 'REJECTED') return mockDelay(row)
    return mockDelay({ ...row, status: 'REJECTED' })
  }
  return apiMutate<ReturnRequest>('post', `/api/returns/${id}/reject`, undefined, undefined)
}

// ── analytics-svc ──────────────────────────────────────────────────────────────
/** GET /api/analytics/events?type=… */
export function getAnalyticsEvents(type?: string): Promise<AnalyticsEvent[]> {
  const query = type ? `?type=${encodeURIComponent(type)}` : ''
  const mock = type ? mockAnalyticsEvents.filter((e) => e.type === type) : mockAnalyticsEvents
  return apiGet(`/api/analytics/events${query}`, mock)
}

/** POST /api/analytics/events → 201 */
export function recordAnalyticsEvent(payload: { type: string; productId?: number | null; customerId?: string | null; value?: number | null }): Promise<AnalyticsEvent> {
  const mock: AnalyticsEvent = {
    id: Math.floor(Math.random() * 100_000),
    type: payload.type as AnalyticsEvent['type'],
    productId: payload.productId ?? null,
    customerId: payload.customerId ?? null,
    value: payload.value ?? null,
    timestamp: new Date().toISOString(),
  }
  return apiMutate('post', '/api/analytics/events', payload, mock)
}

/** GET /api/analytics/reports/top-products?limit=… */
export function getTopProducts(limit?: number): Promise<TopProduct[]> {
  const query = limit ? `?limit=${limit}` : ''
  return apiGet(`/api/analytics/reports/top-products${query}`, mockTopProducts.slice(0, limit ?? 5))
}

/** GET /api/analytics/reports/summary */
export function getAnalyticsSummary(): Promise<AnalyticsSummary> {
  return apiGet('/api/analytics/reports/summary', mockAnalyticsSummary)
}

// ── search-svc ─────────────────────────────────────────────────────────────────
/** GET /api/search?q=…&category=… */
export function searchProducts(q: string, category?: string): Promise<ProductSearchHit[]> {
  const params = new URLSearchParams({ q })
  if (category) params.set('category', category)
  const needle = q.toLowerCase()
  const mock = mockSearchHits.filter((h) => `${h.name} ${h.category} ${h.description}`.toLowerCase().includes(needle))
  return apiGet(`/api/search?${params}`, mock)
}

/** GET /api/search/hot */
export function getHotSearches(): Promise<string[]> {
  return apiGet('/api/search/hot', mockHotSearches)
}

// ── recommendation-svc ─────────────────────────────────────────────────────────
/** GET /api/recommendations?customerId=…&limit=… */
export function getRecommendations(customerId: string, limit?: number): Promise<Recommendation[]> {
  const params = new URLSearchParams({ customerId })
  if (limit) params.set('limit', String(limit))
  return apiGet(`/api/recommendations?${params}`, mockRecommendations.slice(0, limit ?? 5))
}

/** GET /api/recommendations/{customerId}/similar?productId=… */
export function getSimilar(customerId: string, productId: number, limit?: number): Promise<Recommendation[]> {
  const params = new URLSearchParams({ productId: String(productId) })
  if (limit) params.set('limit', String(limit))
  const mock = mockRecommendations.filter((r) => r.productId !== productId).slice(0, limit ?? 5)
  return apiGet(`/api/recommendations/${customerId}/similar?${params}`, mock)
}

// ── checkout-svc ───────────────────────────────────────────────────────────────
/** POST /api/checkout → 201 */
export function createCheckout(request: CheckoutRequest): Promise<CheckoutResponse> {
  const mock: CheckoutResponse = { orderId: Math.floor(Math.random() * 100_000), total: 0, status: 'COMPLETED' }
  return apiMutate('post', '/api/checkout', request, mock)
}

// ── bff-web (storefront facade) ────────────────────────────────────────────────
/** GET /api/bff/home → products + hero + degraded flag. */
export function getHome(): Promise<HomeResponse> {
  return apiGet('/api/bff/home', mockHome)
}

/** GET /api/bff/cart/{cartId} → cart joined with product details. */
export function getCartView(cartId: string): Promise<CartView> {
  return apiGet(`/api/bff/cart/${cartId}`, mockCartView(cartId))
}

// ── small helpers ──────────────────────────────────────────────────────────────
function mockDelayThenThrow<T>(status: number, message: string): Promise<T> {
  return new Promise((_resolve, reject) => {
    setTimeout(() => reject(new ApiError(status, message)), MOCK_DELAY_MS)
  })
}

/** Maps a thrown error to a neutral-Spanish user message (UI boundary). */
export function uiErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 0) return 'No se pudo conectar con el backend. ¿Están iniciados los servicios?'
    if (error.status === 409) return 'Conflicto (409): la operación no es válida para el estado actual.'
    if (error.status === 404) return 'Recurso no encontrado (404).'
    if (error.status === 400) return 'Solicitud inválida (400).'
    return `Error del servidor (${error.status}).`
  }
  return 'Ocurrió un error inesperado.'
}