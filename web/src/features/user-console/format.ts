import { ElMessage } from 'element-plus';
import { formatCny, type AmountUnits } from '@/utils/money';

export const amountValue = (value: unknown): AmountUnits => {
  if (typeof value === 'bigint' || typeof value === 'number' || typeof value === 'string') return value
  return 0
}
export const money = (value: unknown) => formatCny(amountValue(value))
export const number = (value: unknown) => Number(value || 0).toLocaleString('zh-CN')
export const compactNumber = (value: unknown) => Intl.NumberFormat('zh-CN', {
  notation: 'compact',
  maximumFractionDigits: 1
}).format(Number(value || 0))
export const maskKey = (value: string) => {
  if (!value) return ''
  if (value.length <= 12) return `${value.slice(0, 4)}••••`
  return `${value.slice(0, 8)}••••${value.slice(-4)}`
}
export const keyPreview = (token: Record<string, any>) => {
  const preview = String(token?.keyPreview || '')
  if (preview) return preview
  const legacyValue = String(token?.key || '')
  return legacyValue ? maskKey(legacyValue) : 'sk-at-••••'
}
export const formatDateTime = (value: string) => {
  if (!value) return '从未调用'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

export const copyText = async (value: string, message = '已复制') => {
  if (!value) return
  try {
    await navigator.clipboard.writeText(value)
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = value
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
  }
  ElMessage.success(message)
}
