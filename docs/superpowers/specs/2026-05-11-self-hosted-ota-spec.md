# Self-Hosted OTA Server Implementation Spec

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Date:** 2026-05-11
**Chat:** chat5
**Branch:** `feat/ota-self-hosted`
**Worktree:** `.worktrees/ota-self-hosted`

**Goal:** Stand up a self-hosted Expo Updates v1 protocol-compatible server inside `backend/python/` so the existing `expo-updates@~0.28.18` client in `frontend/CretasFoodTrace` can fetch JS/asset OTA updates from `https://ota.cretaceousfuture.com/api/ota/manifest`, with zero EAS / Expo cloud dependency.

**Architecture:** Python FastAPI module exposes manifest + asset endpoints implementing the [Expo Updates v1 protocol](https://docs.expo.dev/technical-specs/expo-updates-1/) byte-for-byte. Bundles are produced by `npx expo export` locally and uploaded to `/www/wwwroot/ota/updates/<runtimeVersion>/<channel>/<timestamp>/` on server 47. RSA-2048 keypair signs every manifest/directive; the public PEM is embedded in the APK and verified client-side by `expo-updates`. Android APKs are built locally with `./gradlew assembleRelease` — no EAS Build.

**Tech Stack:** Python 3.8 + FastAPI + asyncpg (existing backend/python stack), `cryptography` lib for RSA-SHA256 signing, `python-multipart` for `multipart/mixed` response construction, RSA-PKCS#1-v1.5 + SHA-256, Android Studio + JDK 21 + Gradle for local APK builds.

---

## Context

### What's already in place (post PR #296)
- `expo-updates@~0.28.18` installed in `frontend/CretasFoodTrace/package.json`
- `app.json`:
  - `runtimeVersion.policy = "appVersion"` (current app version `1.0.0` → runtime version `"1.0.0"`)
  - `updates.url = "https://u.expo.dev/PLACEHOLDER-EAS-PROJECT-ID"` ← **stale, must replace**
  - `updates.checkAutomatically = "ON_LOAD"`, `fallbackToCacheTimeout = 5000`
  - `extra.eas.projectId = "com.cretas.foodtrace"` ← **invalid (Android pkg, not UUID); remove**
- `eas.json` has `update` profiles and `channel` config (irrelevant for self-hosted, leave alone for now)
- `android/app/src/main/AndroidManifest.xml` has `expo.modules.updates.*` placeholders (`ENABLED=false`); prebuild flips to `true`

### What rolled back (PR #356 CLOSED)
- EAS init attempt (`eas init` → real UUID) — Steve rejected EAS path, chose self-hosted

### Why self-hosted
- 0 cloud cost / lock-in
- Full control of bundle distribution + signing
- Customer base small (~tens of users), can run on existing server 47 infra

---

## 1. Storage layout

On **server 47** filesystem (owned by `cretas-python` systemd service user):

```
/www/wwwroot/ota/
├── updates/
│   └── <runtimeVersion>/         # e.g. "1.0.0"
│       └── <channel>/            # "production" | "staging" | "development"
│           └── <timestamp>/      # millis since epoch, e.g. "1715472000000"
│               ├── metadata.json     # from `expo export` (fileMetadata.android.{bundle,assets})
│               ├── expoConfig.json   # from `expo export` (app.json snapshot for the client)
│               ├── bundles/
│               │   ├── <hash>.hbc    # or .js — launch bundle for android
│               │   └── ...           # ios variant if/when added
│               ├── assets/
│               │   └── <hash>        # asset binaries (images, fonts, …)
│               └── rollback        # OPTIONAL empty marker — presence = rollback directive
└── keys/
    └── ota_private.pem           # chmod 600, owner cretas-python
```

**Selection rule (manifest endpoint):** for `(runtimeVersion, channel)` pair, take the largest `<timestamp>` directory by numeric sort. If the picked dir contains a `rollback` file, emit `rollBackToEmbedded` directive instead of a normal manifest. If no directory exists, return 404.

**Why timestamp-as-dir-name and not DB-driven:** mirrors reference impl, simpler ops, single source of truth on disk. PG table is optional indexing/audit only (Phase 1 extension if needed).

---

## 2. HTTP API

### 2.1 Manifest endpoint

```
GET /api/ota/manifest
```

**Request headers (from `expo-updates` client):**

| Header | Required | Meaning |
|---|---|---|
| `expo-protocol-version` | yes | `"1"` (we reject `"0"` and unset → 400) |
| `expo-platform` | yes | `"android"` (currently APK-only) or `"ios"` |
| `expo-runtime-version` | yes | string match against `<runtimeVersion>` dir, e.g. `"1.0.0"` |
| `expo-current-update-id` | no | UUID of the update currently loaded on device. If equals computed manifest `id` → no-update directive |
| `expo-channel-name` | no | `"production"` (default if missing) / `"staging"` / `"development"` |
| `expo-embedded-update-id` | conditional | UUID of update baked into APK at build time. Required when serving a rollback directive |
| `expo-expect-signature` | no | If present, response must include `expo-signature` part-header in the manifest/directive part |
| `accept` | recommended | `multipart/mixed` (we always respond multipart/mixed) |

**Response — Normal update (200 OK):**

```
HTTP/1.1 200 OK
expo-protocol-version: 1
expo-sfv-version: 0
cache-control: private, max-age=0
content-type: multipart/mixed; boundary=<random-boundary>

--<boundary>
content-disposition: form-data; name="manifest"
content-type: application/json; charset=utf-8
expo-signature: sig="<base64-signature>", keyid="main"

{"id":"...","createdAt":"...","runtimeVersion":"1.0.0","launchAsset":{...},"assets":[...],"metadata":{},"extra":{"expoClient":{...}}}
--<boundary>
content-disposition: form-data; name="extensions"
content-type: application/json

{"assetRequestHeaders":{}}
--<boundary>--
```

**Response — No update (200 OK):**

```
--<boundary>
content-disposition: form-data; name="directive"
content-type: application/json; charset=utf-8
expo-signature: sig="<base64>", keyid="main"

{"type":"noUpdateAvailable"}
--<boundary>--
```

**Response — Rollback (200 OK):**

```
--<boundary>
content-disposition: form-data; name="directive"
content-type: application/json; charset=utf-8
expo-signature: sig="<base64>", keyid="main"

{"type":"rollBackToEmbedded","parameters":{"commitTime":"<ISO-8601>"}}
--<boundary>--
```

**Response — Error (400/404):** JSON `{"error":"<message>"}` with appropriate status.

### 2.2 Asset endpoint

```
GET /api/ota/assets?asset=<full-relative-path>&runtimeVersion=<rv>&platform=<p>
```

- `asset` is the **path within `/www/wwwroot/ota/`** as embedded in the manifest URL (server-generated, so client can't forge — we validate it stays within the runtimeVersion+channel dir tree)
- Returns binary asset body with `content-type` either `application/javascript` (launch asset) or MIME guess from extension
- 200 with bytes, or 404 if not found / path traversal attempt

**Path traversal hardening:** resolve `asset` to absolute path, assert `os.path.commonpath([resolved, BASE]) == BASE`. Reject otherwise with 400.

### 2.3 Admin endpoints (auth via shared-secret bearer token)

```
POST /api/ota/admin/register
Authorization: Bearer <OTA_ADMIN_TOKEN>
Content-Type: application/json

{
  "runtimeVersion": "1.0.0",
  "channel": "production",
  "timestamp": "1715472000000"
}
```

Validates that `updates/1.0.0/production/1715472000000/` exists and contains `metadata.json` + `expoConfig.json`. Logs the registration to `ota_updates` PG table (optional Phase 1+) but does NOT move/copy files. The upload script (`push-bundle.sh`) is responsible for actually placing files on disk before calling this.

```
POST /api/ota/admin/rollback
Authorization: Bearer <OTA_ADMIN_TOKEN>
Content-Type: application/json

{
  "runtimeVersion": "1.0.0",
  "channel": "production",
  "timestamp": "1715472000000"
}
```

Touches an empty `rollback` file inside `updates/<rv>/<channel>/<ts>/`. Next client poll → rollback directive.

```
GET /api/ota/admin/list?runtimeVersion=1.0.0&channel=production
Authorization: Bearer <OTA_ADMIN_TOKEN>
```

Lists timestamp dirs newest-first with size, mtime, and rollback-status.

### 2.4 Health endpoint

```
GET /api/ota/health
```

Returns `{"status":"ok","privateKeyLoaded":true,"basePath":"/www/wwwroot/ota","writable":true}`. No auth required.

---

## 3. Manifest construction details (byte-exact protocol)

### 3.1 Field derivation (verbatim from reference impl)

| Field | Derivation |
|---|---|
| `id` | `sha256_hex(metadata.json bytes)`, then **sliced to UUID-shape** via `f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"` |
| `createdAt` | `metadata.json` file `st_birthtime` (or `st_mtime` fallback on Linux ext4) as ISO 8601 with `Z` suffix |
| `runtimeVersion` | echoed back from `expo-runtime-version` header |
| `launchAsset` | from `metadata.fileMetadata[platform].bundle` — see §3.2 |
| `assets[]` | from `metadata.fileMetadata[platform].assets[]` — see §3.2 |
| `metadata` | `{}` (empty object) |
| `extra.expoClient` | parsed `expoConfig.json` contents |

### 3.2 Asset shape

```python
{
  "hash": base64url(sha256(asset_bytes)),    # base64 with +→- /→_ and = stripped
  "key": md5_hex(asset_bytes),                # NOT sha256 — this is the client cache key
  "fileExtension": f".{ext}" if not is_launch else ".bundle",
  "contentType": "application/javascript" if is_launch else mime_from_ext(ext),
  "url": f"{OTA_HOSTNAME}/api/ota/assets?asset={url_encode(rel_path)}&runtimeVersion={rv}&platform={p}",
  # Phase 1-3: OTA_HOSTNAME = "http://47.100.235.168:8083" (IP direct, HTTP)
  # Phase 4+:  OTA_HOSTNAME = "https://ota.cretaceousfuture.com" (nginx 139 + Let's Encrypt)
}
```

`rel_path` for assets is `updates/<rv>/<channel>/<ts>/<from metadata>`. For launch asset, also `bundles/<hash>.hbc` (or whatever `metadata.fileMetadata.android.bundle` says).

### 3.3 No-update logic

If `expo-current-update-id` header equals `convertSHA256HashToUUID(sha256(metadata.json))` **and** `expo-protocol-version == 1`, raise `NoUpdateAvailableError` → emit `{"type":"noUpdateAvailable"}` directive part. Otherwise emit normal manifest.

### 3.4 Rollback logic

If `<timestamp>` dir contains a `rollback` file:
1. Require `expo-embedded-update-id` header (else 400)
2. If `expo-current-update-id == expo-embedded-update-id` → no-update (client already on embedded)
3. Else emit `{"type":"rollBackToEmbedded","parameters":{"commitTime":"<ISO from rollback file mtime>"}}`

---

## 4. Signing

### 4.1 Algorithm

**RSA-PKCS#1-v1.5 with SHA-256** (per `expo-code-signing` defaults, `alg="rsa-v1_5-sha256"`).

Python implementation:
```python
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

def sign_rsa_sha256(data: bytes, private_key_pem: bytes) -> str:
    key = serialization.load_pem_private_key(private_key_pem, password=None)
    signature = key.sign(data, padding.PKCS1v15(), hashes.SHA256())
    return base64.b64encode(signature).decode("ascii")
```

### 4.2 Signing target — **CRITICAL byte-exact rule**

Sign the **UTF-8 bytes of `JSON.stringify(manifest)` equivalent**. In Python:

```python
manifest_str = json.dumps(manifest, separators=(",", ":"), ensure_ascii=False)
sig_b64 = sign_rsa_sha256(manifest_str.encode("utf-8"), private_key_pem)
```

Why `separators=(",", ":")` matters: JavaScript `JSON.stringify(obj)` emits **no whitespace** between keys/values. Python `json.dumps(obj)` default emits `", "` and `": "` (with spaces). If we use default separators, the bytes differ → client signature verification fails. This is the OTA equivalent of [Rule 8 in python-java-port.md](../../.claude/rules/python-java-port.md#-rule-8-mapofn-序列化-key-order-不可凭直觉必须录-golden-反推).

**`ensure_ascii=False`** matches JS default which emits raw unicode (does NOT escape to `\uXXXX`).

**Key order:** Python `dict` preserves insertion order; we construct the manifest dict with keys in the same order as the reference TypeScript implementation: `{id, createdAt, runtimeVersion, assets, launchAsset, metadata, extra}`.

⚠ **Recording a Java-golden-equivalent**: stand up the reference TypeScript server locally for one bundle and compare its `JSON.stringify(manifest)` byte-by-byte against our Python `json.dumps(manifest, separators=(",",":"))`. Diff → fix Python until identical. Add this as a regression test fixture.

### 4.3 Signature header format

Per Expo SFV (Structured Field Values) dictionary, serialize as:
```
expo-signature: sig="<base64-sig>", keyid="main"
```

Use `http-sfv` Python library OR hand-roll the dictionary serialization (simple enough — the reference uses `structured-headers` NPM pkg).

### 4.4 Key locations

| Key | Path | Permissions | Embedded |
|---|---|---|---|
| Private (signing) | `/www/wwwroot/ota/keys/ota_private.pem` on server 47 | `chmod 600`, owner `cretas-python` | NO — server-only |
| Public RSA (intermediate, server local only) | `/www/wwwroot/ota/keys/ota_public.pem` | n/a | NO — used only to derive the X.509 cert below |
| X.509 cert (verifying, what client actually checks) | `frontend/CretasFoodTrace/ota_public_cert.pem` (committed) | n/a | YES — bundled in APK, referenced via `app.json:updates.codeSigningCertificate` (per §10) |

### 4.5 Generation procedure (Phase 2)

```bash
mkdir -p /www/wwwroot/ota/keys && cd /www/wwwroot/ota/keys
openssl genrsa -out ota_private.pem 2048
openssl rsa -in ota_private.pem -pubout -out ota_public.pem
chmod 600 ota_private.pem
chown cretas-python:cretas-python ota_private.pem

# Copy public to frontend
scp root@47.100.235.168:/www/wwwroot/ota/keys/ota_public.pem \
    frontend/CretasFoodTrace/ota_public.pem
```

**X.509 wrapper for `codeSigningCertificate`:** `expo-updates` actually expects a self-signed X.509 cert, not raw RSA public key. Use:

```bash
openssl req -new -x509 -key ota_private.pem -out ota_cert.pem -days 1825 \
  -subj "/CN=Cretas OTA Self-Signed/O=Cretas Food Trace/C=CN"
```

Commit `ota_cert.pem` into `frontend/CretasFoodTrace/`, set
`"codeSigningCertificate": "./ota_cert.pem"` in `app.json` (Phase 5).

⚠ **5-year validity (1825 days):** rotation calendar reminder required before
expiry; rotation forces a new APK build (cert is baked in).

### Active cert (Phase 2 ship, 2026-05-11)

| Field | Value |
|---|---|
| File on server 47 | `/www/wwwroot/cretas/ota/ota_cert.pem` (root:root) |
| File in repo | `frontend/CretasFoodTrace/ota_cert.pem` (gets bundled into APK) |
| SHA-256 fingerprint | `2C:4E:BF:E9:92:16:39:89:F3:92:BE:3E:22:73:E3:77:FF:B9:54:74:69:BC:05:2E:66:A3:E7:21:17:1B:5F:B3` |
| Subject | `CN=Cretas OTA Self-Signed, O=Cretas Food Trace, C=CN` |
| Valid from | 2026-05-11 20:01:23 UTC |
| Valid until | **2031-05-10 20:01:23 UTC** (rotate calendar reminder) |
| Private key location | `/www/wwwroot/cretas/ota/ota_private.pem` chmod 600 root:root, NEVER leaves server 47 |

---

## 5. Python module structure

```
backend/python/ota/
├── __init__.py
├── api/
│   ├── __init__.py
│   ├── endpoints.py        # FastAPI router: /manifest, /assets, /admin/*, /health
│   └── admin_auth.py        # Bearer-token dependency for admin endpoints
├── services/
│   ├── __init__.py
│   ├── storage.py          # filesystem ops: list timestamps, read metadata, asset path resolve
│   ├── manifest_builder.py # builds the manifest dict from metadata.json + expoConfig.json
│   ├── signing.py          # load_private_key, sign_rsa_sha256, build_signature_header
│   ├── multipart.py        # assemble multipart/mixed body with per-part headers
│   └── directives.py       # createNoUpdateAvailable, createRollBackToEmbedded
├── models.py               # Pydantic models + (optional Phase 1+) PG ORM for ota_updates audit
├── config.py               # OTA_BASE_PATH, OTA_PRIVATE_KEY_PATH, OTA_ADMIN_TOKEN, OTA_HOSTNAME
└── tests/                  # See §6 for full test list
    ├── __init__.py
    ├── conftest.py
    ├── test_manifest_endpoint.py
    ├── test_assets_endpoint.py
    ├── test_signing.py
    ├── test_multipart.py
    ├── test_storage.py
    ├── test_admin_endpoints.py
    └── fixtures/
        ├── sample_metadata.json
        ├── sample_expoConfig.json
        ├── test_private_key.pem
        ├── test_public_cert.pem
        └── reference_manifest.json   # byte-exact reference from TypeScript impl for parity test
```

**Registration in `backend/python/main.py`** (coordinate with chat1 — they're editing main.py):

```python
from ota.api import endpoints as ota_endpoints  # noqa: E402
# ...
app.include_router(ota_endpoints.router, prefix="/api/ota", tags=["OTA"])
```

Append at the end of the existing `app.include_router(...)` block (current line ~979). My PR will leave a single 2-line addition to minimize merge conflict; chat1 should rebase trivially.

### Config envs (added to `cretas-python.service` Environment= block)

| Env | Value (prod) | Value (test) |
|---|---|---|
| `OTA_BASE_PATH` | `/www/wwwroot/ota` | `/www/wwwroot/ota-test` |
| `OTA_PRIVATE_KEY_PATH` | `/www/wwwroot/ota/keys/ota_private.pem` | `/www/wwwroot/ota-test/keys/ota_private.pem` |
| `OTA_ADMIN_TOKEN` | (32-byte hex from `openssl rand -hex 32`, in `.env.prod` not committed) | separate token |
| `OTA_HOSTNAME` (Phase 1-3) | `http://47.100.235.168:8083` (IP direct, HTTP) | `http://47.100.235.168:8084` |
| `OTA_HOSTNAME` (Phase 4+) | `https://ota.cretaceousfuture.com` (independent subdomain on nginx 139, Let's Encrypt ECC via acme.sh DNS-01) | same |
| `OTA_DEFAULT_CHANNEL` | `production` | `staging` |

**Channel set** (per Q2 resolution 2026-05-11): only `{production, staging}` — `development` dropped, Metro bundler covers dev iteration.

**systemd integration** (per Q3 resolution 2026-05-11): admin token lives in a **separate** `/www/wwwroot/cretas/.env.ota` file (chmod 600, owner `cretas-python:cretas-python`). Add to `cretas-python.service`:
```ini
[Service]
EnvironmentFile=/www/wwwroot/cretas/.env.prod
EnvironmentFile=/www/wwwroot/cretas/.env.ota   # ← new, separate from .env.prod for independent rotation
```
Then `systemctl daemon-reload && systemctl restart cretas-python`.

---

## 6. Test plan

All tests under `backend/python/ota/tests/`. Run with `pytest backend/python/ota/tests/ -v`.

### 6.1 `test_storage.py` (6 tests)
- [ ] `test_list_timestamps_descending` — fixture: 3 dirs `100/`, `200/`, `300/` → returns `[300, 200, 100]`
- [ ] `test_latest_returns_largest_numeric_not_lexicographic` — fixture: `[20, 100]` → `100` not `20`
- [ ] `test_no_dir_for_runtime_raises_404`
- [ ] `test_no_dir_for_channel_falls_back_to_404`
- [ ] `test_rollback_marker_detected` — fixture dir contains empty `rollback` file
- [ ] `test_path_traversal_rejected` — `asset=../../../etc/passwd` → assertion fails

### 6.2 `test_manifest_builder.py` (8 tests)
- [ ] `test_id_is_sha256_hex_sliced_to_uuid_shape` — fixed metadata.json → expected UUID
- [ ] `test_created_at_is_iso8601_z_suffix`
- [ ] `test_launch_asset_has_application_javascript_content_type`
- [ ] `test_launch_asset_file_extension_is_dot_bundle`
- [ ] `test_regular_asset_hash_is_base64url_sha256`
- [ ] `test_regular_asset_key_is_md5_hex`
- [ ] `test_asset_url_format` — verify `?asset=<rel>&runtimeVersion=<rv>&platform=<p>`
- [ ] `test_extra_expoClient_is_parsed_expoConfig_json`

### 6.3 `test_signing.py` (5 tests)
- [ ] `test_sign_rsa_sha256_produces_valid_base64` — verify with `openssl dgst -sha256 -verify`
- [ ] `test_json_dumps_uses_no_whitespace_separators` — assert `json.dumps(d, separators=(',',':'))` matches reference TypeScript `JSON.stringify` byte-for-byte (use `reference_manifest.json` fixture)
- [ ] `test_ensure_ascii_false_preserves_unicode`
- [ ] `test_dict_key_order_matches_reference` — `id, createdAt, runtimeVersion, assets, launchAsset, metadata, extra`
- [ ] `test_signature_header_sfv_format` — `sig="...", keyid="main"`

### 6.4 `test_multipart.py` (4 tests)
- [ ] `test_normal_update_has_manifest_and_extensions_parts`
- [ ] `test_no_update_has_only_directive_part`
- [ ] `test_rollback_has_only_directive_part`
- [ ] `test_boundary_is_random_per_response` — no hardcoded boundary

### 6.5 `test_manifest_endpoint.py` (10 tests — integration via TestClient)
- [ ] `test_missing_protocol_version_returns_400`
- [ ] `test_protocol_version_0_returns_400` (we only support v1)
- [ ] `test_missing_platform_returns_400`
- [ ] `test_unknown_platform_returns_400`
- [ ] `test_missing_runtime_version_returns_400`
- [ ] `test_unknown_runtime_version_returns_404`
- [ ] `test_default_channel_is_production_when_header_missing`
- [ ] `test_current_update_id_matches_latest_returns_no_update_directive`
- [ ] `test_expect_signature_header_adds_expo_signature_to_manifest_part`
- [ ] `test_no_expect_signature_omits_expo_signature_part_header`

### 6.6 `test_assets_endpoint.py` (5 tests)
- [ ] `test_missing_asset_param_returns_400`
- [ ] `test_path_traversal_returns_400`
- [ ] `test_nonexistent_asset_returns_404`
- [ ] `test_launch_asset_content_type_is_application_javascript`
- [ ] `test_regular_asset_content_type_from_mime_lookup`

### 6.7 `test_admin_endpoints.py` (6 tests)
- [ ] `test_register_requires_bearer_token`
- [ ] `test_register_validates_directory_exists`
- [ ] `test_register_rejects_missing_metadata_json`
- [ ] `test_rollback_creates_rollback_marker_file`
- [ ] `test_list_returns_newest_first`
- [ ] `test_list_includes_rollback_status`

### 6.8 Parity test (1 test, golden)
- [ ] `test_full_manifest_matches_reference_typescript_impl` — load `fixtures/reference_manifest.json` (recorded once by running the TypeScript reference server against `fixtures/sample_metadata.json`), construct Python equivalent, assert `json.dumps(py_manifest, separators=(',',':'), ensure_ascii=False).encode("utf-8")` is byte-identical.

**Total: ~45 tests.** Acceptance gate: 100% pass.

---

## 7. Upload pipeline (Phase 3)

### `scripts/ota/push-bundle.sh`

```bash
#!/usr/bin/env bash
# Usage: ./scripts/ota/push-bundle.sh [--channel production|staging|development] [--platform android]
# Default: production / android

set -euo pipefail
CHANNEL="${1:-production}"
PLATFORM="${2:-android}"
RUNTIME_VERSION="$(jq -r '.expo.version' frontend/CretasFoodTrace/app.json)"
TIMESTAMP="$(date +%s%3N)"  # millis
TARGET="updates/${RUNTIME_VERSION}/${CHANNEL}/${TIMESTAMP}"

# 1. Export from Expo
cd frontend/CretasFoodTrace
rm -rf dist
npx expo export --platform "${PLATFORM}"

# 2. Verify expected outputs
test -f dist/metadata.json
test -f dist/expoConfig.json  # NOTE: may need explicit flag — see §7.1

# 3. Upload via SSH (scp + tar 原子替换 — 适合 OTA bundle 整体替换; 不依赖 rsync)
cd ../..
tar -czf /tmp/ota-bundle-${TIMESTAMP}.tar.gz -C frontend/CretasFoodTrace/dist .
scp /tmp/ota-bundle-${TIMESTAMP}.tar.gz \
    root@47.100.235.168:/tmp/

ssh root@47.100.235.168 "
  mkdir -p /www/wwwroot/ota/${TARGET}
  tar -xzf /tmp/ota-bundle-${TIMESTAMP}.tar.gz -C /www/wwwroot/ota/${TARGET}/
  chown -R cretas-python:cretas-python /www/wwwroot/ota/updates
  rm /tmp/ota-bundle-${TIMESTAMP}.tar.gz
"
rm /tmp/ota-bundle-${TIMESTAMP}.tar.gz

# 4. Register via admin API
curl -sf -X POST https://ota.cretaceousfuture.com/api/ota/admin/register \
  -H "Authorization: Bearer ${OTA_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"runtimeVersion\":\"${RUNTIME_VERSION}\",\"channel\":\"${CHANNEL}\",\"timestamp\":\"${TIMESTAMP}\"}"

echo "✓ Bundle pushed: rv=${RUNTIME_VERSION} channel=${CHANNEL} ts=${TIMESTAMP}"
```

### 7.1 `expoConfig.json` generation
`npx expo export` does NOT automatically emit `expoConfig.json`. We need to either:
- (a) Run `npx expo config --json > dist/expoConfig.json` after export
- (b) Patch the expo-cli call to include it (requires investigation)

Spec decision: use **(a)** — explicit `expo config --json` call in `push-bundle.sh` Step 2.5.

### 7.2 Concurrency & atomicity
Bundle upload is non-atomic (tar extract takes time). During the brief window, a client poll could see partial state. Mitigations:
- Upload to `<timestamp>.tmp/` first
- Move atomically: `mv <ts>.tmp <ts>` (single rename = atomic on POSIX)
- This is a Phase 3 hardening, not Phase 1 blocker

### 7.3 Rollback script

```bash
# scripts/ota/rollback.sh <runtimeVersion> <channel> <timestamp>
curl -sf -X POST https://ota.cretaceousfuture.com/api/ota/admin/rollback \
  -H "Authorization: Bearer ${OTA_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"runtimeVersion\":\"$1\",\"channel\":\"$2\",\"timestamp\":\"$3\"}"
```

This touches a `rollback` marker. Next client poll → `rollBackToEmbedded` directive → client re-fetches the bundle embedded in the APK at build time.

---

## 8. Nginx reverse proxy (Phase 4)

### Independent subdomain (NOT piggy-backed on api.cretaceousfuture.com)

Steve directive 2026-05-12 amended Q1: OTA gets its own subdomain
`ota.cretaceousfuture.com` instead of piggy-backing on the existing
`api.cretaceousfuture.com` vhost. Rationale:

1. **APK-baked URL is forever** — coupling OTA to the main API domain
   locks the two together for every customer install's lifetime.
   Decoupling now costs ~30 min; decoupling later costs every customer
   reinstalling the APK.
2. **Failure-domain isolation** — if the main API has trouble (timeouts,
   JWT middleware bug, rate-limit storms), OTA stays reachable so we can
   still ship a rollback bundle. That's the whole point of OTA.
3. **Semantic** — OTA is static-resource distribution (closer to a CDN),
   not business API. Putting it under `api.*` is misleading.
4. **Future-proof** — switching OTA to OSS/CDN later = one DNS A-record
   change; `api.*` stays untouched.

### Server 139 nginx vhost (independent server block)

Source-of-truth committed at `nginx/ota.cretaceousfuture.com.conf`.
Live config at `/www/server/panel/vhost/nginx/ota.cretaceousfuture.com.conf`
on 139. Installer: `./scripts/ota/install-nginx-ota.sh` (idempotent — scp +
nginx -t + reload + external health probe; auto-removes the new conf if
nginx -t fails so the gateway stays healthy).

```nginx
server {
    listen 80;
    server_name ota.cretaceousfuture.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    http2 on;
    server_name ota.cretaceousfuture.com;

    ssl_certificate     /www/server/panel/vhost/cert/ota.cretaceousfuture.com.pem;
    ssl_certificate_key /www/server/panel/vhost/cert/ota.cretaceousfuture.com.key;
    # ... TLS hardening identical to api.cretaceousfuture.com vhost ...

    location /api/ota/ {
        proxy_pass http://cretas_python;
        include /www/server/panel/vhost/nginx/include/cretas-python-proxy-defaults.conf;
        # OTA-specific overrides on top of shared Python defaults:
        client_max_body_size 50M;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }

    # Catch-all returns 404 — explicit OTA paths only.
    location / { return 404; }
}
```

The `/api/ota/` path prefix is preserved (no rewrite) so the Python router
in `main.py` (`prefix="/api/ota"`) does NOT have to change — manifest URLs
emitted by `manifest_builder._asset_url()` stay self-consistent across the
nginx hop.

### TLS cert acquisition (2026-05-12)

```
ssh root@139.196.165.140
export Ali_Key=<account C AccessKey ID>     # see .claude/rules/aliyun-credentials.md
export Ali_Secret=<account C AccessKey Secret>
~/.acme.sh/acme.sh --issue --dns dns_ali -d ota.cretaceousfuture.com
~/.acme.sh/acme.sh --install-cert -d ota.cretaceousfuture.com \
    --key-file       /www/server/panel/vhost/cert/ota.cretaceousfuture.com.key \
    --fullchain-file /www/server/panel/vhost/cert/ota.cretaceousfuture.com.pem \
    --reloadcmd      "nginx -s reload"
```

- Issuer: Let's Encrypt ECC
- Validity: 2026-05-11 → 2026-08-09 (90 days)
- Auto-renew: acme.sh cron at day 60 (Jul 10), runs reloadcmd on success

### DNS

`ota.cretaceousfuture.com` A → `139.196.165.140`, TTL 600, RecordId
`2053952913076417536` (account C alidns, added via Python aliyunsdk-alidns
`AddDomainRecord` on 2026-05-12).

### Security group (server 47)
Verify port 8083 is reachable from 139 (per `.claude/rules/aliyun-credentials.md` security-group rules). Existing rule grants 139→47:8083 for prod Python.

---

## 9. Local Android build (Phase 5)

### Prerequisites (Steve's Windows box)
- [ ] **Android Studio** with **Android SDK Platform 34** + **Build Tools 34.0.0**
- [ ] **JDK 21** (Zulu OpenJDK 21 already at `C:/Program Files/Zulu/zulu-21` per memory)
- [ ] `ANDROID_HOME` env var set to SDK location
- [ ] Verify `keytool` from JDK 21 is on PATH

### One-time setup

```bash
cd frontend/CretasFoodTrace

# Generate signing keystore (do ONCE, then commit `android/app/release.keystore` is NOT recommended — store outside repo, e.g. ~/.android-keystores/cretas.keystore)
keytool -genkeypair -v \
  -keystore ~/.android-keystores/cretas.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias cretas \
  -storepass "$(openssl rand -hex 16)" \
  -keypass "$(openssl rand -hex 16)" \
  -dname "CN=Cretas Food Trace, O=Cretaceous Future, L=Shanghai, C=CN"
# Save the two random passwords to a password manager. They are required for every build.
```

### `android/gradle.properties` (LOCAL, not committed)

```properties
CRETAS_UPLOAD_STORE_FILE=/Users/Steve/.android-keystores/cretas.keystore
CRETAS_UPLOAD_KEY_ALIAS=cretas
CRETAS_UPLOAD_STORE_PASSWORD=<from password manager>
CRETAS_UPLOAD_KEY_PASSWORD=<from password manager>
```

### `android/app/build.gradle` snippet (committed)

```gradle
android {
    signingConfigs {
        release {
            if (project.hasProperty('CRETAS_UPLOAD_STORE_FILE')) {
                storeFile file(CRETAS_UPLOAD_STORE_FILE)
                storePassword CRETAS_UPLOAD_STORE_PASSWORD
                keyAlias CRETAS_UPLOAD_KEY_ALIAS
                keyPassword CRETAS_UPLOAD_KEY_PASSWORD
            }
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
            // ... other release config
        }
    }
}
```

### Build command (per-release)

```bash
cd frontend/CretasFoodTrace
npx expo prebuild --platform android --clean   # regenerates android/ from app.json
cd android
./gradlew clean
./gradlew assembleRelease

# Output: android/app/build/outputs/apk/release/app-release.apk
```

### Windows path-length warning
Per memory `setup-apk-build-windows.md`: CMake 250-char path limit. If `.worktrees/ota-self-hosted/frontend/CretasFoodTrace/android/app/.cxx/...` exceeds, set `buildStagingDirectory` to `C:/b/cretas-ota/`.

### Verifying expo-updates is enabled in the APK

After build, inspect `app/src/main/AndroidManifest.xml`:
```xml
<meta-data android:name="expo.modules.updates.ENABLED" android:value="true" />
<meta-data android:name="expo.modules.updates.EXPO_RUNTIME_VERSION" android:value="1.0.0" />
<meta-data android:name="expo.modules.updates.EXPO_UPDATE_URL"
    android:value="https://ota.cretaceousfuture.com/api/ota/manifest" />
<meta-data android:name="expo.modules.updates.EXPO_UPDATES_CODE_SIGNING_CERTIFICATE"
    android:value="@string/cretas_ota_cert" />
```

If `ENABLED=false`, prebuild didn't flip the flag — investigate `app.json` config.

---

## 10. `app.json` changes (Phase 5, deferred until Phase 1-4 ready)

```diff
   "updates": {
-    "url": "https://u.expo.dev/PLACEHOLDER-EAS-PROJECT-ID",
+    "url": "https://ota.cretaceousfuture.com/api/ota/manifest",
     "checkAutomatically": "ON_LOAD",
-    "fallbackToCacheTimeout": 5000
+    "fallbackToCacheTimeout": 5000,
+    "codeSigningCertificate": "./ota_public_cert.pem",
+    "codeSigningMetadata": {
+      "keyid": "main",
+      "alg": "rsa-v1_5-sha256"
+    },
+    "requestHeaders": {
+      "expo-channel-name": "production"
+    }
   },
   ...
   "extra": {
-    "eas": {
-      "projectId": "com.cretas.foodtrace"
-    }
+    "router": {}
   }
```

The `extra` field can be removed entirely if there's no other use, but keeping a placeholder avoids breaking any code that may dereference `Constants.expoConfig.extra`.

### Channel handling
`requestHeaders.expo-channel-name = "production"` tells the client to send `expo-channel-name: production` on every manifest request. For staging APKs (future), set this to `"staging"`. The build profile mapping is independent of `eas.json` since we don't use EAS Build.

---

## 11. Deployment plan

### 11.1 Test environment first (per `.claude/rules/server-operations.md`)

```bash
# Day 1 morning: deploy server module to test (8084) only
./scripts/deploy/deploy-smartbi-python.sh --env test

# Smoke
curl -s http://47.100.235.168:8084/api/ota/health | jq
```

### 11.2 Prod deploy gating
- [ ] Test env smoke 100% PASS
- [ ] `pytest backend/python/ota/tests/` 100% PASS (45/45)
- [ ] Parity test against reference TypeScript impl PASSES byte-exact
- [ ] First APK build successful on Windows
- [ ] First test OTA push end-to-end works on Android emulator
- [ ] Steve verbal sign-off

```bash
# Day 2: prod deploy
./scripts/deploy/deploy-smartbi-python.sh --env prod
```

### 11.3 Customer rollout (separate later phase, NOT this dispatch)
- [ ] Build initial APK with self-hosted OTA URL baked in
- [ ] Distribute to customer F006 (Liutengmen) per memory `reference_f006_liutengmen_prod_accounts.md`
- [ ] Push first non-trivial OTA bundle, observe device pickup
- [ ] If issue: roll back via `scripts/ota/rollback.sh`

---

## 12. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Byte-exact signing fails due to JSON whitespace | Parity test against reference impl in §6.8 catches this in unit tests |
| Cert/keypair mismatch in APK | Phase 5 includes verification step — inspect AndroidManifest.xml meta-data |
| Path traversal on `/api/ota/assets?asset=...` | §2.2 hardening + `test_path_traversal_returns_400` |
| Admin token leakage | Token only in `.env.ota` (chmod 600, separate from `.env.prod` per Q3), never logged, rotate annually |
| Bundle upload races with client poll | §7.2 atomic rename mitigation (Phase 3 hardening) |
| Cert expires in 2031 | Calendar reminder; rotation requires new APK build |
| `expo export` doesn't emit `expoConfig.json` | §7.1 fallback: explicit `expo config --json` call in push script |
| Windows path-length kills Gradle | §9 — `buildStagingDirectory` override |
| Public IPv4 of server 47 changes | Customer APKs hit `ota.cretaceousfuture.com` (DNS abstraction), nginx on 139 forwards — IP change just needs 139 nginx update |
| Concurrent /clear loses chat5 worktree commits | Per memory `feedback_chat_must_push_before_clear.md` — push before any `/clear` |
| chat1 main.py concurrent edits | Per Q5 resolution: Phase 1 PR does NOT touch main.py. Separate post-chat1-merge 1-line micro-PR registers the router. |

---

## 13. What this spec explicitly does NOT cover

- iOS APK builds (out of scope — Android-only customer base for now)
- Multi-tenant signing (single keypair for all customers)
- Bundle compression / delta updates (Expo handles compression at HTTP layer)
- Analytics/telemetry (no per-update download tracking; add later if needed)
- Web admin UI for managing updates (CLI scripts only)
- Auto-detect rollback on crash loops client-side (Expo's `relaunch` API, not server)
- Migration of existing customer APKs from broken-EAS-config state — they'll need ONE reinstall to get the new self-hosted-config APK; this is unavoidable since `updates.url` is baked into the binary

---

## 14. Acceptance criteria (Phase 0 → Phase 6)

- [ ] **Phase 0 (spec):** This document reviewed + approved by organizer (Steve)
- [ ] **Phase 1 (server):** `pytest backend/python/ota/tests/` 45/45 PASS; `/api/ota/health` returns 200; manifest endpoint serves valid multipart/mixed per protocol
- [ ] **Phase 2 (keys):** `ota_public_cert.pem` committed; private key on 47 (`ls -la /www/wwwroot/ota/keys/`) shows mode 600
- [ ] **Phase 3 (push script):** `./scripts/ota/push-bundle.sh production android` exits 0; `/api/ota/admin/list` shows the registered bundle
- [ ] **Phase 4 (nginx):** `curl https://ota.cretaceousfuture.com/api/ota/health` returns 200 from external network
- [ ] **Phase 5 (build):** Local `./gradlew assembleRelease` produces signed APK; `aapt dump xmltree` confirms `ENABLED=true`
- [ ] **Phase 6 (E2E):** Fresh APK on emulator → push a bundle with visible UI change (e.g. button color) → close + reopen app → new UI visible without reinstall

---

## 15. References

- [Expo Updates v1 protocol spec](https://docs.expo.dev/technical-specs/expo-updates-1/)
- [Reference TypeScript implementation](https://github.com/expo/custom-expo-updates-server)
- [Expo code signing docs](https://docs.expo.dev/eas-update/code-signing/)
- `docs/runbooks/2026-05-10-eas-ota-setup-runbook.md` — EAS path (rejected), kept for client behavior reference
- `.claude/rules/server-operations.md` — deploy + environment conventions
- `.claude/rules/python-services-architecture.md` — Python module conventions
- `.claude/rules/concurrent-edit-safety.md` — main.py merge coord
- `.claude/rules/python-java-port.md` Rule 8 — JSON byte-shape parity (same class of byte-exact issue as the signing concern in §4.2)
- PR #296 (MERGED) — installed `expo-updates@~0.28.18`, set runtimeVersion policy
- PR #356 (CLOSED) — EAS init attempt, rolled back per Steve directive 2026-05-11

---

## 16. Open questions for organizer review — RESOLVED 2026-05-11

All 6 questions answered by Steve via dispatch reply 2026-05-11; spec amended inline elsewhere.

1. [x] **HTTPS cert + domain** — Domain DNS is on aliyun account C. **Phase 1-3 use IP direct over HTTP** (`http://47.100.235.168:8083` as `OTA_HOSTNAME`). **Amended 2026-05-12** by Steve directive: do NOT piggy-back on existing `api.cretaceousfuture.com` cert. OTA gets its own subdomain `ota.cretaceousfuture.com` because URL is baked into the APK forever; coupling OTA to the main API domain is a one-way door we'd regret (full rationale in §8). Cert acquired via `acme.sh --issue --dns dns_ali` against account C alidns, Let's Encrypt ECC, 90-day auto-renew via acme.sh cron.
2. [x] **Channel set** — `{production, staging}` only. **Drop `development`** (Metro bundler covers dev). `app.json:updates.requestHeaders.expo-channel-name` defaults to `"production"`.
3. [x] **`OTA_ADMIN_TOKEN` location** — `/www/wwwroot/cretas/.env.ota` (separate from `.env.prod` for independent rotation), chmod 600, owner `cretas-python:cretas-python`. systemd `cretas-python.service` adds `EnvironmentFile=/www/wwwroot/cretas/.env.ota`.
4. [x] **Bundle retention** — keep last N=10 per `(runtimeVersion, channel)` **AND** always preserve the latest (rolling-window prune). Add `scripts/ota/prune-bundles.sh` (Phase 3 extension, NOT a Phase 1 blocker).
5. [x] **main.py router coord** — chat1 owns main.py (PR #360 in flight). Phase 1 PR ships **only** `backend/python/ota/` module + tests; **does NOT touch main.py**. After chat1's main.py PR merges, ship a separate 1-line micro-PR registering `app.include_router(ota_endpoints.router, prefix="/api/ota", tags=["OTA"])`. Avoids rebase conflicts.
6. [x] **Customer rollout (F006)** — out of chat5 scope. Steve + organizer own the F006 cutover (WeChat APK distribution + customer feedback monitoring). Chat5 deliverable terminates at Phase 6 emulator E2E demo.

**GO Phase 1** confirmed 2026-05-11.

---

**Status:** spec approved, Phase 1 implementation IN PROGRESS.
