import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('subscription service integration', () => {
  it('keeps the backend allowlist and frontend workbench in sync', () => {
    const backend = readFileSync(resolve('..', 'src/main/java/com/transit/service/SubscriptionServiceOperation.java'), 'utf8')
    const frontend = readFileSync(resolve('src/config/subscriptionServiceOperations.ts'), 'utf8')
    const backendKeys = [...backend.matchAll(/^\s+[A-Z0-9_]+\("([a-z0-9_]+)"/gmu)].map(match => match[1]).sort()
    const frontendKeys = [...frontend.matchAll(/\{ key: '([a-z0-9_]+)'/gu)].map(match => match[1]).sort()

    expect(backendKeys).toHaveLength(43)
    expect(frontendKeys).toEqual(backendKeys)
  })

  it('uses shared pagination for both public and admin business lists', () => {
    const publicView = readFileSync(resolve('src/views/SubscriptionServices.vue'), 'utf8')
    const adminView = readFileSync(resolve('src/views/AdminSubscriptionServices.vue'), 'utf8')
    expect(publicView).toContain('<ListPagination')
    expect(adminView).toContain('<PagedTable')
    expect(adminView).toContain('<ListPagination')
  })

  it('keeps rich upstream descriptions out of the card layout', () => {
    const publicView = readFileSync(resolve('src/views/SubscriptionServices.vue'), 'utf8')
    expect(publicView).toContain('subscriptionPlainText')
    expect(publicView).toContain('-webkit-line-clamp: 3')
    expect(publicView).toContain('margin-top: auto')
    expect(publicView).toContain('position: absolute; inset: 0')
  })
})
