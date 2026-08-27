import { describe, expect, it } from 'vitest'

import http, { createIdempotencyKey, getHttpErrorMessage, getHttpErrorNotice } from '../src/utils/http'

describe('HTTP client production defaults', () => {
  it('uses a finite default timeout', () => {
    expect(http.defaults.timeout).toBe(30_000)
  })

  it('reports timeouts and network failures distinctly', () => {
    expect(getHttpErrorMessage({ isAxiosError: true, code: 'ECONNABORTED' }))
      .toBe('请求超时，请检查网络后重试')
    expect(getHttpErrorMessage({ isAxiosError: true, code: 'ERR_NETWORK' }))
      .toBe('无法连接到服务，请检查网络或服务状态')
  })

  it('does not describe a 403 authorization failure as an expired login', () => {
    expect(getHttpErrorMessage({
      isAxiosError: true,
      response: { status: 403, data: {} }
    })).toBe('当前账号没有执行此操作的权限')
  })

  it('preserves a safe server error message when supplied', () => {
    expect(getHttpErrorMessage({
      isAxiosError: true,
      response: { status: 400, data: { message: '模型未配置' } }
    })).toBe('模型未配置')
  })

  it('translates a generic missing-resource response and preserves its request id', () => {
    const error = {
      isAxiosError: true,
      response: {
        status: 404,
        data: { requestId: 'req_test_404', error: { message: 'Resource not found' } }
      }
    }
    expect(getHttpErrorMessage(error)).toBe('当前功能接口暂不可用，请确认后端已更新并重启后再试')
    expect(getHttpErrorNotice(error)).toBe('当前功能接口暂不可用，请确认后端已更新并重启后再试（请求 ID：req_test_404）')
  })

  it('creates an idempotency key accepted by the gateway filter', () => {
    const key = createIdempotencyKey('service-order')
    expect(key).toMatch(/^[A-Za-z0-9._:-]{8,160}$/)
    expect(createIdempotencyKey('unsafe scope')).toMatch(/^unsafe-scope-/)
  })
})
