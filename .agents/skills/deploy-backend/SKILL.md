---
name: deploy-backend
description: >
  Use when the user asks to deploy, publish, release, restart, roll back, or
  verify Cretas Java, Python, or web-admin services in test or production.
---

# Cretas Deployment

Deploy only an exact, reviewed commit. Read `.codex/rules/server-operations.md`, `.codex/rules/worktree-and-main-only-deploy.md`, and `.codex/rules/concurrent-edit-safety.md` before changing deployment scripts or production state.

## Scope And Environment

| Component | Production truth | Test |
|---|---|---|
| Java | `47.100.235.168`, alternating `10010` / `10020` | `10011` |
| Python | `47.100.235.168:8083` | `8084` |
| Web admin | `139.196.165.140:8086`, `admin.cretaceousfuture.com` | n/a |

Java runtime is `/www/wwwroot/cretas/`; web admin is `/www/wwwroot/web-admin/`. Production Java traffic is routed by `139.196.165.140:/www/server/panel/vhost/nginx/_upstream_cretas.conf`.

## Build Once

For one commit, perform one final full release build:

- If the deploy script will run `clean package` or `npm run build`, do not run the same full build immediately beforehand. Use target tests and static checks during implementation.
- `SKIP_BUILD=1` is allowed only when the existing artifact has a manifest tying it to the exact deployed commit and its hash has been verified. A recent mtime or filename is not provenance.
- Until CI artifacts carry that manifest, let the deployment script perform the single trusted release build.

## Java Blue-Green Deploy

1. Require a clean release worktree whose `HEAD` equals `origin/main`.
2. Read the upstream file; never assume `10010` or `10020` is permanently active.
3. Run the project script:

```bash
./scripts/deploy/deploy-backend.sh --env test
./scripts/deploy/deploy-backend.sh --env prod
```

4. The script must deploy the inactive slot, verify it before switching, atomically update upstream, pass all post-switch health rounds, then stop the old slot.
5. Report commit, release version, artifact MD5, old/new slot, health rounds, and rollback artifact.

Useful modes:

```bash
./scripts/deploy/deploy-backend.sh --env all
./scripts/deploy/deploy-backend.sh --rollback
```

Do not use `--git` unless the user explicitly requests the legacy server-build path.

## Web Admin Deploy

Use the atomic project script, not manual `rsync --delete`:

```bash
./scripts/deploy/deploy-web-admin.sh --env prod
```

After deployment, compare local `dist/index.html`, server file, localhost response, and public response hashes. All must reference the same release.

## Python And Restart

```bash
./scripts/deploy/deploy-smartbi-python.sh --env test
./scripts/deploy/deploy-smartbi-python.sh --env prod
ssh root@47.100.235.168 "cd /www/wwwroot/cretas && bash restart.sh prod"
```

Never overwrite server `.env` files.

## Mandatory Verification

- Re-read the upstream file and confirm its active port.
- Confirm the new systemd unit is active and only the active production port listens.
- Verify direct active-slot health and the nginx/public route appropriate to the service.
- Verify Web HTTP 200 and content hashes when Web changed.
- After the final backend + Web combination is live, use `e2e-web-admin` once for the required production read-only E2E. Do not count slot smoke checks as the full E2E.
- If production assertions fail, roll back first; do not debug for an extended period on the active bad release.

Production and test may drift. Mention the untouched environment's observed status without expanding a scoped production deploy into an unrequested test deployment.
