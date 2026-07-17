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

# Valid same-commit reuse.
IFS='|' read -r repo manifest dist < <(make_repo valid)
write_candidate "$repo" "$manifest" "$dist"
expect_valid "$manifest" "$repo" "valid manifest"
[[ "$WEB_RELEASE_DIST_DIR" = "$(cd "$(dirname "$manifest")/dist" && pwd)" ]] || fail "validated dist path not exported"

# Squash/merge-compatible reuse: commit changes, but web-admin tree is identical.
IFS='|' read -r repo manifest dist < <(make_repo squash)
write_candidate "$repo" "$manifest" "$dist"
printf 'merged metadata\n' >> "$repo/README.md"
git -C "$repo" add README.md
git -C "$repo" commit -qm "squash merge shell"
git -C "$repo" update-ref refs/remotes/origin/main HEAD
expect_valid "$manifest" "$repo" "different commit with same web tree"

# A changed web-admin tree must reject reuse.
IFS='|' read -r repo manifest dist < <(make_repo tree-mismatch)
write_candidate "$repo" "$manifest" "$dist"
printf 'export const marker = "two";\n' > "$repo/web-admin/source.ts"
git -C "$repo" add web-admin/source.ts
git -C "$repo" commit -qm "change web tree"
git -C "$repo" update-ref refs/remotes/origin/main HEAD
expect_invalid "$manifest" "$repo" "web tree mismatch"

# Any cached asset mutation must fail all-dist integrity validation.
IFS='|' read -r repo manifest dist < <(make_repo hash-mismatch)
write_candidate "$repo" "$manifest" "$dist"
printf 'tampered\n' >> "$(dirname "$manifest")/dist/assets/index-test.js"
expect_invalid "$manifest" "$repo" "asset hash mismatch"

# Dirty exact-main worktrees are never trusted.
IFS='|' read -r repo manifest dist < <(make_repo dirty)
write_candidate "$repo" "$manifest" "$dist"
printf 'dirty\n' >> "$repo/README.md"
expect_invalid "$manifest" "$repo" "dirty worktree"

# Missing manifest preserves fallback behavior.
IFS='|' read -r repo manifest dist < <(make_repo missing-manifest)
expect_invalid "$manifest" "$repo" "missing manifest"

# Missing/corrupt index and missing assets are independently rejected.
IFS='|' read -r repo manifest dist < <(make_repo missing-index)
write_candidate "$repo" "$manifest" "$dist"
rm -f "$(dirname "$manifest")/dist/index.html"
expect_invalid "$manifest" "$repo" "missing index"

IFS='|' read -r repo manifest dist < <(make_repo missing-assets)
write_candidate "$repo" "$manifest" "$dist"
rm -rf "$(dirname "$manifest")/dist/assets"
expect_invalid "$manifest" "$repo" "missing assets"

# An index that references a missing chunk fails even if another asset remains.
IFS='|' read -r repo manifest dist < <(make_repo broken-reference)
write_candidate "$repo" "$manifest" "$dist"
rm -f "$(dirname "$manifest")/dist/assets/index-test.js"
expect_invalid "$manifest" "$repo" "missing referenced asset"

echo "PASS: Web manifest validates provenance, same-tree squash reuse, hashes, clean state, and dist integrity"
