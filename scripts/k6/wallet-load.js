import http from 'k6/http'
import { check, sleep } from 'k6'
import { SharedArray } from 'k6/data'
import { Rate } from 'k6/metrics'

const BASE_URL = (__ENV.BASE_URL || 'https://api.linknux.com').replace(/\/$/, '')
const USER_SOURCE_FILE = __ENV.USER_DATA_FILE || './scripts/k6/user-token-pool.csv'
const USER_SOURCE_TEXT = __ENV.USER_DATA || null
const SUMMARY_PATH = __ENV.SUMMARY_OUT || null
const MODE = (__ENV.SCENARIO_MODE || 'mixed').toLowerCase()
const AUTH_MODE = (__ENV.AUTH_MODE || 'token').toLowerCase()
const TARGET_WRITE_RATIO = Number(__ENV.WRITE_RATIO || 0.3)
const DEFAULT_CUSTOM_AMOUNT = Number(__ENV.CUSTOM_AMOUNT || 12.34)
const PAYMENT_METHOD = __ENV.PAYMENT_METHOD || 'alipay'
const WALLET_PAGE = Number(__ENV.WALLET_PAGE || 2)
const WALLET_PAGE_SIZE = Number(__ENV.WALLET_PAGE_SIZE || 50)
const STAGE_PLAN = parseStagePlan(__ENV.STAGE_PLAN)
const ALLOW_TOKEN_REUSE = String(__ENV.ALLOW_TOKEN_REUSE || 'false').toLowerCase() === 'true'

const serverErrorRate = new Rate('server_error_rate')
const timeoutRate = new Rate('timeout_rate')
const loginFailureRate = new Rate('login_failure_rate')

function parseStagePlan(raw) {
  const fallback = [
    { duration: '2m', target: 20 },
    { duration: '2m', target: 50 },
    { duration: '2m', target: 100 },
    { duration: '2m', target: 200 },
    { duration: '2m', target: 400 },
    { duration: '2m', target: 800 },
  ]

  if (!raw) return fallback

  try {
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed) || parsed.length === 0) return fallback

    const normalized = parsed
      .map((entry) => {
        if (!entry || typeof entry !== 'object') return null
        const duration = String(entry.duration || '').trim()
        const target = Number(entry.target)
        if (!duration || !Number.isFinite(target) || target <= 0) return null
        return { duration, target: Math.trunc(target) }
      })
      .filter(Boolean)

    return normalized.length > 0 ? normalized : fallback
  } catch {
    return fallback
  }
}

function parseDurationMs(duration) {
  const match = String(duration).trim().match(/^(\d+(?:\.\d+)?)(ms|s|m|h)$/)
  if (!match) throw new Error(`Unsupported stage duration: ${duration}`)
  const factors = { ms: 1, s: 1000, m: 60000, h: 3600000 }
  return Number(match[1]) * factors[match[2]]
}

function scenarioFunctionName() {
  if (MODE === 'read') return 'readOnlyScenario'
  if (MODE === 'write') return 'writeOnlyScenario'
  return 'mixedScenario'
}

function buildStageScenarios() {
  let startTimeMs = 0
  return Object.fromEntries(STAGE_PLAN.map((stage, index) => {
    const name = `stage_${index + 1}_${stage.target}`
    const scenario = {
      executor: 'constant-vus',
      vus: stage.target,
      duration: stage.duration,
      startTime: `${startTimeMs}ms`,
      gracefulStop: '5s',
      exec: scenarioFunctionName(),
      tags: { load_stage: String(stage.target) },
    }
    startTimeMs += parseDurationMs(stage.duration)
    return [name, scenario]
  }))
}

function buildThresholds() {
  const thresholds = {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1200', 'p(99)<3000'],
    server_error_rate: ['rate<0.0005'],
    timeout_rate: ['rate<0.0005'],
    login_failure_rate: ['rate<0.10'],
  }

  for (const stage of STAGE_PLAN) {
    const tag = `{load_stage:${stage.target}}`
    thresholds[`http_req_failed${tag}`] = ['rate<0.01']
    thresholds[`http_req_duration${tag}`] = ['p(95)<1200', 'p(99)<3000']
    thresholds[`http_reqs${tag}`] = ['count>0']
    thresholds[`server_error_rate${tag}`] = ['rate<0.0005']
    thresholds[`timeout_rate${tag}`] = ['rate<0.0005']
  }
  return thresholds
}

function parseUserRows() {
  const raw = USER_SOURCE_TEXT
    ? USER_SOURCE_TEXT
    : open(USER_SOURCE_FILE)

  if (!raw) return []
  const text = String(raw).trim()
  if (!text) return []

  if (text.startsWith('[')) {
    const items = JSON.parse(text)
    if (!Array.isArray(items)) return []
    return items
      .map((item) => ({
        identifier: String(item.identifier || item.username || item.email || '').trim(),
        password: String(item.password || '').trim(),
        accessToken: String(item.accessToken || item.access_token || '').trim(),
      }))
      .filter((item) => item.identifier)
  }

  const rows = text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#'))

  if (rows.length === 0) return []
  const first = rows[0].toLowerCase()
  const hasHeader = first.includes('identifier') || first.includes('access_token') || first.includes('password')
  const records = hasHeader ? rows.slice(1) : rows

  return records.map((line) => {
    const [identifierRaw, passwordRaw = '', tokenRaw = ''] = line.split(',').map((value) => value.trim())
    return {
      identifier: identifierRaw,
      password: passwordRaw,
      accessToken: tokenRaw,
    }
  }).filter((item) => item.identifier)
}

const CREDENTIAL_POOL = new SharedArray('user-pool', parseUserRows)

function safeJson(response) {
  try {
    return response.json()
  } catch (_) {
    return null
  }
}

function is2xx(status) {
  return status >= 200 && status < 300
}

function trackFailure(response) {
  if (!response) {
    timeoutRate.add(1)
    serverErrorRate.add(0)
    return
  }
  if (response.error || response.status === 0) {
    timeoutRate.add(1)
    serverErrorRate.add(0)
    return
  }
  timeoutRate.add(0)
  serverErrorRate.add(response.status >= 500)
}

function loginAndGetToken(identifier, password) {
  const response = http.post(`${BASE_URL}/auth/login`,
    JSON.stringify({ identifier, password }),
    { headers: { 'Content-Type': 'application/json' } }
  )
  trackFailure(response)
  if (!is2xx(response.status)) {
    loginFailureRate.add(1)
    return null
  }
  const payload = safeJson(response)
  if (!payload) return null
  if (payload.verificationRequired === true) {
    // New IP trust is enabled for this test user; use pre-issued tokens for this account.
    loginFailureRate.add(1)
    return null
  }
  loginFailureRate.add(0)
  return typeof payload.access_token === 'string' ? payload.access_token : null
}

export function setup() {
  if (CREDENTIAL_POOL.length === 0) {
    throw new Error(`No user records loaded from USER_DATA_FILE=${USER_SOURCE_FILE} or USER_DATA`)
  }

  const tokens = []
  for (const row of CREDENTIAL_POOL) {
    let token = null

    if (AUTH_MODE === 'login') {
      if (!row.identifier || !row.password) continue
      token = loginAndGetToken(row.identifier, row.password)
    } else if (row.accessToken) {
      token = row.accessToken
    }

    if (token) {
      tokens.push({
        identifier: row.identifier,
        token: token.startsWith('Bearer ') ? token : `Bearer ${token}`,
      })
    }
  }

  if (tokens.length === 0) {
    throw new Error('No usable auth tokens from USER_DATA source. Ensure there are tokens or valid credentials.')
  }

  const maximumVus = Math.max(...STAGE_PLAN.map((stage) => stage.target))
  if (!ALLOW_TOKEN_REUSE && tokens.length < maximumVus) {
    throw new Error(`The full stage plan requires ${maximumVus} unique tokens, but only ${tokens.length} are available. Add tokens or set ALLOW_TOKEN_REUSE=true for a non-isolated exploratory run.`)
  }

  return { tokens }
}

function headersFromToken(token) {
  return { headers: { Authorization: token, 'Content-Type': 'application/json' } }
}

function readWalletFlow(user) {
  const response = http.get(
    `${BASE_URL}/platform/user/wallet?page=${WALLET_PAGE}&pageSize=${WALLET_PAGE_SIZE}`,
    headersFromToken(user.token),
  )
  trackFailure(response)
  check(response, {
    'GET /platform/user/wallet is 2xx': (res) => is2xx(res.status),
    'wallet payload includes transactionTotal': (res) => {
      const payload = safeJson(res)
      return payload && typeof payload.transactionTotal === 'number'
    },
    'wallet payload includes page metadata': (res) => {
      const payload = safeJson(res)
      return payload
        && Number(payload.transactionPage) > 0
        && Number(payload.transactionPageSize) > 0
    },
  })
}

function createRechargeOrder(user) {
  const response = http.post(
    `${BASE_URL}/platform/user/recharge-orders`,
    JSON.stringify({
      customAmount: Number(DEFAULT_CUSTOM_AMOUNT.toFixed(2)),
      paymentMethod: PAYMENT_METHOD,
    }),
    headersFromToken(user.token),
  )
  trackFailure(response)
  check(response, {
    'POST /platform/user/recharge-orders is 2xx': (res) => is2xx(res.status),
    'recharge order returns order id': (res) => {
      const payload = safeJson(res)
      return payload && (payload.id !== undefined || payload.orderNo !== undefined)
    },
  })
}

function executeMixedScenario(user) {
  if (Math.random() < TARGET_WRITE_RATIO) {
    createRechargeOrder(user)
  } else {
    readWalletFlow(user)
  }
}

function executeReadScenario(user) {
  readWalletFlow(user)
}

function executeWriteScenario(user) {
  createRechargeOrder(user)
}

export const options = {
  thresholds: buildThresholds(),
  scenarios: buildStageScenarios(),
}

export function mixedScenario(data) {
  const users = (data && data.tokens) || []
  if (!users.length) return
  const user = users[(__VU - 1) % users.length]
  executeMixedScenario(user)
  sleep(0)
}

export function readOnlyScenario(data) {
  const users = (data && data.tokens) || []
  if (!users.length) return
  const user = users[(__VU - 1) % users.length]
  executeReadScenario(user)
  sleep(0)
}

export function writeOnlyScenario(data) {
  const users = (data && data.tokens) || []
  if (!users.length) return
  const user = users[(__VU - 1) % users.length]
  executeWriteScenario(user)
  sleep(0)
}

export function handleSummary(data) {
  const summary = JSON.stringify(data, null, 2)
  const lines = ['\nPer-stage capacity summary:']
  for (const stage of STAGE_PLAN) {
    const tag = `{load_stage:${stage.target}}`
    const failed = data.metrics[`http_req_failed${tag}`]?.values?.rate
    const duration = data.metrics[`http_req_duration${tag}`]?.values || {}
    const requests = data.metrics[`http_reqs${tag}`]?.values || {}
    lines.push(
      `${stage.target} VUs: qps=${requests.rate === undefined ? 'n/a' : requests.rate.toFixed(1)}, `
      + `failed=${failed === undefined ? 'n/a' : (failed * 100).toFixed(2) + '%'}, `
      + `p95=${duration['p(95)'] === undefined ? 'n/a' : duration['p(95)'].toFixed(1) + 'ms'}, `
      + `p99=${duration['p(99)'] === undefined ? 'n/a' : duration['p(99)'].toFixed(1) + 'ms'}`,
    )
  }
  const outputs = { stdout: `${lines.join('\n')}\n` }
  if (SUMMARY_PATH) outputs[SUMMARY_PATH] = summary
  return outputs
}
