import http from './http'

const storageKey = 'subscription-referral-invite'
const lifetime = 30 * 24 * 60 * 60 * 1000

export function savedInvite(): string {
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey) || 'null')
    if (saved && saved.expires > Date.now()) return String(saved.code)
    localStorage.removeItem(storageKey)
  } catch { /* An unavailable browser store must not prevent registration. */ }
  return ''
}

let captureQueue: Promise<string> = Promise.resolve('')
export function captureInvite(candidate: unknown): Promise<string> {
  captureQueue = captureQueue.then(() => validateAndCapture(candidate))
  return captureQueue
}

async function validateAndCapture(candidate: unknown): Promise<string> {
  const existing = savedInvite()
  const code = existing || (typeof candidate === 'string' ? candidate.trim() : '')
  if (!code || code.length > 32) return ''
  try {
    const { data } = await http.get('/api/public/referrals/invite', { params: { code } })
    if (!data.valid) {
      try { localStorage.removeItem(storageKey) } catch { /* Registration can continue. */ }
      return ''
    }
    if (existing) return existing
    const first = savedInvite()
    if (first) return first
    try { localStorage.setItem(storageKey, JSON.stringify({ code, expires: Date.now() + lifetime })) } catch { /* Keep this page's valid invitation. */ }
    return code
  } catch { return '' }
}
