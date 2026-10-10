export type PublicCopy = { title: string; description: string; heading: string; summary: string; detail: string }
export type PublicPage = { path: string; zh: PublicCopy; en: PublicCopy }
export const SITE_ORIGIN: string
export const publicPages: PublicPage[]
export function getPublicPage(path: string): PublicPage | undefined
export function escapeHtml(text: string): string
export function renderHead(page: PublicPage, english?: boolean): string
export function renderStaticBody(page: PublicPage): string
