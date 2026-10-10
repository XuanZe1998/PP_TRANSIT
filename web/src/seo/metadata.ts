import { SITE_ORIGIN, getPublicPage } from './pages.mjs'

export function updatePageMetadata(path: string, english: boolean, fallbackTitle: string) {
  const page = getPublicPage(path)
  const copy = page && (english ? page.en : page.zh)
  document.title = copy?.title || fallbackTitle
  const setMeta = (attribute: 'name' | 'property', key: string, content: string) => {
    let meta = document.head.querySelector<HTMLMetaElement>(`meta[${attribute}="${key}"]`)
    if (!meta) {
      meta = document.createElement('meta')
      meta.setAttribute(attribute, key)
      document.head.append(meta)
    }
    meta.content = content
  }
  document.head.querySelectorAll('link[rel="canonical"]').forEach(node => node.remove())
  if (page && copy) {
    const canonical = SITE_ORIGIN + page.path
    const link = document.createElement('link')
    link.rel = 'canonical'
    link.href = canonical
    document.head.append(link)
    setMeta('name', 'description', copy.description)
    setMeta('name', 'robots', 'index,follow')
    setMeta('property', 'og:type', 'website')
    setMeta('property', 'og:title', copy.title)
    setMeta('property', 'og:description', copy.description)
    setMeta('property', 'og:url', canonical)
    setMeta('name', 'twitter:card', 'summary')
  } else {
    // Do not leave homepage canonical or discovery metadata on private/other SPA routes.
    document.head.querySelector('meta[name="description"]')?.remove()
    document.head.querySelectorAll('meta[property^="og:"], meta[name^="twitter:"]').forEach(node => node.remove())
    const privatePath = /^\/(?:admin|console|oauth|payment)(?:\/|$)/.test(path) || /^\/services\/[^/]+\/redeem$/.test(path)
    setMeta('name', 'robots', privatePath ? 'noindex,follow' : 'index,follow')
  }
}
