'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { ArrowRight, BarChart3, Check, ChevronRight, CircleAlert, Package, Plus, Search, ShieldCheck, ShoppingBag, Sparkles, Truck, X, Zap } from 'lucide-react'

import { getAnalyticsEvents, getHome, getInventory, getNotifications, getOrders, getPayments, getPaymentByOrder, getProduct, getProducts, getReturns, getShipments, updateShipmentStatus, createNotification, createOrder, processPayment, approveReturn, rejectReturn, reserveStock, updateStock, deleteProduct, createProduct, uiErrorMessage, ApiError } from '@/lib/api'
import { isMockMode } from '@/lib/config'
import { ALL_CATEGORIES, adminModuleConfigs, categories as mockCategories, chartData, initialProducts, mockCustomerNames, mockInfraRows } from '@/lib/mock-data'
import type { AnalyticsEvent, CustomerRow, InventoryItem, Notification, Order, Payment, Product, ProductSummary, ReturnRequest, ShippingOrder, ShopProduct, VisualColor } from '@/lib/types'

// ── visual helpers ─────────────────────────────────────────────────────────────
const VISUAL_BY_CATEGORY: Record<string, VisualColor> = {
  Peripherals: 'keyboard',
  Audio: 'headphones',
  Displays: 'monitor',
  Accessories: 'hub',
  'Desk setup': 'lamp',
}

function visualKeyFor(product: { category: string; color?: VisualColor }): VisualColor {
  return product.color ?? VISUAL_BY_CATEGORY[product.category] ?? 'speaker'
}

function orderRef(order: Order | undefined, fallbackId: number): string {
  return order?.ref ?? `NV-${fallbackId}`
}

function customerName(customerId: string): string {
  return mockCustomerNames[customerId] ?? customerId
}

const PLACEHOLDER_PRODUCT: ShopProduct = { id: 0, name: '', description: '', price: 0, category: '', stock: 0, color: 'keyboard' }

function ProductVisual({ product, large = false }: { product: ShopProduct; large?: boolean }) {
  const visualColor = visualKeyFor(product)
  return <div className={`product-visual visual-${visualColor} ${large ? 'product-visual-large' : ''}`} aria-label={`${product.name} · imagen del producto`} role="img"><div className="visual-glow" />{visualColor === 'keyboard' && <div className="keyboard-shape"><div className="key-row" /><div className="key-row short" /><div className="space-key" /></div>}{visualColor === 'headphones' && <div className="headphone-shape"><div className="headband" /><div className="ear ear-left" /><div className="ear ear-right" /></div>}{visualColor === 'monitor' && <div className="monitor-shape"><div className="monitor-screen" /><div className="monitor-stand" /></div>}{visualColor === 'hub' && <div className="hub-shape"><span /><span /><span /><span /></div>}{visualColor === 'lamp' && <div className="lamp-shape"><div className="lamp-head" /><div className="lamp-arm" /><div className="lamp-base" /></div>}{visualColor === 'speaker' && <div className="speaker-shape"><div className="speaker-face" /><div className="speaker-dot" /></div>}<span className="visual-code">N°{String(product.id).padStart(2, '0')}</span></div>
}

// ── shell ──────────────────────────────────────────────────────────────────────
export default function Page() {
  const [mode, setMode] = useState<'shop' | 'ops'>('shop')
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState(ALL_CATEGORIES)
  const [cart, setCart] = useState<ShopProduct[]>([])
  const [cartOpen, setCartOpen] = useState(false)
  const [notice, setNotice] = useState('')
  const [selectedProduct, setSelectedProduct] = useState<ShopProduct | null>(null)

  // Storefront data (GET /api/bff/home in real mode; mock payload in demo mode).
  const [shopProducts, setShopProducts] = useState<ProductSummary[]>([])
  const [hero, setHero] = useState<ProductSummary | null>(null)
  const [homeDegraded, setHomeDegraded] = useState(false)
  const [shopLoading, setShopLoading] = useState(true)
  const [shopError, setShopError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    getHome()
      .then((home) => {
        if (!active) return
        setShopProducts(home.products)
        setHero(home.hero)
        setHomeDegraded(home.degraded)
      })
      .catch((error) => {
        if (!active) return
        setShopError(uiErrorMessage(error))
      })
      .finally(() => {
        if (active) setShopLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  const flash = useCallback((message: string) => {
    setNotice(message)
    window.setTimeout(() => setNotice(''), 2600)
  }, [])

  const filtered = useMemo(
    () => shopProducts.filter((p) => (category === ALL_CATEGORIES || p.category === category) && `${p.name} ${p.category} ${p.description}`.toLowerCase().includes(query.toLowerCase())),
    [shopProducts, category, query],
  )

  const stripCategories = isMockMode() ? mockCategories : [ALL_CATEGORIES, ...Array.from(new Set(shopProducts.map((p) => p.category)))]

  const add = (product: ShopProduct) => {
    setCart((current) => [...current, product])
    setCartOpen(true)
  }

  const subtotal = cart.reduce((sum, item) => sum + item.price, 0)

  const handleCheckout = async () => {
    if (cart.length === 0) return
    if (cart.length > 50) {
      flash('Máximo 50 artículos por pedido.')
      return
    }
    setCartOpen(false)
    if (isMockMode()) {
      setCart([])
      flash('Pedido confirmado · POST /api/orders · 201 (mock)')
      return
    }
    try {
      const order = await createOrder({
        customerId: 'cust-demo',
        items: cart.map((item) => ({ productId: item.id, quantity: 1, price: item.price })),
        total: subtotal,
      })
      const payment = await processPayment({ orderId: order.id, amount: subtotal, method: 'card', cardLast4: '4242' })
      setCart([])
      flash(payment.status === 'APPROVED' ? `Pedido #${order.id} confirmado y pagado.` : `Pedido #${order.id} registrado, pero el pago fue rechazado.`)
    } catch (error) {
      flash(uiErrorMessage(error))
    }
  }

  return <main className="site-shell"><div className="announcement"><span><Zap size={13} /> Envío exprés gratis en pedidos mayores a $150</span><button onClick={() => setMode(mode === 'shop' ? 'ops' : 'shop')}>{mode === 'shop' ? 'Iniciar sesión de operador' : 'Volver a la tienda'} <ArrowRight size={13} /></button></div><header className="site-header"><a className="brand" href="#top"><span className="brand-mark"><span /></span><span>NOVA<span className="brand-dot">.</span></span></a><span className={isMockMode() ? 'mode-badge mode-badge-demo' : 'mode-badge mode-badge-api'}><span className="mode-badge-dot" />{isMockMode() ? 'Modo demo' : 'Modo API'}</span>{mode === 'shop' && <nav className="desktop-nav" aria-label="Navegación principal"><a href="#shop">Tienda</a><a href="#categories">Categorías</a><a href="#why-nova">Por qué Nova</a></nav>}<div className="header-actions">{mode === 'shop' && <label className="search-box"><Search size={17} /><span className="sr-only">Buscar productos</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Buscar productos..." /></label>}<button className="icon-button" onClick={() => setCartOpen(true)} aria-label={`Abrir carrito, ${cart.length} ${cart.length === 1 ? 'artículo' : 'artículos'}`}><ShoppingBag size={19} /><span className="cart-count">{cart.length}</span></button></div></header>{mode === 'shop' ? <Storefront products={filtered} hero={hero ?? filtered[0] ?? null} category={category} setCategory={setCategory} categories={stripCategories} add={add} select={setSelectedProduct} loading={shopLoading} error={shopError} degraded={homeDegraded} /> : <OpsDashboard flash={flash} />}{selectedProduct && <ProductModal product={selectedProduct} add={add} close={() => setSelectedProduct(null)} />}{cartOpen && <CartDrawer cart={cart} subtotal={subtotal} close={() => setCartOpen(false)} checkout={handleCheckout} />}{notice && <div className="toast" role="status"><Check size={16} />{notice}</div>}</main>
}

// ── storefront ─────────────────────────────────────────────────────────────────
function Storefront({ products, hero, category, setCategory, categories, add, select, loading, error, degraded }: { products: ProductSummary[]; hero: ProductSummary | null; category: string; setCategory: (value: string) => void; categories: string[]; add: (product: ShopProduct) => void; select: (product: ShopProduct) => void; loading: boolean; error: string | null; degraded: boolean }) {
  const heroProduct: ShopProduct = hero ? { ...hero, stock: undefined, color: undefined } : PLACEHOLDER_PRODUCT
  return <><section className="hero" id="top"><div className="hero-copy"><span className="eyebrow"><Sparkles size={14} /> El futuro, hecho con criterio.</span><h1>Tecnología que <em>acompaña</em> tu forma de pensar.</h1><p>Herramientas seleccionadas para el trabajo moderno, el flujo creativo y todo lo demás. Sin ruido. Solo cosas que funcionan de verdad.</p><div className="hero-buttons"><a className="button button-primary" href="#shop">Explorar la colección <ArrowRight size={17} /></a><a className="text-link" href="#why-nova">Por qué Nova <ChevronRight size={16} /></a></div></div><div className="hero-product"><div className="hero-orbit orbit-one" /><div className="hero-orbit orbit-two" /><ProductVisual product={heroProduct} large /><div className="hero-label label-top"><span>01</span><strong>Hecho para concentrarse</strong></div></div></section><section className="category-strip" id="categories"><div className="section-intro"><span className="eyebrow">Colección seleccionada</span><h2>Encontrá tu <em>imprescindible.</em></h2></div><div className="category-list">{categories.map((item, index) => <button key={item} className={category === item ? 'category active' : 'category'} onClick={() => setCategory(item)}><span className={`category-icon category-icon-${index}`} />{item}<ArrowRight size={15} /></button>)}</div></section><section className="shop-section" id="shop"><div className="shop-heading"><div><span className="eyebrow">Explorá la selección</span><h2>{category === ALL_CATEGORIES ? 'Diseñado para hacer más.' : category}</h2></div><span className="shop-count">{products.length} {products.length === 1 ? 'producto' : 'productos'}</span></div>{degraded && <div className="inline-note"><CircleAlert size={16} /> El catálogo devolvió datos parciales (modo degradado del BFF).</div>}{loading ? <div className="empty-state"><Package size={28} /><h3>Cargando productos…</h3></div> : error ? <div className="empty-state"><CircleAlert size={28} /><h3>No se pudo cargar el catálogo</h3><p>{error}</p></div> : products.length ? <div className="product-grid">{products.map((product) => <article className="product-card" key={product.id}><button className="visual-button" onClick={() => select(product)}><ProductVisual product={{ ...product, stock: undefined, color: undefined }} /></button><div className="product-card-copy"><div className="product-meta"><span>{product.category}</span><span className="rating">★ 4.8</span></div><button className="product-name" onClick={() => select(product)}>{product.name}</button><p>{product.description}</p><div className="product-bottom"><strong>${product.price}</strong><button className="add-button" onClick={() => add({ ...product, stock: undefined, color: undefined })} aria-label={`Agregar ${product.name} al carrito`}><Plus size={17} /></button></div></div></article>)}</div> : <div className="empty-state"><Search size={28} /><h3>No se encontraron productos</h3><p>Prueba con otra búsqueda o explorá la colección completa.</p></div>}</section><section className="trust-section" id="why-nova"><div className="trust-heading"><span className="eyebrow">El estándar Nova</span><h2>Comprá menos.<br /><em>Elegí mejor.</em></h2></div><div className="trust-grid"><div><ShieldCheck size={23} /><h3>Probado, no de moda</h3><p>Cada producto gana su lugar con pruebas reales.</p></div><div><Truck size={23} /><h3>Envíos que acompañan</h3><p>Envío exprés gratis desde $150, con seguimiento incluido.</p></div><div><BarChart3 size={23} /><h3>Soporte humano</h3><p>Consejos reales de personas que se preocupan por cómo trabajás.</p></div></div></section></>
}

// ── product modal ──────────────────────────────────────────────────────────────
function ProductModal({ product, add, close }: { product: ShopProduct; add: (product: ShopProduct) => void; close: () => void }) {
  const [full, setFull] = useState<ShopProduct>(product)
  useEffect(() => {
    let active = true
    setFull(product)
    if (product.stock !== undefined) {
      return
    }
    if (isMockMode()) {
      const seeded = initialProducts.find((p) => p.id === product.id)
      if (seeded) setFull({ ...product, stock: seeded.stock })
      return
    }
    getProduct(product.id)
      .then((loaded) => {
        if (active) setFull({ ...loaded, color: loaded.color })
      })
      .catch(() => {
        /* stock stays unknown; the modal still renders the rest */
      })
    return () => {
      active = false
    }
  }, [product])
  return <div className="modal-backdrop" onClick={close}><section className="product-modal" onClick={(e) => e.stopPropagation()}><button className="modal-close" onClick={close} aria-label="Cerrar detalles del producto"><X size={20} /></button><ProductVisual product={full} large /><div className="modal-copy"><span className="eyebrow">{full.category}</span><h2>{full.name}</h2><p>{full.description}</p><div className="spec-list"><span><strong>En stock</strong>{full.stock !== undefined ? `${full.stock} unidades disponibles` : 'consultando…'}</span><span><strong>Entrega</strong>Llega en 2–3 días hábiles</span></div><div className="modal-buy"><strong>${full.price}</strong><button className="button button-primary" onClick={() => { add(full); close() }}>Agregar al carrito <Plus size={17} /></button></div></div></section></div>
}

// ── cart drawer ────────────────────────────────────────────────────────────────
function CartDrawer({ cart, subtotal, close, checkout }: { cart: ShopProduct[]; subtotal: number; close: () => void; checkout: () => void }) {
  return <div className="drawer-backdrop" onClick={close}><aside className="cart-drawer" onClick={(e) => e.stopPropagation()}><div className="drawer-header"><div><span className="eyebrow">Tu selección</span><h2>Carrito <span>({cart.length})</span></h2></div><button className="modal-close" onClick={close} aria-label="Cerrar carrito"><X size={20} /></button></div>{cart.length ? <><div className="cart-lines">{cart.map((item, index) => <div className="cart-line" key={`${item.id}-${index}`}><ProductVisual product={item} /><div className="cart-line-copy"><strong>{item.name}</strong><span>${item.price}</span></div></div>)}</div><div className="cart-summary"><div><span>Subtotal</span><strong>${subtotal}</strong></div><span>Envío calculado al finalizar la compra</span><button className="button button-primary full-button" onClick={checkout}>Finalizar compra <ArrowRight size={17} /></button></div></> : <div className="empty-cart"><ShoppingBag size={32} /><h3>Tu carrito te espera.</h3><p>Agregá algo útil. O hermoso.</p></div>}</aside></div>
}

// ── ops dashboard ──────────────────────────────────────────────────────────────
type TabId = 'overview' | 'catalog' | 'inventory' | 'returns' | 'shipping' | 'orders' | 'payments' | 'notifications' | 'analytics' | 'customers' | 'search' | 'carts' | 'users' | 'settings' | 'infra'

function OpsDashboard({ flash }: { flash: (message: string) => void }) {
  const [tab, setTab] = useState<TabId>('overview')
  const [products, setProducts] = useState<Product[]>([])
  const [returns, setReturns] = useState<ReturnRequest[]>([])
  const [shipments, setShipments] = useState<ShippingOrder[]>([])
  const [orders, setOrders] = useState<Order[]>([])
  const [payments, setPayments] = useState<Payment[]>([])
  const [notifications, setNotifications] = useState<Notification[]>([])
  const [analytics, setAnalytics] = useState<AnalyticsEvent[]>([])
  const [inventory, setInventory] = useState<InventoryItem[]>([])
  const [opsLoading, setOpsLoading] = useState(true)
  const [newProduct, setNewProduct] = useState({ name: '', price: '', stock: '' })
  const [notifSubject, setNotifSubject] = useState('')

  useEffect(() => {
    let active = true
    ;(async () => {
      setOpsLoading(true)
      const results = await Promise.allSettled([
        getProducts(),
        getReturns(),
        getShipments(),
        getOrders(),
        getPayments(),
        getNotifications(),
        getAnalyticsEvents(),
        getInventory(),
      ])
      if (!active) return
      const [p, r, s, o, pay, n, a, inv] = results
      if (p.status === 'fulfilled') setProducts(p.value)
      if (r.status === 'fulfilled') setReturns(r.value)
      if (s.status === 'fulfilled') setShipments(s.value)
      if (o.status === 'fulfilled') setOrders(o.value)
      if (pay.status === 'fulfilled') setPayments(pay.value)
      if (n.status === 'fulfilled') setNotifications(n.value)
      if (a.status === 'fulfilled') setAnalytics(a.value)
      if (inv.status === 'fulfilled') setInventory(inv.value)
      const failures = results.filter((result) => result.status === 'rejected').length
      if (failures > 0 && !isMockMode()) {
        flash(`No se pudieron cargar ${failures} sección(es). Revisá que los servicios estén arriba.`)
      }
      setOpsLoading(false)
    })()
    return () => {
      active = false
    }
  }, [flash])

  const customers: CustomerRow[] = useMemo(() => {
    if (isMockMode()) {
      return [
        { customerId: 'cust-alex', name: 'Alex Morgan', orderCount: 3, lifetimeTotal: 1284 },
        { customerId: 'cust-jordan', name: 'Jordan Lee', orderCount: 2, lifetimeTotal: 458 },
        { customerId: 'cust-sam', name: 'Sam Rivera', orderCount: 1, lifetimeTotal: 149 },
      ]
    }
    const byCustomer = new Map<string, CustomerRow>()
    for (const order of orders) {
      const row = byCustomer.get(order.customerId) ?? { customerId: order.customerId, orderCount: 0, lifetimeTotal: 0 }
      row.orderCount += 1
      row.lifetimeTotal += order.total
      byCustomer.set(order.customerId, row)
    }
    return Array.from(byCustomer.values()).sort((a, b) => b.lifetimeTotal - a.lifetimeTotal)
  }, [orders])

  const nav = [
    { id: 'overview', label: 'Resumen' },
    { id: 'catalog', label: 'Catálogo CRUD' },
    { id: 'inventory', label: 'Inventario y reservas' },
    { id: 'orders', label: 'Órdenes' },
    { id: 'payments', label: 'Pagos' },
    { id: 'shipping', label: 'Cola de envíos' },
    { id: 'returns', label: 'Cola de devoluciones' },
    { id: 'notifications', label: 'Notificaciones' },
    { id: 'analytics', label: 'Eventos de analytics' },
    { id: 'customers', label: 'Clientes' },
    { id: 'search', label: 'Búsqueda y recomendaciones' },
    { id: 'carts', label: 'Carritos' },
    { id: 'users', label: 'Usuarios y roles' },
    { id: 'settings', label: 'Configuración' },
    { id: 'infra', label: 'Identidad e infraestructura' },
  ] as const

  const handleCreateProduct = async () => {
    if (!newProduct.name) return
    try {
      const created = await createProduct({
        name: newProduct.name,
        category: 'Accessories',
        price: Number(newProduct.price) || 0,
        stock: Number(newProduct.stock) || 0,
        description: 'Nuevo producto del catálogo',
        color: 'hub',
      })
      setProducts((current) => [...current, created])
      setNewProduct({ name: '', price: '', stock: '' })
      flash(isMockMode()
        ? `POST /api/catalog/products · 201 (mock) · #${created.id}`
        : `POST /api/catalog/products · 201 · #${created.id}`)
    } catch (error) {
      flash(uiErrorMessage(error))
    }
  }

  const handleDeleteProduct = async (id: number) => {
    try {
      await deleteProduct(id)
      setProducts((current) => current.filter((product) => product.id !== id))
      flash(isMockMode() ? `DELETE /api/catalog/products/${id} · 204 (mock)` : `DELETE /api/catalog/products/${id} · 204`)
    } catch (error) {
      flash(uiErrorMessage(error))
    }
  }

  const handleStockDelta = async (item: InventoryItem, delta: number) => {
    const nextLevel = Math.max(0, item.stockLevel + delta)
    try {
      const updated = await updateStock(item.productId, nextLevel)
      setInventory((current) => current.map((row) => (row.productId === updated.productId ? updated : row)))
      setProducts((current) => current.map((product) => (product.id === updated.productId ? { ...product, stock: updated.stockLevel } : product)))
      flash(isMockMode()
        ? `PUT /api/inventory/${item.productId} · stockLevel ${nextLevel} (mock)`
        : `PUT /api/inventory/${item.productId} · stockLevel ${nextLevel}`)
    } catch (error) {
      flash(uiErrorMessage(error))
    }
  }

  const handleReserve = async (item: InventoryItem) => {
    const available = item.stockLevel - item.reserved
    try {
      const reservation = await reserveStock(item.productId, 1, available)
      setInventory((current) => current.map((row) => (row.productId === item.productId ? { ...row, reserved: row.reserved + 1 } : row)))
      flash(isMockMode()
        ? `POST /api/inventory/reserve · 201 (mock) · reserva #${reservation.id}`
        : `POST /api/inventory/reserve · 201 · reserva #${reservation.id}`)
    } catch (error) {
      flash(error instanceof ApiError && error.status === 409
        ? 'Stock insuficiente (409): no hay unidades disponibles para reservar.'
        : uiErrorMessage(error))
    }
  }

  const handleAdvanceShipment = async (shipment: ShippingOrder) => {
    const next = shipment.status === 'PENDING' ? 'SHIPPED' : 'DELIVERED'
    try {
      const updated = await updateShipmentStatus(shipment.id, next, shipments)
      setShipments((current) => current.map((row) => (row.id === shipment.id ? updated : row)))
      flash(next === 'SHIPPED'
        ? `PATCH /api/shipping/${shipment.id}/status · SHIPPED · tracking asignado`
        : `PATCH /api/shipping/${shipment.id}/status · DELIVERED`)
    } catch (error) {
      flash(uiErrorMessage(error))
    }
  }

  const handleResolveReturn = async (returnCase: ReturnRequest, verdict: 'APPROVED' | 'REJECTED') => {
    try {
      const updated = verdict === 'APPROVED' ? await approveReturn(returnCase.id, returns) : await rejectReturn(returnCase.id, returns)
      setReturns((current) => current.map((row) => (row.id === returnCase.id ? updated : row)))
      flash(`POST /api/returns/${returnCase.id}/${verdict.toLowerCase()} · 200`)
    } catch (error) {
      flash(uiErrorMessage(error))
    }
  }

  const handleVerifyPayment = async (order: Order) => {
    if (isMockMode()) {
      flash(`Verificación · GET /api/payments/order/${order.id} · ${order.payment ?? 'sin dato'} (mock)`)
      return
    }
    try {
      const list = await getPaymentByOrder(order.id)
      const result = list.map((payment) => payment.status).join(', ') || 'sin pagos registrados'
      flash(`GET /api/payments/order/${order.id} · ${result}`)
    } catch (error) {
      flash(uiErrorMessage(error))
    }
  }

  const handleCreateNotification = async () => {
    try {
      const created = await createNotification({ type: 'PROMOTIONAL', recipient: 'operators@nova.local', subject: notifSubject || 'Campaña promocional manual', body: 'Campaña promocional manual' })
      setNotifications((current) => [...current, created])
      setNotifSubject('')
      flash(`POST /api/notifications · 201 · ${created.type}`)
    } catch (error) {
      flash(uiErrorMessage(error))
    }
  }

  return <section className="ops-page"><div className="ops-header"><div><span className="eyebrow"><BarChart3 size={14} /> Admin · rol: admin</span><h1>Buen día, <em>equipo.</em></h1><p>Controles operativos conectados a los microservicios del e-commerce.</p></div><button className="button button-primary" onClick={() => (isMockMode() ? flash('Informe exportado · GET /api/analytics/reports/summary (mock)') : flash('No existe un endpoint de exportación; consultá GET /api/analytics/reports/*'))}><Package size={16} /> Exportar informe</button></div><div className="ops-layout"><aside className="ops-sidebar">{nav.map((item) => <button className={tab === item.id ? 'ops-nav active' : 'ops-nav'} key={item.id} onClick={() => setTab(item.id)}>{item.label}<ChevronRight size={14} /></button>)}</aside><div className="ops-content">{opsLoading ? <div className="admin-panel"><p>Cargando datos…</p></div> : <>{tab === 'overview' && <Overview products={products} returns={returns} shipments={shipments} orders={orders} />}{tab === 'catalog' && <div className="admin-panel"><PanelTitle eyebrow="catalog-svc · CRUD" title="Productos" /><div className="admin-form"><input placeholder="Nombre del producto" value={newProduct.name} onChange={(e) => setNewProduct({ ...newProduct, name: e.target.value })} /><input type="number" placeholder="Precio" value={newProduct.price} onChange={(e) => setNewProduct({ ...newProduct, price: e.target.value })} /><input type="number" placeholder="Stock" value={newProduct.stock} onChange={(e) => setNewProduct({ ...newProduct, stock: e.target.value })} /><button className="button button-primary" onClick={handleCreateProduct}>Crear producto</button></div><div className="admin-table">{products.map((product) => <div className="admin-row" key={product.id}><span><strong>{product.name}</strong><small>GET / PUT / DELETE · id {product.id}</small></span><b>${product.price}</b><button className="status-pill" onClick={() => handleDeleteProduct(product.id)}>Eliminar</button></div>)}</div></div>}{tab === 'inventory' && <div className="admin-panel"><PanelTitle eyebrow="inventory-svc · sincronizado" title="Stock y reservas" /><div className="admin-table">{inventory.map((item) => { const available = item.stockLevel - item.reserved; return <div className="admin-row" key={item.id}><span><strong>{item.name}</strong><small>GET /api/inventory/{item.productId} · {available < 10 ? 'STOCK BAJO' : 'disponible'}</small></span><b>{item.stockLevel} unidades</b><div className="row-actions"><button onClick={() => handleStockDelta(item, -1)}>−</button><button onClick={() => handleStockDelta(item, 1)}>+</button><button onClick={() => handleReserve(item)}>Reservar</button></div></div> })}</div><div className="inline-note"><CircleAlert size={16} /> Las reservas tienen un TTL de 15 minutos configurado; liberá manualmente las reservas vencidas.</div></div>}{tab === 'returns' && <div className="admin-panel"><PanelTitle eyebrow="returns-svc · revisión manual" title="Cola de devoluciones" />{returns.length ? returns.map((returnCase) => <div className="workflow-row" key={returnCase.id}><span><strong>{`RET-${returnCase.id}`} · {returnCase.orderId}</strong><small>Motivo: {returnCase.reason} · {returnCase.quantity} unidad(es)</small></span><b className={returnCase.status === 'PENDING_REVIEW' ? 'warning-text' : ''}>{returnCase.status}</b>{returnCase.status === 'PENDING_REVIEW' && <div className="row-actions"><button onClick={() => handleResolveReturn(returnCase, 'APPROVED')}>Aprobar</button><button onClick={() => handleResolveReturn(returnCase, 'REJECTED')}>Rechazar</button></div>}</div>) : <div className="empty-state"><CircleAlert size={28} /><h3>Sin devoluciones pendientes</h3></div>}</div>}{tab === 'shipping' && <div className="admin-panel"><PanelTitle eyebrow="shipping-svc · máquina de estados estricta" title="Envíos" />{shipments.length ? shipments.map((item) => <div className="workflow-row" key={item.id}><span><strong>{`SHP-${item.id}`} · {item.orderId}</strong><small>{item.trackingNumber ? `Seguimiento ${item.trackingNumber}` : 'Sin número de seguimiento'}</small></span><b>{item.status}</b>{item.status !== 'DELIVERED' && <button onClick={() => handleAdvanceShipment(item)}>{item.status === 'PENDING' ? 'Enviar pedido' : 'Marcar como entregado'}</button>}</div>) : <div className="empty-state"><Truck size={28} /><h3>Sin envíos registrados</h3></div>}</div>}{tab === 'orders' && <div className="admin-panel"><PanelTitle eyebrow="order-svc · payment-svc · checkout-svc" title="Órdenes y verificación de pagos" /><div className="inline-note"><CircleAlert size={16} /> Las transiciones de estado son visibles a propósito: la API hoy no tiene endpoint de transición de órdenes.</div>{orders.map((order) => <div className="workflow-row" key={order.id}><span><strong>{orderRef(order, order.id)} · {customerName(order.customerId)}</strong><small>GET /api/payments/order/{order.id} · checkout registrado</small></span><b>${order.total} · {order.status}</b><button onClick={() => handleVerifyPayment(order)}>Verificar pago</button></div>)}</div>}{tab === 'payments' && <PaymentsPanel payments={payments} orders={orders} />}{tab === 'notifications' && <div className="admin-panel"><PanelTitle eyebrow="notification-svc" title="Notificaciones y campañas" /><div className="admin-form"><input aria-label="Asunto de la notificación" placeholder="Asunto de la notificación" value={notifSubject} onChange={(e) => setNotifSubject(e.target.value)} /><button className="button button-primary" onClick={handleCreateNotification}><Plus size={16} /> Crear notificación PROMOTIONAL</button></div>{notifications.map((item, index) => <div className="workflow-row" key={`${item.id}-${index}`}><span><strong>{item.type} · {item.subject}</strong><small>GET /api/notifications · entrega en cola ({item.recipient})</small></span><b>LISTA</b></div>)}</div>}{tab === 'analytics' && <AnalyticsPanel events={analytics} />}{tab === 'customers' && <CustomersPanel customers={customers} />}{['search', 'carts', 'users', 'settings'].includes(tab) && <AdminModule tab={tab} flash={flash} />}{tab === 'infra' && <div className="admin-panel"><PanelTitle eyebrow="Keycloak · gateway · actuators" title="Identidad e infraestructura" /><div className="service-grid">{mockInfraRows.map((item) => <div key={item}><Check size={16} /><span>{item}</span></div>)}</div><div className="inline-note"><ShieldCheck size={16} /> Antes de producción: validar los JWTs de Keycloak en el gateway y aplicar autorización de administrador por endpoint.</div></div>}</>}</div></div></section>
}

// ── admin panels ───────────────────────────────────────────────────────────────
function AdminModule({ tab, flash }: { tab: string; flash: (message: string) => void }) {
  const config = adminModuleConfigs[tab]
  if (!config) return null
  return <div className="admin-panel"><PanelTitle eyebrow={config.eyebrow} title={config.title} /><p className="module-description">{config.description}</p><div className="admin-table">{config.rows.map((row) => <div className="admin-row" key={row}><span><strong>{row.split(' · ')[0]}</strong><small>{row.split(' · ').slice(1).join(' · ')}</small></span><span className="status-pill">SOLO LECTURA</span></div>)}</div><button className="button button-secondary module-action" onClick={() => (isMockMode() ? flash(`${config.action} · respuesta del servicio recibida (mock)`) : flash(`${config.action} · sin endpoint disponible en el backend`))}>{config.action}</button></div>
}

function PaymentsPanel({ payments, orders }: { payments: Payment[]; orders: Order[] }) {
  return <div className="admin-panel"><PanelTitle eyebrow="payment-svc · GET /api/payments" title="Pagos" /><p className="module-description">Transacciones aprobadas y rechazadas con seguimiento de auditoría inmutable.</p><div className="inline-note"><CircleAlert size={16} /> Regla de negocio: una tarjeta cuyo número de referencia termina en 0000 se rechaza (DECLINED); montos ≤ 0 también.</div><div className="admin-table">{payments.length ? payments.map((payment) => <div className="admin-row" key={payment.id}><span><strong>{`PAY-${payment.id}`}</strong><small>{orderRef(orders.find((o) => o.id === payment.orderId), payment.orderId)} · {payment.method} · GET /api/payments/order/{payment.orderId}</small></span><b>${payment.amount}</b><span className={`status-pill ${payment.status === 'DECLINED' ? 'status-declined' : ''}`}>{payment.status}</span></div>) : <div className="empty-state"><CircleAlert size={28} /><h3>Sin pagos registrados</h3></div>}</div></div>
}

function AnalyticsPanel({ events }: { events: AnalyticsEvent[] }) {
  return <div className="admin-panel"><PanelTitle eyebrow="analytics-svc · GET /api/analytics/events" title="Eventos de analytics" /><p className="module-description">Eventos VIEW, ADD_TO_CART, PURCHASE, SEARCH y CLICK registrados por la plataforma.</p><div className="admin-table">{events.length ? events.map((event) => <div className="admin-row" key={event.id}><span><strong>{event.type}</strong><small>{event.productId ? `producto ${event.productId}` : event.customerId ?? 'sin referencia'} · {event.timestamp ? new Date(event.timestamp).toISOString().slice(11, 19) : '—'}</small></span><span className="status-pill">{event.value ? `${event.value}` : 'SOLO LECTURA'}</span></div>) : <div className="empty-state"><CircleAlert size={28} /><h3>Sin eventos registrados</h3></div>}</div></div>
}

function CustomersPanel({ customers }: { customers: CustomerRow[] }) {
  return <div className="admin-panel"><PanelTitle eyebrow="aggregation · orders + events" title="Clientes" /><p className="module-description">La vista de clientes se deriva de los customerId de las órdenes hasta que exista un servicio dedicado (la ruta /api/customers/** es un placeholder).</p><div className="admin-table">{customers.length ? customers.map((customer) => <div className="admin-row" key={customer.customerId}><span><strong>{customer.name ?? customer.customerId}</strong><small>{customer.customerId} · órdenes:{customer.orderCount}</small></span><b>${customer.lifetimeTotal.toLocaleString('en-US')} acumulado</b><span className="status-pill">SOLO LECTURA</span></div>) : <div className="empty-state"><CircleAlert size={28} /><h3>Sin clientes registrados</h3></div>}</div></div>
}

function PanelTitle({ eyebrow, title }: { eyebrow: string; title: string }) {
  return <div className="panel-title"><span className="eyebrow">{eyebrow}</span><h2>{title}</h2></div>
}

function Overview({ products, returns, shipments, orders }: { products: Product[]; returns: ReturnRequest[]; shipments: ShippingOrder[]; orders: Order[] }) {
  const grossSales = orders.reduce((sum, order) => sum + order.total, 0)
  return <><div className="ops-stats"><div><span>Ventas brutas</span><strong>${grossSales.toLocaleString('en-US')}</strong><small className="positive">+18,4% vs. la semana pasada</small></div><div><span>Órdenes</span><strong>{orders.length}</strong><small className="positive">+12,6% vs. la semana pasada</small></div><div><span>Devoluciones pendientes</span><strong>{returns.filter((item) => item.status === 'PENDING_REVIEW').length}</strong><small className="warning-text">Requieren revisión</small></div><div><span>Alertas de stock bajo</span><strong>{products.filter((item) => item.stock < 10).length}</strong><small className="warning-text">Requieren atención</small></div></div><div className="ops-grid"><div className="analytics-card"><PanelTitle eyebrow="analytics-svc · GET /reports/summary" title="Resumen de ventas" /><div className="chart"><div className="chart-y"><span>$30k</span><span>$20k</span><span>$10k</span><span>$0</span></div><div className="bars">{chartData.map((height, index) => <div className="bar-wrap" key={index}><div className="bar" style={{ height: `${height}%` }} /><span>{index + 1}</span></div>)}</div></div></div><div className="inventory-card"><PanelTitle eyebrow="shipping-svc" title="Colas en vivo" />{shipments.length ? shipments.map((shipment) => <div className="queue-item" key={shipment.id}><Truck size={16} /><span><strong>{shipment.orderId}</strong><small>{shipment.status}{shipment.trackingNumber ? ` · ${shipment.trackingNumber}` : ''}</small></span></div>) : <div className="empty-state"><Truck size={28} /><h3>Sin envíos activos</h3></div>}</div></div></>
}

export { ProductVisual }