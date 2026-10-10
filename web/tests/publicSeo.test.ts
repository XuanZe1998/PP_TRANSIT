import { describe, expect, it } from 'vitest'
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { buildPublicPages, renderPage } from '../scripts/build-public-pages.mjs'
import { publicPages, renderHead, escapeHtml } from '../src/seo/pages.mjs'

const template = '<html><head><title>Linknux</title><meta name="description" content="old"></head><body><div id="app"></div><script type="module" src="/assets/app.js"></script></body></html>'

describe('public SEO build', () => {
  it('emits distinct readable pages and preserves the application assets', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'linknux-seo-'))
    try {
      await writeFile(join(directory, 'index.html'), template)
      await buildPublicPages(directory)
      const titles = new Set()
      for (const page of publicPages) {
        const html = await readFile(join(directory, page.path === '/' ? 'index.html' : page.path.slice(1) + '/index.html'), 'utf8')
        expect(html).toContain(`<h1>${page.zh.heading}</h1>`)
        expect(html).toContain(page.zh.summary)
        expect(html).toContain(`href="https://linknux.com${page.path}"`)
        expect(html.match(/rel="canonical"/g)).toHaveLength(1)
        expect(html.match(/name="description"/g)).toHaveLength(1)
        expect(html).toContain('/assets/app.js')
        titles.add(html.match(/<title>(.*?)<\/title>/)?.[1])
      }
      expect(titles.size).toBe(3)
      const shell = await readFile(join(directory, 'app.html'), 'utf8')
      expect(shell).not.toContain('<h1>')
      expect(shell).not.toContain('rel="canonical"')
      const sitemap = await readFile(join(directory, 'sitemap.xml'), 'utf8')
      expect(sitemap.match(/<loc>/g)).toHaveLength(3)
      expect(sitemap).not.toMatch(/lastmod|console|admin/)
    } finally {
      await rm(directory, { recursive: true, force: true })
    }
  })

  it('fails rather than silently emitting an empty body when Vite changes its template', () => {
    expect(() => renderPage('<html><div id="different"></div></html>', publicPages[0])).toThrow()
  })

  it('escapes copy before embedding it in HTML and has explicit English metadata', () => {
    expect(escapeHtml('<script>"&')).toBe('&lt;script&gt;&quot;&amp;')
    expect(renderHead(publicPages[1], true)).toContain(publicPages[1].en.title)
    expect(renderHead(publicPages[1], true)).toContain('https://linknux.com/docs')
  })
})
