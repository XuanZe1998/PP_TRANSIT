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
