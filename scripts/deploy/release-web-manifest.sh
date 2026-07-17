#!/usr/bin/env bash

WEB_RELEASE_MANIFEST_FORMAT="cretas-release-web-manifest-v2"
WEB_RELEASE_SOURCE_PATH="web-admin"
WEB_RELEASE_MANIFEST_NAME="release-web.manifest"
WEB_RELEASE_ARCHIVE_NAME="release-web-dist.tar.gz"

web_release_cache_root() {
    printf '%s\n' "${CRETAS_WEB_CACHE_DIR:-$HOME/.cache/cretas/web-admin-deploy}"
}

web_release_default_manifest() {
    printf '%s/current/%s\n' "$(web_release_cache_root)" "$WEB_RELEASE_MANIFEST_NAME"
}

web_release_manifest_field() {
    local manifest=$1
    local key=$2

    awk -v key="$key" '
        index($0, key "=") == 1 {
            count++
            value = substr($0, length(key) + 2)
            sub(/\r$/, "", value)
        }
        END {
            if (count != 1) exit 1
            print value
        }
    ' "$manifest"
}

web_release_sha256_file() {
    local path=$1

    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$path" | awk '{print tolower($1)}'
    else
        shasum -a 256 "$path" | awk '{print tolower($1)}'
    fi
}

web_release_verify_dist() {
    local dist_dir=$1
    local reference relative_path asset_count

    [ -s "$dist_dir/index.html" ] || return 1
    [ -d "$dist_dir/assets" ] || return 1
    asset_count=$(find "$dist_dir/assets" -type f 2>/dev/null | wc -l | tr -d ' ')
    [ "$asset_count" -gt 0 ] || return 1

    reference=$(grep -oE 'assets/[A-Za-z0-9._/-]+\.(js|css)' "$dist_dir/index.html" | head -1 || true)
    [ -n "$reference" ] || return 1
    while IFS= read -r relative_path; do
        [ -f "$dist_dir/$relative_path" ] || return 1
    done < <(grep -oE 'assets/[A-Za-z0-9._/-]+\.(js|css)' "$dist_dir/index.html" | LC_ALL=C sort -u)
}

web_release_extract_index() {
    local archive=$1
    local output=$2

    tar -xOzf "$archive" ./index.html > "$output" 2>/dev/null \
        || tar -xOzf "$archive" index.html > "$output" 2>/dev/null
    [ -s "$output" ]
}

web_release_verify_archive() {
    local archive=$1
    local listing index_file relative_path asset_count index_sha rc=0

    [ -s "$archive" ] || return 1
    listing=$(mktemp) || return 1
    index_file=$(mktemp) || { rm -f "$listing"; return 1; }

    gzip -t "$archive" 2>/dev/null || rc=1
    if [ "$rc" = "0" ]; then
        tar -tzf "$archive" > "$listing" 2>/dev/null || rc=1
    fi
    if [ "$rc" = "0" ]; then
        grep -Eq '^(\./)?index\.html$' "$listing" || rc=1
        asset_count=$(grep -Ec '^(\./)?assets/.+[^/]$' "$listing" || true)
        [ "$asset_count" -gt 0 ] || rc=1
    fi
    if [ "$rc" = "0" ]; then
        web_release_extract_index "$archive" "$index_file" || rc=1
    fi
    if [ "$rc" = "0" ]; then
        grep -qE 'assets/[A-Za-z0-9._/-]+\.(js|css)' "$index_file" || rc=1
    fi
    if [ "$rc" = "0" ]; then
        while IFS= read -r relative_path; do
            if ! grep -Fxq "./$relative_path" "$listing" && ! grep -Fxq "$relative_path" "$listing"; then
                rc=1
                break
            fi
        done < <(grep -oE 'assets/[A-Za-z0-9._/-]+\.(js|css)' "$index_file" | LC_ALL=C sort -u)
    fi
    if [ "$rc" = "0" ]; then
        index_sha=$(web_release_sha256_file "$index_file") || rc=1
    fi

    if [ "$rc" = "0" ]; then
        WEB_RELEASE_VERIFIED_INDEX_SHA256=$index_sha
        WEB_RELEASE_VERIFIED_ASSET_COUNT=$asset_count
    fi
    rm -f "$listing" "$index_file"
    [ "$rc" = "0" ]
}

web_release_require_clean_worktree() {
    local repo_root=$1
    [ -z "$(git -C "$repo_root" status --porcelain --untracked-files=normal 2>/dev/null)" ]
}

web_release_require_clean_exact_origin_main() {
    local repo_root=$1
    local head_sha origin_sha

    head_sha=$(git -C "$repo_root" rev-parse HEAD 2>/dev/null) || return 1
    origin_sha=$(git -C "$repo_root" rev-parse origin/main 2>/dev/null) || return 1
    [ "$head_sha" = "$origin_sha" ] || return 1
    web_release_require_clean_worktree "$repo_root"
}

web_release_write() {
    local repo_root=$1
    local source_dist=$2
    local manifest=$3
    local build_command=$4
    local build_commit web_tree node_version npm_version package_lock_sha
    local archive_sha index_sha asset_count cache_root stage_dir old_dir archive_path

    web_release_require_clean_worktree "$repo_root" || return 1
    web_release_verify_dist "$source_dist" || return 1
    case "$build_command" in *$'\n'*|*$'\r'*) return 1 ;; esac

    build_commit=$(git -C "$repo_root" rev-parse HEAD 2>/dev/null) || return 1
    web_tree=$(git -C "$repo_root" rev-parse "HEAD:$WEB_RELEASE_SOURCE_PATH" 2>/dev/null) || return 1
    node_version=$(node --version 2>/dev/null | tr -d '\r\n') || return 1
    npm_version=$(npm --version 2>/dev/null | tr -d '\r\n') || return 1
    package_lock_sha=$(web_release_sha256_file "$repo_root/web-admin/package-lock.json") || return 1
    index_sha=$(web_release_sha256_file "$source_dist/index.html") || return 1
    asset_count=$(find "$source_dist/assets" -type f | wc -l | tr -d ' ')

    cache_root=$(dirname "$(dirname "$manifest")")
    stage_dir="$cache_root/.current.staging.$$"
    old_dir="$cache_root/.current.old.$$"
    archive_path="$stage_dir/$WEB_RELEASE_ARCHIVE_NAME"
    rm -rf "$stage_dir" "$old_dir"
    mkdir -p "$stage_dir" || return 1
    tar czf "$archive_path" -C "$source_dist" . || { rm -rf "$stage_dir"; return 1; }
    web_release_verify_archive "$archive_path" || { rm -rf "$stage_dir"; return 1; }
    [ "$WEB_RELEASE_VERIFIED_INDEX_SHA256" = "$index_sha" ] \
        || { rm -rf "$stage_dir"; return 1; }
    [ "$WEB_RELEASE_VERIFIED_ASSET_COUNT" = "$asset_count" ] \
        || { rm -rf "$stage_dir"; return 1; }
    archive_sha=$(web_release_sha256_file "$archive_path") || { rm -rf "$stage_dir"; return 1; }

    {
        printf 'format=%s\n' "$WEB_RELEASE_MANIFEST_FORMAT"
        printf 'success=true\n'
        printf 'build_commit=%s\n' "$build_commit"
        printf 'web_tree=%s\n' "$web_tree"
        printf 'archive_path=%s\n' "$WEB_RELEASE_ARCHIVE_NAME"
        printf 'archive_sha256=%s\n' "$archive_sha"
        printf 'index_sha256=%s\n' "$index_sha"
        printf 'asset_count=%s\n' "$asset_count"
        printf 'package_lock_sha256=%s\n' "$package_lock_sha"
        printf 'node_version=%s\n' "$node_version"
        printf 'npm_version=%s\n' "$npm_version"
        printf 'build_command=%s\n' "$build_command"
    } > "$stage_dir/$WEB_RELEASE_MANIFEST_NAME" || { rm -rf "$stage_dir"; return 1; }

    mkdir -p "$cache_root" || { rm -rf "$stage_dir"; return 1; }
    if [ -e "$cache_root/current" ]; then
        mv "$cache_root/current" "$old_dir" || { rm -rf "$stage_dir"; return 1; }
    fi
    if ! mv "$stage_dir" "$cache_root/current"; then
        [ ! -e "$old_dir" ] || mv "$old_dir" "$cache_root/current" || true
        rm -rf "$stage_dir"
        return 1
    fi
    rm -rf "$old_dir"

    WEB_RELEASE_ARCHIVE_PATH="$cache_root/current/$WEB_RELEASE_ARCHIVE_NAME"
    WEB_RELEASE_ARCHIVE_SHA256=$archive_sha
    WEB_RELEASE_INDEX_SHA256=$index_sha
    WEB_RELEASE_ASSET_COUNT=$asset_count
    WEB_RELEASE_BUILD_COMMIT=$build_commit
    WEB_RELEASE_WEB_TREE=$web_tree
}

web_release_validate() {
    local manifest=$1
    local repo_root=$2
    local format success build_commit web_tree archive_relative archive_sha index_sha
    local asset_count package_lock_sha node_version npm_version build_command
    local current_tree built_tree manifest_dir archive actual_archive_sha actual_lock_sha

    [ -f "$manifest" ] || return 1
    web_release_require_clean_exact_origin_main "$repo_root" || return 1

    format=$(web_release_manifest_field "$manifest" format) || return 1
    success=$(web_release_manifest_field "$manifest" success) || return 1
    build_commit=$(web_release_manifest_field "$manifest" build_commit) || return 1
    web_tree=$(web_release_manifest_field "$manifest" web_tree) || return 1
    archive_relative=$(web_release_manifest_field "$manifest" archive_path) || return 1
    archive_sha=$(web_release_manifest_field "$manifest" archive_sha256) || return 1
    index_sha=$(web_release_manifest_field "$manifest" index_sha256) || return 1
    asset_count=$(web_release_manifest_field "$manifest" asset_count) || return 1
    package_lock_sha=$(web_release_manifest_field "$manifest" package_lock_sha256) || return 1
    node_version=$(web_release_manifest_field "$manifest" node_version) || return 1
    npm_version=$(web_release_manifest_field "$manifest" npm_version) || return 1
    build_command=$(web_release_manifest_field "$manifest" build_command) || return 1

    [ "$format" = "$WEB_RELEASE_MANIFEST_FORMAT" ] || return 1
    [ "$success" = "true" ] || return 1
    [[ "$build_commit" =~ ^[0-9a-fA-F]{40}$ ]] || return 1
    [[ "$web_tree" =~ ^[0-9a-fA-F]{40}$ ]] || return 1
    [[ "$archive_sha" =~ ^[0-9a-fA-F]{64}$ ]] || return 1
    [[ "$index_sha" =~ ^[0-9a-fA-F]{64}$ ]] || return 1
    [[ "$package_lock_sha" =~ ^[0-9a-fA-F]{64}$ ]] || return 1
    [[ "$asset_count" =~ ^[1-9][0-9]*$ ]] || return 1
    [ "$archive_relative" = "$WEB_RELEASE_ARCHIVE_NAME" ] || return 1
    [ -n "$node_version" ] && [ -n "$npm_version" ] && [ -n "$build_command" ] || return 1

    current_tree=$(git -C "$repo_root" rev-parse "origin/main:$WEB_RELEASE_SOURCE_PATH" 2>/dev/null) || return 1
    [ "$web_tree" = "$current_tree" ] || return 1
    git -C "$repo_root" cat-file -e "${build_commit}^{commit}" 2>/dev/null || return 1
    built_tree=$(git -C "$repo_root" rev-parse "${build_commit}:$WEB_RELEASE_SOURCE_PATH" 2>/dev/null) || return 1
    [ "$built_tree" = "$current_tree" ] || return 1

    manifest_dir=$(cd "$(dirname "$manifest")" 2>/dev/null && pwd) || return 1
    archive="$manifest_dir/$archive_relative"
    actual_archive_sha=$(web_release_sha256_file "$archive") || return 1
    [ "$actual_archive_sha" = "${archive_sha,,}" ] || return 1
    web_release_verify_archive "$archive" || return 1
    [ "$WEB_RELEASE_VERIFIED_INDEX_SHA256" = "${index_sha,,}" ] || return 1
    [ "$WEB_RELEASE_VERIFIED_ASSET_COUNT" = "$asset_count" ] || return 1
    actual_lock_sha=$(web_release_sha256_file "$repo_root/web-admin/package-lock.json") || return 1
    [ "$actual_lock_sha" = "${package_lock_sha,,}" ] || return 1

    WEB_RELEASE_BUILD_COMMIT=$build_commit
    WEB_RELEASE_WEB_TREE=$web_tree
    WEB_RELEASE_ARCHIVE_SHA256=$actual_archive_sha
    WEB_RELEASE_INDEX_SHA256=$WEB_RELEASE_VERIFIED_INDEX_SHA256
    WEB_RELEASE_ASSET_COUNT=$WEB_RELEASE_VERIFIED_ASSET_COUNT
    WEB_RELEASE_ARCHIVE_PATH=$archive
}

web_release_ensure_dependencies() {
    local repo_root=$1
    local web_admin_dir="$repo_root/web-admin"
    local vite_bin="$web_admin_dir/node_modules/.bin/vite"
    local vite_cmd="$web_admin_dir/node_modules/.bin/vite.cmd"
    local dependency_manifest="$web_admin_dir/node_modules/.cretas-package-lock.sha256"
    local lock_hash manifest_tmp

    lock_hash=$(web_release_sha256_file "$web_admin_dir/package-lock.json") || return 1
    if [ -f "$dependency_manifest" ] \
        && [ "$(tr -d '\r\n' < "$dependency_manifest")" = "$lock_hash" ] \
        && { [ -x "$vite_bin" ] || [ -f "$vite_cmd" ]; }; then
        return 0
    fi
    (cd "$web_admin_dir" && npm ci --legacy-peer-deps --prefer-offline --no-audit --no-fund) || return 1
    [ -x "$vite_bin" ] || [ -f "$vite_cmd" ] || return 1
    manifest_tmp="${dependency_manifest}.tmp.$$"
    printf '%s\n' "$lock_hash" > "$manifest_tmp" && mv -f "$manifest_tmp" "$dependency_manifest"
}

web_release_build() {
    local repo_root=$1
    local manifest=$2
    local dist_dir="$repo_root/web-admin/dist"

    web_release_require_clean_worktree "$repo_root" \
        || { echo "ERROR: Web manifest build requires a clean worktree" >&2; return 1; }
    web_release_ensure_dependencies "$repo_root" \
        || { echo "ERROR: Web dependency restore failed" >&2; return 1; }
    rm -rf "$dist_dir"
    (cd "$repo_root/web-admin" && npm run build) || return 1
    web_release_verify_dist "$dist_dir" || return 1
    web_release_require_clean_worktree "$repo_root" \
        || { echo "ERROR: worktree changed during Web release build" >&2; return 1; }
    web_release_write "$repo_root" "$dist_dir" "$manifest" "npm run build" || return 1
    printf 'Web release manifest: %s\n' "$manifest"
    printf 'Web release archive: %s\n' "$WEB_RELEASE_ARCHIVE_PATH"
}

web_release_usage() {
    cat <<'EOF'
Usage:
  ./scripts/deploy/release-web-manifest.sh build [--manifest <path>]
  ./scripts/deploy/release-web-manifest.sh validate [--manifest <path>]

Build creates one Vite production dist and stores one immutable tar.gz with a
trusted manifest. Validation requires a clean HEAD exactly equal to origin/main.
A different build commit is reusable only when its web-admin Git tree is identical.
EOF
}

web_release_main() {
    local command=${1:-}
    local script_dir repo_root manifest
    shift || true
    script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
    repo_root=$(cd "$script_dir/../.." && pwd)
    manifest=$(web_release_default_manifest)

    while [ $# -gt 0 ]; do
        case "$1" in
            --manifest) manifest=${2:-}; shift 2 ;;
            -h|--help) web_release_usage; return 0 ;;
            *) echo "ERROR: unknown argument: $1" >&2; web_release_usage >&2; return 2 ;;
        esac
    done

    case "$command" in
        build) web_release_build "$repo_root" "$manifest" ;;
        validate)
            web_release_validate "$manifest" "$repo_root" || return 1
            printf 'Trusted Web archive: %s\n' "$WEB_RELEASE_ARCHIVE_PATH"
            ;;
        *) web_release_usage >&2; return 2 ;;
    esac
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    set -euo pipefail
    web_release_main "$@"
fi
