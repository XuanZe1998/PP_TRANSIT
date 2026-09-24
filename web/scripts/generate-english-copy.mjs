import { promises as fs } from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { execFileSync } from 'node:child_process'

const webRoot = path.resolve(process.cwd(), 'src')
const sourceRoots = [webRoot, path.resolve(process.cwd(), '..', 'src', 'main')]
const output = path.join(webRoot, 'i18n', 'englishCopy.generated.ts')
const cacheFile = path.join(webRoot, 'i18n', 'englishCopy.cache.json')
const HAN = /[\u3400-\u9fff\uf900-\ufaff]/u

const decodeEntities = (value) => value
  .replaceAll('&#10;', '\n')
  .replaceAll('&nbsp;', ' ')
  .replaceAll('&amp;', '&')
  .replaceAll('&lt;', '<')
  .replaceAll('&gt;', '>')
  .replaceAll('&quot;', '"')
  .replaceAll('&#39;', "'")

function normalize(value) {
  return decodeEntities(value)
    .replace(/\\n/g, '\n')
    .replace(/\\t/g, '\t')
    .replace(/\s+/g, ' ')
    .trim()
}

async function listSourceFiles(directory) {
  const entries = await fs.readdir(directory, { withFileTypes: true })
  const files = []
  for (const entry of entries) {
    const absolute = path.join(directory, entry.name)
    if (entry.isDirectory()) files.push(...await listSourceFiles(absolute))
    else if (/\.(?:vue|ts|java|properties|sql|json|ya?ml)$/u.test(entry.name) && !entry.name.endsWith('.generated.ts') && !entry.name.endsWith('.cache.json')) files.push(absolute)
  }
  return files
}

function extractQuotedStrings(source, found) {
  for (const quote of ["'", '"', '`']) {
    const escaped = quote
    const quotePattern = new RegExp(`${escaped}((?:\\\\.|(?!${escaped})[\\s\\S])*?)${escaped}`, 'gu')
    for (const match of source.matchAll(quotePattern)) {
      const value = normalize(match[1])
      if (HAN.test(value)) found.add(value)
    }
  }
}

function extractTemplateText(source, found) {
  for (const match of source.matchAll(/>([^<]+)</gu)) {
    const value = normalize(match[1])
    if (HAN.test(value)) found.add(value)
  }
}

async function extractAll() {
  const found = new Set()
  for (const sourceRoot of sourceRoots) {
    for (const file of await listSourceFiles(sourceRoot)) {
      const source = await fs.readFile(file, 'utf8')
      extractQuotedStrings(source, found)
      if (file.endsWith('.vue')) extractTemplateText(source, found)
    }
  }
  return [...found]
    .filter((value) => value.length <= 1500)
    .sort((left, right) => left.localeCompare(right, 'zh-CN'))
}

async function translateBatch(batch) {
  const request = batch.map((value, index) => `[LNX${String(index).padStart(4, '0')}] ${value}`).join('\n')
  const encoded = Buffer.from(request, 'utf8').toString('base64')
  const script = [
    `$q=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${encoded}'))`,
    `$u='https://translate.googleapis.com/translate_a/single?client=gtx&sl=zh-CN&tl=en&dt=t&q='+[uri]::EscapeDataString($q)`,
    `$r=Invoke-RestMethod -Uri $u -TimeoutSec 30`,
    `($r[0] | ForEach-Object { $_[0] }) -join ''`,
  ].join(';')
  const combined = execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], {
    encoding: 'utf8', timeout: 45_000, maxBuffer: 1024 * 1024,
  })
  const translated = new Map()
  const marker = /\[LNX(\d{4})\]\s*/gu
  const matches = [...combined.matchAll(marker)]
  for (let index = 0; index < matches.length; index += 1) {
    const start = matches[index].index + matches[index][0].length
    const end = matches[index + 1]?.index ?? combined.length
    translated.set(Number(matches[index][1]), combined.slice(start, end).trim())
  }
  if (translated.size !== batch.length) throw new Error(`Expected ${batch.length} translations, received ${translated.size}`)
  return batch.map((_, index) => translated.get(index))
}

async function main() {
  const sources = await extractAll()
  let cache = {}
  try { cache = JSON.parse(await fs.readFile(cacheFile, 'utf8')) } catch { /* First generation. */ }
  const uncached = sources.filter((source) => !cache[source])
  if (process.argv.includes('--check')) {
    if (uncached.length) throw new Error(`English copy is missing ${uncached.length} entries:\n${uncached.slice(0, 20).join('\n')}`)
    process.stdout.write(`English copy covers all ${sources.length} extracted UI strings.\n`)
    return
  }
  const missing = process.argv.includes('--cached-only') ? [] : uncached
  let cursor = 0
  while (cursor < missing.length) {
    const batch = []
    let length = 0
    while (cursor < missing.length && batch.length < 30) {
      const candidate = missing[cursor]
      if (batch.length && length + candidate.length > 2800) break
      batch.push(candidate)
      length += candidate.length
      cursor += 1
    }
    let translated
    for (let attempt = 1; attempt <= 4; attempt += 1) {
      try {
        translated = await translateBatch(batch)
        break
      } catch (error) {
        if (attempt === 4) throw error
        await new Promise((resolve) => setTimeout(resolve, attempt * 1_000))
      }
    }
    batch.forEach((source, index) => { cache[source] = translated[index] })
    await fs.writeFile(cacheFile, `${JSON.stringify(cache, null, 2)}\n`)
    process.stdout.write(`Translated ${Math.min(cursor, missing.length)}/${missing.length}\r`)
  }

  const outputSources = sources.filter((source) => cache[source])
  const activeCache = Object.fromEntries(outputSources.map((source) => [source, cache[source]]))
  await fs.writeFile(cacheFile, `${JSON.stringify(activeCache, null, 2)}\n`)
  const entries = outputSources.map((source) => `  ${JSON.stringify(source)}: ${JSON.stringify(cache[source])},`).join('\n')
  const module = `// Generated by scripts/generate-english-copy.mjs. Do not edit by hand.\n` +
    `// The checked-in map keeps production localization deterministic and offline.\n` +
    `export const generatedEnglishCopy: Readonly<Record<string, string>> = {\n${entries}\n}\n`
  await fs.writeFile(output, module)
  process.stdout.write(`\nWrote ${outputSources.length} English UI translations to ${path.relative(process.cwd(), output)}\n`)
}

await main()
