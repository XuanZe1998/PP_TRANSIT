import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'

describe('bilingual product contract', () => {
  it('keeps the approved English campaign copy and an authored Chinese counterpart', () => {
    const source = readFileSync('src/views/HomePage.vue', 'utf8')
    expect(source).toContain('One API. Every AI Model.')
    expect(source).toContain('Access GPT, Claude, Gemini, DeepSeek, Qwen, GLM, Kimi and more through one OpenAI-compatible API.')
    expect(source).toContain('One API key. One endpoint. One bill.')
    expect(source).toContain('一个接口，万般智能。')
    expect(source).toContain('一把密钥，一个端点，一张清晰账单。')
  })

  it('installs one global language switch and sends locale/currency to the backend', () => {
    expect(readFileSync('src/App.vue', 'utf8')).toContain('<LocaleSwitch />')
    const http = readFileSync('src/utils/http.ts', 'utf8')
    expect(http).toContain("scoped.headers['Accept-Language']")
    expect(http).toContain("scoped.headers['X-Display-Currency']")
  })
})
