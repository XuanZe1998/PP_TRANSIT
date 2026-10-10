import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { publicPages } from '../src/seo/pages.mjs'

for (const page of publicPages) {
  const filename = page.path === '/' ? 'dist/index.html' : `dist${page.path}/index.html`
  const html = await readFile(filename, 'utf8')
  assert(html.includes(`<h1>${page.zh.heading}</h1>`), filename)
  assert(html.includes(`href="https://linknux.com${page.path}"`), filename)
  assert.equal((html.match(/rel="canonical"/g) || []).length, 1)
  assert.equal((html.match(/name="description"/g) || []).length, 1)
  assert(/src="\/assets\/[^"\s]+\.js"/.test(html), 'Production asset missing')
}
assert((await readFile('dist/robots.txt', 'utf8')).includes('Sitemap: https://linknux.com/sitemap.xml'))
assert.equal((await readFile('dist/sitemap.xml', 'utf8')).match(/<loc>/g).length, 3)
assert(!(await readFile('dist/app.html', 'utf8')).includes('rel="canonical"'))
console.log('Public production HTML, assets, robots, sitemap and neutral SPA entry verified')
