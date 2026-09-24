import { describe, expect, it } from 'vitest'
import { subscriptionPlainText } from '../src/utils/subscriptionText'

describe('subscriptionPlainText', () => {
  it('turns upstream HTML into readable plain text', () => {
    expect(subscriptionPlainText('<h2>全天自助</h2><p>24小时&nbsp;自动充值<br>无需密码</p>'))
      .toBe('全天自助\n24小时 自动充值\n无需密码')
  })

  it('drops executable content and decodes numeric entities', () => {
    expect(subscriptionPlainText('<script>alert(1)</script><p>&#x2705; 安全</p>'))
      .toBe('✅ 安全')
  })

  it('uses the fallback for empty rich text', () => {
    expect(subscriptionPlainText('<p> </p>', '暂无说明')).toBe('暂无说明')
  })
})
