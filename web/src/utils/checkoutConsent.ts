type Order = { id: number; amountCents?: number; currency?: string; paymentAmountCents?: number; paymentCurrency?: string; paymentMethod?: string }
type Consent = { owner: string; order: string; disclosure: string; at: number }
const key = 'checkout-order-consents-v1'
const signature = (order: Order) => JSON.stringify([order.id,order.amountCents,order.currency,order.paymentAmountCents,order.paymentCurrency,order.paymentMethod])
function read(): Consent[] {
  try { const data = JSON.parse(sessionStorage.getItem(key) || '[]'); return Array.isArray(data) ? data.filter(x => x && typeof x.at === 'number' && Date.now()-x.at >= 0 && Date.now()-x.at < 86400000).slice(-50) : [] } catch { return [] }
}
export function rememberedConsent(owner: string, order: Order): string | undefined {
  if (!owner) return undefined
  const value = read().find(x => x.owner === owner && x.order === signature(order))?.disclosure
  return typeof value === 'string' && value ? value : undefined
}
export function rememberConsent(owner: string, order: Order, disclosure?: string) {
  if (!owner) return
  const rows = read().filter(x => !(x.owner === owner && x.order === signature(order)))
  if (disclosure) rows.push({ owner, order:signature(order), disclosure, at:Date.now() })
  try { sessionStorage.setItem(key, JSON.stringify(rows.slice(-50))) } catch { /* storage unavailable: require explicit confirmation again */ }
}
