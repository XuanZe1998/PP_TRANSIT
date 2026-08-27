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
