#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/test-helpers.sh"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
BIN="$(mktemp -d)"
trap 'rm -rf "$TMP" "$BIN"' EXIT

git -C "$TMP" init -q
git -C "$TMP" config user.email test@example.invalid
git -C "$TMP" config user.name test
printf 'base\n' > "$TMP/README.md"
git -C "$TMP" add README.md
git -C "$TMP" commit -qm base
git -C "$TMP" branch -M main
git -C "$TMP" remote add origin https://github.com/example/test.git

git -C "$TMP" branch merged-fix
git -C "$TMP" worktree add -q "$TMP/merged" merged-fix

git -C "$TMP" switch -qc feature-source
printf 'feature\n' > "$TMP/feature.txt"
git -C "$TMP" add feature.txt
git -C "$TMP" commit -qm feature
FEATURE_COMMIT="$(git -C "$TMP" rev-parse HEAD)"
git -C "$TMP" switch -q main
printf 'main divergence\n' > "$TMP/main.txt"
git -C "$TMP" add main.txt
git -C "$TMP" commit -qm main-divergence
git -C "$TMP" cherry-pick "$FEATURE_COMMIT" >/dev/null
git -C "$TMP" worktree add -q "$TMP/equivalent" feature-source

git -C "$TMP" branch pr-merged main~1
git -C "$TMP" worktree add -q "$TMP/pr-merged" pr-merged
printf 'pr-only\n' > "$TMP/pr-merged/pr.txt"
git -C "$TMP/pr-merged" add pr.txt
git -C "$TMP/pr-merged" commit -qm pr-only
printf 'pr-only-2\n' > "$TMP/pr-merged/pr2.txt"
git -C "$TMP/pr-merged" add pr2.txt
git -C "$TMP/pr-merged" commit -qm pr-only-2

cat > "$BIN/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' pr-merged
EOF
chmod +x "$BIN/gh"

REPORT="$(PATH="$BIN:$PATH" bash "$ROOT/scripts/maintenance/worktree-report.sh" --repo "$TMP" --base main)"
assert_contains "$REPORT" 'BRANCH=merged-fix'
assert_contains "$REPORT" 'MAIN=merged'
assert_contains "$REPORT" 'BRANCH=feature-source'
assert_contains "$REPORT" 'MAIN=patch-equivalent'
assert_contains "$REPORT" 'BRANCH=pr-merged'
assert_contains "$REPORT" 'MAIN=pr-merged'
assert_contains "$REPORT" 'ACTION=cleanup-candidate'
assert_contains "$REPORT" 'LAST_COMMIT_TIME='

printf 'dirty\n' > "$TMP/merged/dirty.txt"
DIRTY_REPORT="$(PATH="$BIN:$PATH" bash "$ROOT/scripts/maintenance/worktree-report.sh" --repo "$TMP" --base main)"
assert_contains "$DIRTY_REPORT" 'BRANCH=merged-fix'
assert_contains "$DIRTY_REPORT" 'STATE=dirty'
assert_exit 2 bash "$ROOT/scripts/maintenance/worktree-report.sh" --repo "$TMP" --base missing
