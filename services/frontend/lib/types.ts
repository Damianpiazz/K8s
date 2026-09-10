// Domain types shared by the storefront and the ops dashboard.
//
// Field names mirror the JSON payloads served by the Spring Boot services
// (verified against the controllers/models under services/<svc>). Fields marked
// "frontend-only" do not exist in the backend and are used for rendering:
//   - Product.color       visual key for the CSS-drawn product placeholder
//   - Order.ref           display order number (backend id is a Long)
//   - Order.payment       cached payment outcome for the demo orders list

/** Visual key for the CSS-drawn product placeholder (frontend-only). */
export type VisualColor = 'keyboard' | 'headphones' | 'monitor' | 'hub' | 'lamp' | 'speaker'

// ── catalog-svc ────────────────────────────────────────────────────────────────
export interface Product {
  id: number
  name: string
  description: string
  price: number
  stock: number
  category: string
  /** Frontend-only: visual placeholder key; absent for backend products. */
  color?: VisualColor
}

// ── cart-svc ───────────────────────────────────────────────────────────────────
export interface CartItem {
  productId: number
  quantity: number
  unitPrice: number
}

export interface Cart {
  id: string
  items: CartItem[]
}

export interface CartTotal {
  cartId: string
  total: number
  itemCount: number
}

// ── order-svc ──────────────────────────────────────────────────────────────────
export interface OrderItem {
  productId: number
  quantity: number
  price: number
}

export type OrderStatus = 'CREATED' | 'PAID' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED'

export interface Order {
  id: number
  customerId: string
  items: OrderItem[]
  total: number
  status: OrderStatus
  createdAt?: string
  /** Frontend-only: display order number (e.g. "NV-260918"). */
  ref?: string
  /** Frontend-only: cached payment outcome for the demo table ('PAID' = order paid). */
  payment?: 'PENDING' | 'PAID' | 'APPROVED' | 'DECLINED'
}

// ── payment-svc ────────────────────────────────────────────────────────────────
export type PaymentStatus = 'APPROVED' | 'DECLINED'

export interface Payment {
  id: number
  orderId: number
  amount: number
  method: string
  status: PaymentStatus
  createdAt?: string
}

export interface PaymentRequest {
  orderId: number
  amount: number
  method: string
  cardLast4: string
}

// ── notification-svc ───────────────────────────────────────────────────────────
export type NotificationType =
  | 'ORDER_CONFIRMED'
  | 'PAYMENT_RECEIVED'
  | 'PAYMENT_DECLINED'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'PROMOTIONAL'
  | 'SYSTEM'

export interface Notification {
  id: number
  type: NotificationType
  recipient: string
  subject: string
  body: string
  createdAt?: string
}

export interface NotificationRequest {
  type: NotificationType
  recipient: string
  subject: string
  body: string
}

// ── inventory-svc ──────────────────────────────────────────────────────────────
export interface InventoryItem {
  id: number
  productId: number
  sku: string
  name: string
  stockLevel: number
  reserved: number
}

export type ReservationStatus = 'RESERVED' | 'RELEASED' | 'CONFIRMED'

export interface Reservation {
  id: number
  productId: number
  quantity: number
  createdAt?: string
  expiresAt?: string
  status: ReservationStatus
}

// ── shipping-svc ───────────────────────────────────────────────────────────────
export type ShippingStatus = 'PENDING' | 'SHIPPED' | 'DELIVERED' | 'FAILED'

export interface Address {
  line1: string
  city: string
  postalCode: string
  country: string
}

export interface ShippingOrder {
  id: number
  orderId: string
  address?: Address | null
  status: ShippingStatus
  carrier?: string
  trackingNumber?: string | null
  createdAt?: string
}

export interface ShipmentCreateRequest {
  orderId: string
  address: Address
}

// ── returns-svc ────────────────────────────────────────────────────────────────
export type ReturnStatus = 'RETURN_REQUESTED' | 'APPROVED' | 'REJECTED' | 'PENDING_REVIEW'

export interface ReturnRequest {
  id: number
  orderId: string
  productId: number
  reason: string
  quantity: number
  createdAt?: string
  status: ReturnStatus
}

export interface ReturnCreateRequest {
  orderId: string
  productId: number
  reason: string
  quantity: number
}

// ── analytics-svc ──────────────────────────────────────────────────────────────
export type EventType = 'VIEW' | 'ADD_TO_CART' | 'PURCHASE' | 'SEARCH' | 'CLICK'

export interface AnalyticsEvent {
  id: number
  type: EventType
  productId: number | null
  customerId?: string | null
  value?: number | null
  timestamp?: string
}

export interface TopProduct {
  productId: number
  count: number
}

/** GET /api/analytics/reports/summary → counts per event type. */
export type AnalyticsSummary = Record<string, number>

// ── search-svc ─────────────────────────────────────────────────────────────────
export interface ProductSearchHit {
  id: number
  name: string
  description: string
  category: string
  score: number
}

// ── recommendation-svc ─────────────────────────────────────────────────────────
export interface Recommendation {
  productId: number
  name: string
  category: string
  score: number
  reason: string
}

// ── checkout-svc ───────────────────────────────────────────────────────────────
export interface CheckoutRequest {
  cartId: string
  customerId: string
  paymentMethod: string
}

export interface CheckoutResponse {
  orderId: number
  total: number
  status: string
}

export interface CheckoutRecord {
  id: number
  cartId: string
  customerId: string
  paymentMethod: string
  total: number
  status: string
  createdAt?: string
}

// ── bff-web (storefront facade) ────────────────────────────────────────────────
export interface ProductSummary {
  id: number
  name: string
  description: string
  price: number
  category: string
}

export interface HomeResponse {
  products: ProductSummary[]
  hero: ProductSummary | null
  degraded: boolean
}

export interface CartItemView {
  productId: number
  name: string
  quantity: number
  unitPrice: number
  lineTotal: number
}

export interface CartView {
  cartId: string
  items: CartItemView[]
  total: number
  degraded: boolean
}

// ── derived views (frontend-only) ──────────────────────────────────────────────
/** Product as displayed in the storefront grid/modal/cart drawer. */
export type ShopProduct = ProductSummary & {
  stock?: number
  color?: VisualColor
}

/** Customer row for the ops "Customers" tab (aggregated from orders). */
export interface CustomerRow {
  customerId: string
  name?: string
  orderCount: number
  lifetimeTotal: number
}