// Optional analytics only. No non-essential script is loaded until the visitor opts in.
const rawUrl = import.meta.env.VITE_ANALYTICS_SCRIPT_URL || ''
function safeScriptUrl(url: string): string {
  try { const parsed = new URL(url); return parsed.protocol === 'https:' ? parsed.href : '' }
  catch { return '' }
}
export const analyticsScriptUrl = safeScriptUrl(rawUrl)
export const analyticsConfigured = Boolean(analyticsScriptUrl)
const key = 'linknux.cookie.analytics.v1'
export function analyticsChoice(): boolean | null {
  try { const choice = localStorage.getItem(key); return choice === 'yes' ? true : choice === 'no' ? false : null }
  catch { return null }
}
let loaded = false
export function applyAnalyticsChoice(enabled: boolean) {
  try { localStorage.setItem(key, enabled ? 'yes' : 'no') } catch { /* Storage may be blocked. */ }
  if (enabled && analyticsConfigured && !loaded) {
    const script = document.createElement('script')
    script.src = analyticsScriptUrl
    script.async = true
    script.dataset.cookieCategory = 'analytics'
    document.head.appendChild(script)
    loaded = true
  }
  // Revocation blocks future loads; previously loaded third-party code may require a page reload.
  if (!enabled && loaded) window.location.reload()
}
export function openCookiePreferences() { window.dispatchEvent(new Event('open-cookie-preferences')) }
