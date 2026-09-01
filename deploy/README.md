# Production deployment

Production releases are built by GitHub Actions after `master` passes the backend and frontend jobs. The same release protocol is available locally through `deploy-production.ps1`.

## Safety properties

- Only committed `master` revisions can be deployed locally.
- Private Spring configuration is read from `/etc/api-transit/application-local.yaml` and is never packaged.
- The server validates release identifiers, owners, archive paths, artifact size, and SHA-256 checksums.
- A transactional MySQL dump is taken before activation.
- Backend and frontend symlinks switch atomically and are restored if health checks fail.
- Five application releases and five pre-deployment database backups are retained.

## Local deployment

The local machine must have the production deploy key at `~/.ssh/api-transit-deploy` and the server host key in `~/.ssh/known_hosts`.

```powershell
.\deploy\deploy-production.ps1
```

Application secrets, SSH passwords, and database contents are intentionally outside this workflow.

## OAuth production checklist

OAuth providers compare callback addresses character by character. Before a production release, confirm all of the following without a trailing slash:

- GitHub OAuth App **Authorization callback URL**: `https://linknux.com/oauth/callback/github`
- Google OAuth client **Authorized redirect URI**: `https://linknux.com/oauth/callback/google`
- Google OAuth client **Authorized JavaScript origin**: `https://linknux.com`
- Production `GITHUB_REDIRECT_URI` and `GOOGLE_REDIRECT_URI` exactly match the two callback values above.

After startup, check the non-secret `OAuth callback configuration` log entry, then open each login button once and inspect the provider authorization URL's `redirect_uri`. A `redirect_uri_mismatch` response means the provider console and the deployed environment still differ; changing application code alone cannot override that provider-side allowlist.

## Compression verification

The production Nginx server block enables gzip for JavaScript, CSS, JSON, and SVG, while hashed files under `/assets/` retain a one-year immutable cache. After reloading the effective HTTPS configuration, verify it with:

```bash
curl -sI -H 'Accept-Encoding: gzip' https://linknux.com/assets/<current-entry-file>.js
```

The response must include `Content-Encoding: gzip`, `Vary: Accept-Encoding`, and the immutable cache header.

## Post-deployment smoke and release validation

After a production release, run these quick checks:

```bash
curl -sS -I https://linknux.com/
curl -sS -H "Authorization: Bearer <admin-or-user-token>" \
  "https://api.linknux.com/platform/user/wallet?page=1&pageSize=10"
curl -sS -H "Authorization: Bearer <admin-or-user-token>" \
  https://api.linknux.com/platform/user/recharge-orders
```

For wallet/recharge-specific quality checks, use:

- `POST /platform/user/recharge-orders` with `customAmount > 0` (expect success).
- `customAmount <= 0` (expect 400).
- `needInvoice=true` denied when user invoice access is disabled.
- `needInvoice=true` works after admin sets user `invoiceEnabled=true`.
- wallet pagination query `page=1,pageSize=10/20/50/100` and unsupported `pageSize` fallback.

## Load test execution

Use the load test package in:

- `scripts/k6/wallet-load.js`
- `scripts/k6/run-wallet-load.ps1`
- `scripts/k6/user-token-pool.sample.csv`

See [线上压测与上线核验方案](../docs/线上压测与上线核验方案.md) for the full concurrency methodology and interpretation.
