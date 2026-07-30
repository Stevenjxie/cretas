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

# ---- 构建期复用 (web_release_build_reusable) ----
# Java 侧一直有 release_manifest_build_reusable, Web 侧没有 —— 于是每次构建都无条件
# npm ci + vite build。实测那是 86s (npm ci 20s + vite build 66s) 而产出逐字节相同。
IFS='|' read -r repo manifest dist < <(make_repo build-reuse)
write_candidate "$repo" "$manifest" "$dist"
WEB_RELEASE_REUSED_TREE=
web_release_build_reusable "$repo" "$manifest" \
    || fail "same web tree should be reusable at build time"
[ -n "$WEB_RELEASE_REUSED_TREE" ] || fail "build reuse did not export the reused tree"
[ "$WEB_RELEASE_REUSED_TREE" = "$(git -C "$repo" rev-parse "HEAD:$WEB_RELEASE_SOURCE_PATH")" ] \
    || fail "build reuse exported the wrong tree"
# 复用完成后 current/ 必须仍然是一份可通过校验的制品 —— 否则构建"成功"了但发布下一步会挂,
# 而那种失败会出现在离构建很远的地方。
expect_valid "$manifest" "$repo" "reused-at-build-time manifest"

# 🔴 锚点差异必须钉住: 构建期复用锚 HEAD, 部署期的 web_release_validate_cached 锚 origin/main。
# 构建阶段合法地跑在已 review 的候选分支上, 那时 origin/main 还没前进 —— 锚错了会让复用永远
# 不命中(或者更糟, 命中别的树)。这里把 origin/main 停在旧 commit, HEAD 前进但 web 树不变,
# 复用仍应命中。
git -C "$repo" update-ref refs/remotes/origin/main "$(git -C "$repo" rev-parse HEAD)"
printf 'backend only\n' >> "$repo/README.md"
git -C "$repo" add README.md
git -C "$repo" commit -qm "commit outside web tree"
web_release_build_reusable "$repo" "$manifest" \
    || fail "build reuse must anchor on HEAD, not origin/main"

# web 树真的变了 → 必须不命中(否则会拿旧 dist 当新代码发出去)
printf 'export const marker = "two";\n' > "$repo/web-admin/source.ts"
git -C "$repo" add web-admin/source.ts
git -C "$repo" commit -qm "change web tree"
if web_release_build_reusable "$repo" "$manifest"; then
    fail "changed web tree must not be reusable"
fi

# archive 被改一个字节 → sha256 不符必须拒绝
IFS='|' read -r repo manifest dist < <(make_repo build-reuse-tamper)
write_candidate "$repo" "$manifest" "$dist"
tree=$(git -C "$repo" rev-parse "HEAD:$WEB_RELEASE_SOURCE_PATH")
cached_dir="$(dirname "$(dirname "$manifest")")/by-tree/$tree"
printf 'X' | dd of="$cached_dir/$WEB_RELEASE_ARCHIVE_NAME" bs=1 seek=100 conv=notrunc status=none 2>/dev/null
if web_release_build_reusable "$repo" "$manifest"; then
    fail "tampered cached archive must not be reusable"
fi

# success=false → 必须拒绝
IFS='|' read -r repo manifest dist < <(make_repo build-reuse-failed)
write_candidate "$repo" "$manifest" "$dist"
tree=$(git -C "$repo" rev-parse "HEAD:$WEB_RELEASE_SOURCE_PATH")
cached_dir="$(dirname "$(dirname "$manifest")")/by-tree/$tree"
sed -i 's/^success=true$/success=false/' "$cached_dir/$WEB_RELEASE_MANIFEST_NAME"
if web_release_build_reusable "$repo" "$manifest"; then
    fail "manifest with success=false must not be reusable"
fi

# 接线断言: build 必须在 ensure_dependencies 之前查复用, 否则 npm ci 已经付掉了
BUILD_FN=$(awk '/^web_release_build\(\) \{/,/^\}/' "$MANIFEST_HELPER")
reuse_line=$(printf '%s\n' "$BUILD_FN" | grep -n 'web_release_build_reusable' | head -1 | cut -d: -f1)
deps_line=$(printf '%s\n' "$BUILD_FN" | grep -n 'web_release_ensure_dependencies' | head -1 | cut -d: -f1)
[ -n "$reuse_line" ] || fail "web_release_build does not consult the build-time reuse check"
[ -n "$deps_line" ] || fail "could not locate ensure_dependencies in web_release_build"
[ "$reuse_line" -lt "$deps_line" ] \
    || fail "reuse check runs after ensure_dependencies; npm ci would already be paid"
# 逃生开关与 Java 侧对齐
grep -Fq 'CRETAS_RELEASE_FORCE_WEB_BUILD' "$MANIFEST_HELPER" \
    || fail "missing CRETAS_RELEASE_FORCE_WEB_BUILD escape hatch"

echo "PASS: Web archive manifest validates provenance, same-tree squash reuse, build-time reuse, one-file hash, clean state, and archive integrity"
