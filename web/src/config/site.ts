import { reactive, readonly } from 'vue'
import http from '@/utils/http'

export type SiteConfig = { name: string; description: string; logoUrl: string; faviconUrl: string }

export const SITE_FALLBACK: Readonly<SiteConfig> = Object.freeze({
  name: 'Linknux',
  description: '面向开发者与团队的一站式 AI 能力平台，统一连接主流模型，提供智能路由、用量治理、创作工具与企业协作。',
  logoUrl: '/brand/linknux-mark-192.png',
  faviconUrl: '/favicon.png'
})

const state = reactive<SiteConfig>({ ...SITE_FALLBACK })

export const siteConfig = readonly(state)

export async function loadSiteConfig() {
  try {
    const { data } = await http.get('/api/public/site-config', { timeout: 5000 })
    state.name = String(data?.name || SITE_FALLBACK.name)
    state.description = String(data?.description || SITE_FALLBACK.description)
    state.logoUrl = String(data?.logoUrl || SITE_FALLBACK.logoUrl)
    state.faviconUrl = String(data?.faviconUrl || SITE_FALLBACK.faviconUrl)
  } catch {
    Object.assign(state, SITE_FALLBACK)
  }
  document.title = `${document.title.split(' - ')[0] || '首页'} - ${state.name}`
  const icon = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
  if (icon) icon.href = state.faviconUrl
}
