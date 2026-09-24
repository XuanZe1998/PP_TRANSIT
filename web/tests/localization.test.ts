import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { toEnglishCopy } from '../src/i18n/locale'

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

  it('keeps every generated English translation free of Chinese characters', () => {
    const translations = JSON.parse(readFileSync('src/i18n/englishCopy.cache.json', 'utf8')) as Record<string, string>
    expect(Object.keys(translations).length).toBeGreaterThan(3000)
    expect(Object.entries(translations).filter(([, target]) => /[\u3400-\u9fff\uf900-\ufaff]/u.test(target))).toEqual([])
  })

  it('translates dynamic and compound legacy copy', () => {
    expect(toEnglishCopy('查看 12 个模型')).toBe('View 12 models')
    expect(toEnglishCopy('✦ 帮我写得更好')).toBe('✦ Help me write better')
    expect(toEnglishCopy('5 秒')).toBe('5 seconds')
    expect(toEnglishCopy('服务目录加载失败')).toBe('Service catalog failed to load')
    expect(toEnglishCopy('Claude Code Max 20x组')).toBe('Claude Code Max 20x Plan')
    expect(toEnglishCopy('服务按实际用量计费，第三方模型提供方可能按请求处理数据。')).toBe('Services are billed by actual usage. Third-party model providers may process request data.')
  })
})
