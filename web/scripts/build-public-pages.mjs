import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { publicPages, renderHead, renderStaticBody, SITE_ORIGIN } from '../src/seo/pages.mjs'

export function renderPage(template, page) {
  if (!/<title>[\s\S]*?<\/title>/.test(template) || !template.includes('<div id="app"></div>')) {
    throw new Error('Unexpected Vite template; refusing to emit empty public pages')
  }
  return template
    .replace(/<meta name="description"[^>]*>/g, '')
    .replace(/<title>[\s\S]*?<\/title>/, renderHead(page))
    .replace('<div id="app"></div>', `<div id="app">${renderStaticBody(page)}</div>`)
}

export async function buildPublicPages(directory) {
  const template = await readFile(resolve(directory, 'index.html'), 'utf8')
  // The neutral SPA entry prevents private/other routes from returning homepage SEO copy.
  const shell = template.replace(/<meta name="description"[^>]*>/g, '')
  await writeFile(resolve(directory, 'app.html'), shell)
  for (const page of publicPages) {
    const folder = page.path === '/' ? directory : resolve(directory, page.path.slice(1))
    await mkdir(folder, { recursive: true })
    await writeFile(resolve(folder, 'index.html'), renderPage(template, page))
  }
  await writeFile(resolve(directory, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${publicPages.map(page => `  <url><loc>${SITE_ORIGIN}${page.path}</loc></url>`).join('\n')}\n</urlset>\n`)
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(import.meta.filename)) {
  await buildPublicPages(resolve('dist'))
  console.log('Generated 3 public HTML pages, neutral SPA entry and sitemap.xml')
}
