#!/usr/bin/env bash

WEB_RELEASE_MANIFEST_FORMAT="cretas-release-web-manifest-v1"
WEB_RELEASE_SOURCE_PATH="web-admin"
WEB_RELEASE_MANIFEST_NAME="release-web.manifest"

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

web_release_hash_tree() {
    local root=$1
    local list_file digest tree_digest

    [ -d "$root" ] || return 1
    list_file=$(mktemp) || return 1
    (
        cd "$root" || exit 1
        find . -type f -print0 | LC_ALL=C sort -z | while IFS= read -r -d '' file; do
            digest=$(web_release_sha256_file "$file") || exit 1
            printf '%s  %s\n' "$digest" "${file#./}"
        done
    ) > "$list_file" || { rm -f "$list_file"; return 1; }
    [ -s "$list_file" ] || { rm -f "$list_file"; return 1; }
    tree_digest=$(web_release_sha256_file "$list_file") || { rm -f "$list_file"; return 1; }
    printf '%s\n' "$tree_digest"
    rm -f "$list_file"
}

web_release_verify_dist() {
    local dist_dir=$1
    local reference relative_path asset_count

    [ -s "$dist_dir/index.html" ] || return 1
    [ -d "$dist_dir/assets" ] || return 1
    asset_count=$(find "$dist_dir/assets" -type f 2>/dev/null | wc -l | tr -d ' ')
    [ "$asset_count" -gt 0 ] || return 1

    # A Vite release must reference at least one local hashed asset, and every
    # referenced asset must exist in the cached dist. This rejects partial or
    # mixed copies even when index.html itself is present.
    reference=$(grep -oE 'assets/[A-Za-z0-9._/-]+\.(js|css)' "$dist_dir/index.html" | head -1 || true)
    [ -n "$reference" ] || return 1
    while IFS= read -r relative_path; do
        [ -f "$dist_dir/$relative_path" ] || return 1
    done < <(grep -oE 'assets/[A-Za-z0-9._/-]+\.(js|css)' "$dist_dir/index.html" | LC_ALL=C sort -u)
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
    local index_sha assets_sha dist_sha asset_count cache_root stage_dir old_dir

    web_release_require_clean_worktree "$repo_root" || return 1
    web_release_verify_dist "$source_dist" || return 1
    case "$build_command" in *$'\n'*|*$'\r'*) return 1 ;; esac

    build_commit=$(git -C "$repo_root" rev-parse HEAD 2>/dev/null) || return 1
    web_tree=$(git -C "$repo_root" rev-parse "HEAD:$WEB_RELEASE_SOURCE_PATH" 2>/dev/null) || return 1
    node_version=$(node --version 2>/dev/null | tr -d '\r\n') || return 1
    npm_version=$(npm --version 2>/dev/null | tr -d '\r\n') || return 1
    package_lock_sha=$(web_release_sha256_file "$repo_root/web-admin/package-lock.json") || return 1
    index_sha=$(web_release_sha256_file "$source_dist/index.html") || return 1
    assets_sha=$(web_release_hash_tree "$source_dist/assets") || return 1
    dist_sha=$(web_release_hash_tree "$source_dist") || return 1
    asset_count=$(find "$source_dist/assets" -type f | wc -l | tr -d ' ')

    cache_root=$(dirname "$(dirname "$manifest")")
    stage_dir="$cache_root/.current.staging.$$"
    old_dir="$cache_root/.current.old.$$"
    rm -rf "$stage_dir" "$old_dir"
    mkdir -p "$stage_dir/dist" || return 1
    cp -a "$source_dist/." "$stage_dir/dist/" || { rm -rf "$stage_dir"; return 1; }

    {
        printf 'format=%s\n' "$WEB_RELEASE_MANIFEST_FORMAT"
        printf 'success=true\n'
        printf 'build_commit=%s\n' "$build_commit"
        printf 'web_tree=%s\n' "$web_tree"
        printf 'dist_path=dist\n'
        printf 'dist_sha256=%s\n' "$dist_sha"
        printf 'index_sha256=%s\n' "$index_sha"
        printf 'assets_sha256=%s\n' "$assets_sha"
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
}

web_release_validate() {
    local manifest=$1
    local repo_root=$2
    local format success build_commit web_tree dist_relative dist_sha index_sha assets_sha
    local asset_count package_lock_sha node_version npm_version build_command
    local current_tree built_tree manifest_dir dist_dir actual_dist_sha actual_index_sha
    local actual_assets_sha actual_asset_count actual_lock_sha

    [ -f "$manifest" ] || return 1
    web_release_require_clean_exact_origin_main "$repo_root" || return 1

    format=$(web_release_manifest_field "$manifest" format) || return 1
    success=$(web_release_manifest_field "$manifest" success) || return 1
    build_commit=$(web_release_manifest_field "$manifest" build_commit) || return 1
    web_tree=$(web_release_manifest_field "$manifest" web_tree) || return 1
    dist_relative=$(web_release_manifest_field "$manifest" dist_path) || return 1
    dist_sha=$(web_release_manifest_field "$manifest" dist_sha256) || return 1
    index_sha=$(web_release_manifest_field "$manifest" index_sha256) || return 1
    assets_sha=$(web_release_manifest_field "$manifest" assets_sha256) || return 1
    asset_count=$(web_release_manifest_field "$manifest" asset_count) || return 1
    package_lock_sha=$(web_release_manifest_field "$manifest" package_lock_sha256) || return 1
    node_version=$(web_release_manifest_field "$manifest" node_version) || return 1
    npm_version=$(web_release_manifest_field "$manifest" npm_version) || return 1
    build_command=$(web_release_manifest_field "$manifest" build_command) || return 1

    [ "$format" = "$WEB_RELEASE_MANIFEST_FORMAT" ] || return 1
    [ "$success" = "true" ] || return 1
    [[ "$build_commit" =~ ^[0-9a-fA-F]{40}$ ]] || return 1
    [[ "$web_tree" =~ ^[0-9a-fA-F]{40}$ ]] || return 1
    [[ "$dist_sha" =~ ^[0-9a-fA-F]{64}$ ]] || return 1
    [[ "$index_sha" =~ ^[0-9a-fA-F]{64}$ ]] || return 1
    [[ "$assets_sha" =~ ^[0-9a-fA-F]{64}$ ]] || return 1
    [[ "$package_lock_sha" =~ ^[0-9a-fA-F]{64}$ ]] || return 1
    [[ "$asset_count" =~ ^[1-9][0-9]*$ ]] || return 1
    [ "$dist_relative" = "dist" ] || return 1
    [ -n "$node_version" ] && [ -n "$npm_version" ] && [ -n "$build_command" ] || return 1

    current_tree=$(git -C "$repo_root" rev-parse "origin/main:$WEB_RELEASE_SOURCE_PATH" 2>/dev/null) || return 1
    [ "$web_tree" = "$current_tree" ] || return 1
    git -C "$repo_root" cat-file -e "${build_commit}^{commit}" 2>/dev/null || return 1
    built_tree=$(git -C "$repo_root" rev-parse "${build_commit}:$WEB_RELEASE_SOURCE_PATH" 2>/dev/null) || return 1
    [ "$built_tree" = "$current_tree" ] || return 1

    manifest_dir=$(cd "$(dirname "$manifest")" 2>/dev/null && pwd) || return 1
    dist_dir="$manifest_dir/$dist_relative"
    web_release_verify_dist "$dist_dir" || return 1
    actual_index_sha=$(web_release_sha256_file "$dist_dir/index.html") || return 1
    actual_assets_sha=$(web_release_hash_tree "$dist_dir/assets") || return 1
    actual_dist_sha=$(web_release_hash_tree "$dist_dir") || return 1
    actual_asset_count=$(find "$dist_dir/assets" -type f | wc -l | tr -d ' ')
    actual_lock_sha=$(web_release_sha256_file "$repo_root/web-admin/package-lock.json") || return 1
    [ "$actual_index_sha" = "${index_sha,,}" ] || return 1
    [ "$actual_assets_sha" = "${assets_sha,,}" ] || return 1
    [ "$actual_dist_sha" = "${dist_sha,,}" ] || return 1
    [ "$actual_asset_count" = "$asset_count" ] || return 1
    [ "$actual_lock_sha" = "${package_lock_sha,,}" ] || return 1

    WEB_RELEASE_BUILD_COMMIT=$build_commit
    WEB_RELEASE_WEB_TREE=$web_tree
    WEB_RELEASE_DIST_SHA256=$actual_dist_sha
    WEB_RELEASE_INDEX_SHA256=$actual_index_sha
    WEB_RELEASE_ASSETS_SHA256=$actual_assets_sha
    WEB_RELEASE_DIST_DIR=$dist_dir
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
}

web_release_usage() {
    cat <<'EOF'
Usage:
  ./scripts/deploy/release-web-manifest.sh build [--manifest <path>]
  ./scripts/deploy/release-web-manifest.sh validate [--manifest <path>]

Build creates one Vite production dist and stores it with a trusted manifest.
Validation requires a clean HEAD exactly equal to origin/main. A different
build commit is reusable only when its web-admin Git tree is identical.
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
            printf 'Trusted Web dist: %s\n' "$WEB_RELEASE_DIST_DIR"
            ;;
        *) web_release_usage >&2; return 2 ;;
    esac
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    set -euo pipefail
    web_release_main "$@"
fi
