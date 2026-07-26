#!/usr/bin/env bash
# OSS/CDN publishing helpers for self-hosted Expo OTA bundles.
# shellcheck shell=bash

set -euo pipefail

OTA_OSS_BUCKET="${OTA_OSS_BUCKET:-cretas-download}"
OTA_OSS_CONFIG="${OTA_OSS_CONFIG:-$HOME/.ossutilconfig-apk}"
OTA_OSS_UPDATE_PREFIX="${OTA_OSS_UPDATE_PREFIX:-app-updates/updates}"
OTA_OSS_STORE_PREFIX="${OTA_OSS_STORE_PREFIX:-app-updates/assets-store}"
OTA_ASSET_CACHE_CONTROL="${OTA_ASSET_CACHE_CONTROL:-public, max-age=31536000, immutable}"
OTA_HBC_CONTENT_ENCODING="${OTA_HBC_CONTENT_ENCODING:-gzip}"

ota_resolve_ossutil() {
  local candidate="${OSSUTIL_BIN:-${OSSUTIL:-}}"
  if [ -n "$candidate" ] && command -v "$candidate" >/dev/null 2>&1; then
    command -v "$candidate"
    return 0
  fi
  if command -v ossutil64 >/dev/null 2>&1; then
    command -v ossutil64
    return 0
  fi
  if command -v ossutil >/dev/null 2>&1; then
    command -v ossutil
    return 0
  fi
  echo "ERROR: ossutil/ossutil64 not found" >&2
  return 1
}

ota_create_precompressed_hbc_dir() {
  local dist_dir="$1"
  local tmp_dir

  [ "$OTA_HBC_CONTENT_ENCODING" = "gzip" ] || return 0
  [ -d "$dist_dir/_expo/static/js" ] || {
    echo "ERROR: missing $dist_dir/_expo/static/js" >&2
    return 1
  }

  tmp_dir="$(mktemp -d)"
  DIST_DIR="$dist_dir" TMP_DIR="$tmp_dir" node <<'NODE'
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const distDir = process.env.DIST_DIR;
const tmpDir = process.env.TMP_DIR;
const jsRoot = path.join(distDir, '_expo', 'static', 'js');

function walk(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const fullPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      walk(fullPath);
      continue;
    }
    if (!entry.isFile() || !entry.name.endsWith('.hbc')) continue;

    const relativePath = path.relative(distDir, fullPath);
    const outputPath = path.join(tmpDir, relativePath);
    fs.mkdirSync(path.dirname(outputPath), { recursive: true });
    fs.writeFileSync(
      outputPath,
      zlib.gzipSync(fs.readFileSync(fullPath), { level: 6 }),
    );
  }
}

walk(jsRoot);
NODE
  printf '%s\n' "$tmp_dir"
}

ota_upload_bundle_to_oss() {
  local dist_dir="$1"
  local runtime_version="$2"
  local channel="$3"
  local timestamp="$4"
  local ossutil
  local update_dest
  local store_dest
  local gzip_dir=""

  [ -f "$OTA_OSS_CONFIG" ] || {
    echo "ERROR: OTA OSS config missing: $OTA_OSS_CONFIG" >&2
    return 1
  }
  ossutil="$(ota_resolve_ossutil)"
  update_dest="oss://${OTA_OSS_BUCKET}/${OTA_OSS_UPDATE_PREFIX}/${runtime_version}/${channel}/${timestamp}"
  store_dest="oss://${OTA_OSS_BUCKET}/${OTA_OSS_STORE_PREFIX}"

  if [ "$OTA_HBC_CONTENT_ENCODING" = "gzip" ]; then
    "$ossutil" cp --config-file "$OTA_OSS_CONFIG" -r -f \
      --meta "Cache-Control:${OTA_ASSET_CACHE_CONTROL}" \
      --exclude "*.hbc" \
      "$dist_dir/_expo/" "${update_dest}/_expo/" >/dev/null

    gzip_dir="$(ota_create_precompressed_hbc_dir "$dist_dir")"
    while IFS= read -r -d '' file; do
      local relative_path="${file#"$gzip_dir"/}"
      "$ossutil" cp --config-file "$OTA_OSS_CONFIG" -f \
        --meta "Cache-Control:${OTA_ASSET_CACHE_CONTROL}#Content-Encoding:gzip#Content-Type:application/octet-stream" \
        "$file" "${update_dest}/${relative_path}" >/dev/null
    done < <(find "$gzip_dir" -type f -name '*.hbc' -print0)
    rm -rf "$gzip_dir"
  else
    "$ossutil" cp --config-file "$OTA_OSS_CONFIG" -r -f \
      --meta "Cache-Control:${OTA_ASSET_CACHE_CONTROL}" \
      "$dist_dir/_expo/" "${update_dest}/_expo/" >/dev/null
  fi

  if [ -d "$dist_dir/assets" ]; then
    "$ossutil" cp --config-file "$OTA_OSS_CONFIG" -r -f \
      --meta "Cache-Control:${OTA_ASSET_CACHE_CONTROL}" \
      "$dist_dir/assets/" "${store_dest}/" >/dev/null
  fi

  local launch_asset
  launch_asset="$(find "$dist_dir/_expo/static/js" -type f -name '*.hbc' | head -n 1)"
  [ -n "$launch_asset" ] || {
    echo "ERROR: no Hermes launch asset found" >&2
    return 1
  }
  local launch_relative="${launch_asset#"$dist_dir"/}"
  "$ossutil" stat --config-file "$OTA_OSS_CONFIG" \
    "${update_dest}/${launch_relative}" >/dev/null

  printf '%s\n' "$update_dest"
}
