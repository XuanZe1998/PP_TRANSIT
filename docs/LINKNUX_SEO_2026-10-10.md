# Linknux public SEO repair — 2026-10-10

Public HTTP checks before this change returned the same 2,336-byte SPA HTML for
`/`, `/docs`, `/pricing`, `/robots.txt` and `/sitemap.xml`. The initial HTML had an
empty application root and a generic title. This does not prove an indexing
failure or a Google ranking outcome; it is a reproducible content/routing defect.

## Change

- Build three public pages with distinct titles, descriptions, canonical and
  Open Graph metadata, readable headings, introductory copy and crawlable links.
- Mount the existing Vue application over the static introductory HTML. Shared
  public copy powers the documentation/pricing intros and metadata. No account
  data, model inventory or prices are frozen into generated pages.
- Emit real text/XML robots and sitemap files and a neutral SPA entry. Update
  metadata on navigation and language changes; mark private SPA routes noindex.
- Provide exact Nginx routes and a minimal, backed-up administrator patch that
  preserves the effective TLS, API proxy and staged security-header settings.
- Correct the English copy checker so script/style bodies are not interpreted
  as rendered template text.
- Restore the existing dependency audit gate: pin patched Vue 3.5.43, update
  source-map-js to 1.2.2 and remove unused Tailwind 3 (no Tailwind configuration
  or directives exist). This removes its vulnerable development dependency
  tree without a Tailwind 4 migration. Audit returns zero vulnerabilities.

This is static introductory content, not full server-side rendering. English is
selected in the browser; this change does not introduce separate English URLs
or claim separate English indexing. Sitemap includes only the three generated
public pages and contains no artificial modification dates.

## Validation performed locally

- `npm test`: 119 tests across 24 files passed.
- `npm run i18n:check`: 3,992 UI strings covered.
- `npm run build` and `node scripts/verify-public-build.mjs`: passed.
- `npx playwright test --config playwright.seo.config.ts`: 3 browser tests
  passed against the production build served by Nginx, including JavaScript
  disabled, Vue mount, language switching, SPA navigation, text/XML MIME types,
  canonical metadata, query-preserving redirects and retained security headers.
- `python deploy/server/test_public_seo.py`: configuration patch preservation,
  idempotence and refusal of conflicting configurations tested.
- Nginx snippet syntax checked in an isolated local Nginx container. Full
  production TLS configuration still requires `nginx -t` on the server.

To repeat the browser checks locally (PowerShell, repository root, Docker and
Chrome installed), build `web` first, then:

```powershell
$repo = (Get-Location).Path
docker run --rm -d --name linknux-seo-review -p 127.0.0.1:18667:80 `
  --mount "type=bind,source=$repo/web/dist,target=/usr/share/nginx/html,readonly" `
  --mount "type=bind,source=$repo/web/tests/fixtures/seo-nginx.conf,target=/etc/nginx/nginx.conf,readonly" `
  --mount "type=bind,source=$repo/deploy/nginx,target=/review,readonly" nginx:1.29-alpine
Push-Location web
npx playwright test --config playwright.seo.config.ts
Pop-Location
docker stop linknux-seo-review
```

## Production installation

GitHub's required backend/frontend checks precede the existing master release
workflow. Application deployment alone does not install Nginx configuration.
The administrator patch must run after the new frontend artifacts exist. Until
then the old directory fallback may redirect `/docs` and `/pricing` to paths
ending in `/`; browser metadata accepts both forms and uses the canonical
extensionless URL. Other SPA routes still need the new neutral fallback.

At preparation time, local SSH connections to `207.57.122.109:22` timed out
before authentication, despite the authorized existing administrator identity.
No key/password was committed, uploaded or printed. Record actual GitHub release
and production HTTP verification results after deployment; preparation is not
proof of production success.
