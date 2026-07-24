#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
MANIFEST_HELPER="$ROOT_DIR/scripts/deploy/release-web-manifest.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

# shellcheck source=../deploy/release-web-manifest.sh
source "$MANIFEST_HELPER"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

make_repo() {
    local name=$1
    local repo="$TMP_ROOT/$name/repo"
    local cache="$TMP_ROOT/$name/cache"
    local dist="$TMP_ROOT/$name/candidate-dist"

    mkdir -p "$repo/web-admin" "$dist/assets" "$cache"
    printf '{"name":"fixture","lockfileVersion":3,"packages":{}}\n' > "$repo/web-admin/package-lock.json"
    printf 'export const marker = "one";\n' > "$repo/web-admin/source.ts"
    printf 'outside web tree\n' > "$repo/README.md"
    printf '<link rel="stylesheet" href="/assets/index-test.css"><script type="module" src="/assets/index-test.js"></script>\n' > "$dist/index.html"
    printf 'console.log("fixture")\n' > "$dist/assets/index-test.js"
    printf 'body{}\n' > "$dist/assets/index-test.css"

    git -C "$repo" init -q -b main
    git -C "$repo" config user.email fixture@example.com
    git -C "$repo" config user.name Fixture
    git -C "$repo" add .
    git -C "$repo" commit -qm base
    git -C "$repo" update-ref refs/remotes/origin/main HEAD

    printf '%s|%s|%s\n' "$repo" "$cache/current/$WEB_RELEASE_MANIFEST_NAME" "$dist"
}

write_candidate() {
    local repo=$1 manifest=$2 dist=$3
    web_release_write "$repo" "$dist" "$manifest" "npm run build" \
        || fail "could not write candidate manifest"
}

expect_valid() {
    local manifest=$1 repo=$2 label=$3
    web_release_validate "$manifest" "$repo" || fail "$label should validate"
}

expect_invalid() {
    local manifest=$1 repo=$2 label=$3
    if web_release_validate "$manifest" "$repo"; then
        fail "$label unexpectedly validated"
    fi
}

rewrite_archive_without() {
    local manifest=$1
    local relative_path=$2
    local archive="$(dirname "$manifest")/$WEB_RELEASE_ARCHIVE_NAME"
    local unpack="$TMP_ROOT/rewrite-$RANDOM-$RANDOM"
    local new_hash

    mkdir -p "$unpack"
    tar xzf "$archive" -C "$unpack"
    rm -rf "$unpack/$relative_path"
    tar czf "$archive" -C "$unpack" .
    new_hash=$(web_release_sha256_file "$archive")
    sed -i "s/^archive_sha256=.*/archive_sha256=$new_hash/" "$manifest"
    rm -rf "$unpack"
}

# Valid same-commit reuse.
IFS='|' read -r repo manifest dist < <(make_repo valid)
write_candidate "$repo" "$manifest" "$dist"
expect_valid "$manifest" "$repo" "valid manifest"
[[ "$WEB_RELEASE_ARCHIVE_PATH" = "$(cd "$(dirname "$manifest")" && pwd)/$WEB_RELEASE_ARCHIVE_NAME" ]] || fail "validated archive path not exported"

# Squash/merge-compatible reuse: commit changes, but web-admin tree is identical.
IFS='|' read -r repo manifest dist < <(make_repo squash)
write_candidate "$repo" "$manifest" "$dist"
printf 'merged metadata\n' >> "$repo/README.md"
git -C "$repo" add README.md
git -C "$repo" commit -qm "squash merge shell"
git -C "$repo" update-ref refs/remotes/origin/main HEAD
expect_valid "$manifest" "$repo" "different commit with same web tree"

# Concurrent candidate builds must not evict an older exact Web tree. Build A,
# then B, then return origin/main to A and restore A from the content-addressed
# cache without running another build.
IFS='|' read -r repo manifest dist < <(make_repo tree-cache)
write_candidate "$repo" "$manifest" "$dist"
commit_a=$(git -C "$repo" rev-parse HEAD)
tree_a=$(git -C "$repo" rev-parse HEAD:web-admin)
archive_a=$(web_release_manifest_field "$manifest" archive_sha256)
cached_a="$(dirname "$(dirname "$manifest")")/by-tree/$tree_a/$WEB_RELEASE_MANIFEST_NAME"
[ -f "$cached_a" ] || fail "tree A was not saved in the content-addressed cache"

printf 'export const marker = "two";\n' > "$repo/web-admin/source.ts"
git -C "$repo" add web-admin/source.ts
git -C "$repo" commit -qm "build tree B"
git -C "$repo" update-ref refs/remotes/origin/main HEAD
printf 'console.log("fixture B")\n' > "$dist/assets/index-test.js"
write_candidate "$repo" "$manifest" "$dist"
tree_b=$(git -C "$repo" rev-parse HEAD:web-admin)
[ "$tree_a" != "$tree_b" ] || fail "tree cache fixture did not create a distinct tree"
[ -f "$(dirname "$(dirname "$manifest")")/by-tree/$tree_b/$WEB_RELEASE_MANIFEST_NAME" ] \
    || fail "tree B was not saved in the content-addressed cache"

git -C "$repo" switch --quiet --detach "$commit_a"
git -C "$repo" update-ref refs/remotes/origin/main "$commit_a"
web_release_validate_cached "$manifest" "$repo" \
    || fail "tree A was not restored from the content-addressed cache"
[ "$(web_release_manifest_field "$manifest" web_tree)" = "$tree_a" ] \
    || fail "current manifest was not restored to tree A"
[ "$(web_release_manifest_field "$manifest" archive_sha256)" = "$archive_a" ] \
    || fail "restored tree A archive hash changed"

# A changed web-admin tree must reject reuse.
IFS='|' read -r repo manifest dist < <(make_repo tree-mismatch)
write_candidate "$repo" "$manifest" "$dist"
printf 'export const marker = "two";\n' > "$repo/web-admin/source.ts"
git -C "$repo" add web-admin/source.ts
git -C "$repo" commit -qm "change web tree"
git -C "$repo" update-ref refs/remotes/origin/main HEAD
expect_invalid "$manifest" "$repo" "web tree mismatch"

# Any cached archive mutation must fail immutable archive validation.
IFS='|' read -r repo manifest dist < <(make_repo hash-mismatch)
write_candidate "$repo" "$manifest" "$dist"
printf 'tampered\n' >> "$(dirname "$manifest")/$WEB_RELEASE_ARCHIVE_NAME"
expect_invalid "$manifest" "$repo" "archive hash mismatch"

# Dirty exact-main worktrees are never trusted.
IFS='|' read -r repo manifest dist < <(make_repo dirty)
write_candidate "$repo" "$manifest" "$dist"
printf 'dirty\n' >> "$repo/README.md"
expect_invalid "$manifest" "$repo" "dirty worktree"

# Missing manifest preserves fallback behavior.
IFS='|' read -r repo manifest dist < <(make_repo missing-manifest)
expect_invalid "$manifest" "$repo" "missing manifest"

# Structurally invalid archives are rejected even when their archive SHA field
# is refreshed to match the changed bytes.
IFS='|' read -r repo manifest dist < <(make_repo missing-index)
write_candidate "$repo" "$manifest" "$dist"
rewrite_archive_without "$manifest" "index.html"
expect_invalid "$manifest" "$repo" "missing index"

IFS='|' read -r repo manifest dist < <(make_repo missing-assets)
write_candidate "$repo" "$manifest" "$dist"
rewrite_archive_without "$manifest" "assets"
expect_invalid "$manifest" "$repo" "missing assets"

# An index that references a missing chunk fails even if another asset remains.
IFS='|' read -r repo manifest dist < <(make_repo broken-reference)
write_candidate "$repo" "$manifest" "$dist"
rewrite_archive_without "$manifest" "assets/index-test.js"
expect_invalid "$manifest" "$repo" "missing referenced asset"

if grep -q 'web_release_hash_tree' "$MANIFEST_HELPER"; then
    fail "slow per-file hash tree helper was reintroduced"
fi

echo "PASS: Web archive manifest validates provenance, same-tree squash reuse, one-file hash, clean state, and archive integrity"
