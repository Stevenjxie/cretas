# OTA operator scripts

These scripts drive the self-hosted Expo OTA pipeline from a developer
laptop into server 47 and the immutable download CDN. The native app embeds
the public signing certificate; the matching private key stays outside Git
and is loaded only by the OTA server.

## Prerequisites

1. **SSH key authentication** to `root@47.100.235.168` (already used by
   `scripts/deploy/deploy-smartbi-python.sh`).
2. **`OTA_ADMIN_TOKEN`** env var in your local shell, sourced from a
   never-committed file:
   ```bash
   # ~/.ota-env (chmod 600)
   export OTA_ADMIN_TOKEN=<hex64 from /www/wwwroot/cretas/.env.ota on server 47>
   ```
   ```bash
   # ~/.bashrc append
   [ -f ~/.ota-env ] && source ~/.ota-env
   ```
   The token is shared out-of-band and does not live in this repo.
3. **`jq`** for `app.json` parsing.
4. **Node/npx** with Expo available from
   `frontend/CretasFoodTrace/package.json`.
5. **`ossutil64`** configured for the download bucket. Override the defaults
   when necessary:
   ```bash
   export OTA_OSS_BUCKET=cretas-download
   export OTA_OSS_CONFIG=~/.ossutilconfig-apk
   ```
6. Server environment values:
   ```bash
   OTA_ASSET_BASE_URL=https://dl.cretaceousfuture.com/app-updates/updates
   OTA_ASSET_STORE_BASE_URL=https://dl.cretaceousfuture.com/app-updates/assets-store
   ```

## `push-bundle.sh` — ship a new OTA bundle

```bash
./scripts/ota/push-bundle.sh [channel] [platform]
```

Defaults: `channel=production`, `platform=android`.

Pipeline (6 steps, about 30–90 seconds depending on bundle size and network):

1. `npx expo export --platform <p> --clear` writes a clean local export.
2. `npx expo config --json` writes the exact runtime configuration alongside it.
3. `tar -czf` + `scp`, then extract into a hidden server staging directory.
4. Upload immutable assets to OSS before the update is made visible:
   - Hermes `.hbc` is gzip-compressed and uploaded with
     `Content-Encoding: gzip`.
   - content-addressed Expo assets are uploaded once into the shared asset
     store with immutable cache headers.
5. Verify the CDN launch object exists, then atomically rename the server
   staging directory to its final timestamp.
6. `POST /api/ota/admin/register` with the Bearer token.

The ordering is intentional: a client can never receive a manifest whose CDN
launch asset has not finished uploading.

Exit codes:

- `0`: success
- `2`: bad arguments or missing environment
- `3`: Expo export produced incomplete output
- `4`: server registration returned non-200

## `rollback.sh` — revert customers to embedded bundle

```bash
./scripts/ota/rollback.sh <runtimeVersion> <channel> <timestamp>
```

Touches a `rollback` marker file inside the target bundle directory. On the
next device poll, the server emits a `rollBackToEmbedded` directive and the
device reverts to the JS bundle baked into the APK.

## `prune-bundles.sh` — bound disk usage

```bash
./scripts/ota/prune-bundles.sh <runtimeVersion> <channel> [N=10]
```

Keeps the newest `N` bundles per `(runtimeVersion, channel)` and deletes the
rest. `N=0` is forced to `1` because the latest bundle must always be retained.

## Local test coverage

The Python-side tests in
[`backend/python/ota/tests/test_scripts.py`](../../backend/python/ota/tests/test_scripts.py)
cover:

- `bash -n` syntax checks for operator scripts and the CDN helper
- regex consistency between bash `SAFE_COMPONENT` and server validation
- invalid channel, platform, retention and path traversal arguments

They do not exercise live SSH, SCP, curl or OSS operations.

```bash
cd backend/python
python -m pytest ota/tests/test_scripts.py -v
```

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `OTA_ADMIN_TOKEN env var required` | Source the gitignored operator environment file |
| runtime version fails validation | Use a clean alphanumeric version from `app.json` |
| `/admin/register` returns 401 | The local admin token is stale |
| `/admin/register` returns 404 | The bundle was not atomically promoted on server 47 |
| `expected output dist/metadata.json missing` | Expo export did not complete |
| launch asset missing from OSS | Check the OSS profile, bucket and upload gate |
| SSH permission denied | Verify key authentication to server 47 |
