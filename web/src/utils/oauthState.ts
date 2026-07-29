export type OAuthProvider = 'github' | 'google'

export type OAuthStateErrorCode =
  | 'INVALID_PROVIDER'
  | 'INVALID_STATE'
  | 'INVALID_REDIRECT'
  | 'STORAGE_UNAVAILABLE'
  | 'MISSING_STATE'
  | 'CORRUPT_STATE'
  | 'EXPIRED_STATE'
  | 'PROVIDER_MISMATCH'
  | 'STATE_MISMATCH'

export class OAuthStateError extends Error {
  readonly code: OAuthStateErrorCode

  constructor(code: OAuthStateErrorCode, message: string, options?: ErrorOptions) {
    super(message, options)
    this.name = 'OAuthStateError'
    this.code = code
  }
}

export interface OAuthStateRecord {
  provider: OAuthProvider
  state: string
  redirect?: string
  createdAt: number
}

export interface ConsumedOAuthState {
  provider: OAuthProvider
  redirect?: string
}

const STORAGE_KEY = 'api-transit.oauth-state.v1'
const STORAGE_VERSION = 1
export const OAUTH_STATE_TTL_MS = 10 * 60 * 1000

interface StoredOAuthState extends OAuthStateRecord {
  version: typeof STORAGE_VERSION
}

function oauthStorage(): Storage {
  try {
    if (typeof sessionStorage === 'undefined') {
      throw new Error('sessionStorage is not available')
    }
    return sessionStorage
  } catch (error) {
    throw new OAuthStateError(
      'STORAGE_UNAVAILABLE',
      'OAuth session storage is unavailable',
      { cause: error },
    )
  }
}

function assertProvider(provider: string): asserts provider is OAuthProvider {
  if (provider !== 'github' && provider !== 'google') {
    throw new OAuthStateError('INVALID_PROVIDER', 'Unsupported OAuth provider')
  }
}

function assertState(state: string): void {
  if (typeof state !== 'string' || state.trim().length === 0 || state.length > 512) {
    throw new OAuthStateError('INVALID_STATE', 'OAuth state must contain between 1 and 512 characters')
  }
}

function assertRedirect(redirect: string | undefined): void {
  if (redirect === undefined) return

  // Only application-local paths are persisted so this value cannot become an
  // open redirect after a successful OAuth callback.
  if (
    redirect.length === 0
    || redirect.length > 2_048
    || !redirect.startsWith('/')
    || redirect.startsWith('//')
    || redirect.includes('\\')
    || /[\u0000-\u001f\u007f]/.test(redirect)
  ) {
    throw new OAuthStateError('INVALID_REDIRECT', 'OAuth redirect must be a local application path')
  }
}

function parseRecord(raw: string): StoredOAuthState {
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch (error) {
    throw new OAuthStateError('CORRUPT_STATE', 'Stored OAuth state is not valid JSON', { cause: error })
  }

  if (!value || typeof value !== 'object') {
    throw new OAuthStateError('CORRUPT_STATE', 'Stored OAuth state has an invalid shape')
  }

  const record = value as Partial<StoredOAuthState>
  try {
    if (record.version !== STORAGE_VERSION) {
      throw new OAuthStateError('CORRUPT_STATE', 'Stored OAuth state has an unsupported version')
    }
    assertProvider(record.provider as string)
    assertState(record.state as string)
    assertRedirect(record.redirect)
    if (!Number.isFinite(record.createdAt) || !Number.isInteger(record.createdAt)) {
      throw new OAuthStateError('CORRUPT_STATE', 'Stored OAuth state has an invalid timestamp')
    }
  } catch (error) {
    if (error instanceof OAuthStateError && error.code === 'CORRUPT_STATE') throw error
    throw new OAuthStateError('CORRUPT_STATE', 'Stored OAuth state has an invalid shape', { cause: error })
  }

  return record as StoredOAuthState
}

function statesMatch(expected: string, returned: string): boolean {
  // Avoid an early-exit comparison. OAuth state is public to the browser, but
  // keeping comparison work independent of the matching prefix is inexpensive.
  const length = Math.max(expected.length, returned.length)
  let difference = expected.length ^ returned.length
  for (let index = 0; index < length; index += 1) {
    difference |= (expected.charCodeAt(index) || 0) ^ (returned.charCodeAt(index) || 0)
  }
  return difference === 0
}

export function saveOAuthState(
  provider: OAuthProvider,
  state: string,
  redirect?: string,
): void {
  assertProvider(provider)
  assertState(state)
  assertRedirect(redirect)

  const record: StoredOAuthState = {
    version: STORAGE_VERSION,
    provider,
    state,
    ...(redirect === undefined ? {} : { redirect }),
    createdAt: Date.now(),
  }

  try {
    oauthStorage().setItem(STORAGE_KEY, JSON.stringify(record))
  } catch (error) {
    if (error instanceof OAuthStateError) throw error
    throw new OAuthStateError('STORAGE_UNAVAILABLE', 'Unable to save OAuth state', { cause: error })
  }
}

export function consumeOAuthState(
  provider: OAuthProvider,
  returnedState: string,
): ConsumedOAuthState {
  const storage = oauthStorage()
  let raw: string | null

  try {
    raw = storage.getItem(STORAGE_KEY)
    // Delete before validation: success, mismatch, expiry, and corrupt records
    // are all one-shot and cannot be replayed.
    storage.removeItem(STORAGE_KEY)
  } catch (error) {
    throw new OAuthStateError('STORAGE_UNAVAILABLE', 'Unable to consume OAuth state', { cause: error })
  }

  if (raw === null) {
    throw new OAuthStateError('MISSING_STATE', 'No pending OAuth state was found')
  }

  const record = parseRecord(raw)
  assertProvider(provider)
  assertState(returnedState)

  if (record.provider !== provider) {
    throw new OAuthStateError('PROVIDER_MISMATCH', 'OAuth provider does not match the pending request')
  }

  const age = Date.now() - record.createdAt
  if (age < 0 || age > OAUTH_STATE_TTL_MS) {
    throw new OAuthStateError('EXPIRED_STATE', 'OAuth state has expired')
  }

  if (!statesMatch(record.state, returnedState)) {
    throw new OAuthStateError('STATE_MISMATCH', 'OAuth state does not match the pending request')
  }

  return {
    provider: record.provider,
    ...(record.redirect === undefined ? {} : { redirect: record.redirect }),
  }
}

export function peekOAuthState(): OAuthStateRecord | null {
  let raw: string | null
  try {
    raw = oauthStorage().getItem(STORAGE_KEY)
  } catch (error) {
    if (error instanceof OAuthStateError) throw error
    throw new OAuthStateError('STORAGE_UNAVAILABLE', 'Unable to read OAuth state', { cause: error })
  }
  if (raw === null) return null

  const { provider, state, redirect, createdAt } = parseRecord(raw)
  return {
    provider,
    state,
    ...(redirect === undefined ? {} : { redirect }),
    createdAt,
  }
}

export function clearOAuthState(): void {
  try {
    oauthStorage().removeItem(STORAGE_KEY)
  } catch (error) {
    if (error instanceof OAuthStateError) throw error
    throw new OAuthStateError('STORAGE_UNAVAILABLE', 'Unable to clear OAuth state', { cause: error })
  }
}
