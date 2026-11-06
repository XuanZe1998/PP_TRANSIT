import { formatCny } from '@/utils/money'

export function money(value: unknown) {
  return formatCny(typeof value === 'string' || typeof value === 'number' || typeof value === 'bigint' ? value : 0)
}

export function percent(value: unknown) {
  return `${Number(value || 0).toFixed(1)}%`
}
