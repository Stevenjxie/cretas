#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
FASTLANE="$ROOT_DIR/scripts/deploy/publish-main-fastlane.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

create_fixture() {
    local name="$1"
    local remote="$TMP_ROOT/$name-remote.git"
    local repo="$TMP_ROOT/$name"

    git init --bare --quiet "$remote"
    git init --quiet -b main "$repo"
    git -C "$repo" config user.name "Fastlane Test"
    git -C "$repo" config user.email "fastlane@example.test"
    mkdir -p "$repo/scripts/deploy" "$repo/docs/dispatch" "$repo/web-admin/src"
    cp "$FASTLANE" "$repo/scripts/deploy/publish-main-fastlane.sh"
    cat > "$repo/scripts/deploy/release-preflight.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
git diff --check
echo "fixture preflight passed $*"
EOF
    chmod +x "$repo/scripts/deploy/"*.sh
    printf '# ACTIVE\n\n## In Flight\n\n- None.\n\n## Scope Locks\n\n- None.\n' \
        > "$repo/docs/dispatch/ACTIVE.md"
    printf 'base\n' > "$repo/web-admin/src/app.ts"
    git -C "$repo" add .
    git -C "$repo" commit --quiet -m base
    git -C "$repo" remote add origin "$remote"
    git -C "$repo" push --quiet -u origin main
    git -C "$repo" switch --quiet -c codex/fixture
    printf 'feature\n' >> "$repo/web-admin/src/app.ts"
    git -C "$repo" add web-admin/src/app.ts
    git -C "$repo" commit --quiet -m feature
    printf '%s\n' "$repo"
}

run_fastlane() {
    local repo="$1"
    shift
    (cd "$repo" && bash scripts/deploy/publish-main-fastlane.sh "$@")
}

success_repo=$(create_fixture success)
success_base=$(git -C "$success_repo" rev-parse origin/main)
success_head=$(git -C "$success_repo" rev-parse HEAD)
run_fastlane "$success_repo" --base-sha "$success_base" --confirm YES-DIRECT-MAIN \
    > "$TMP_ROOT/success.log"
[[ "$(git --git-dir="$TMP_ROOT/success-remote.git" rev-parse main)" == "$success_head" ]] \
    || fail "valid fastlane did not fast-forward main"
grep -Fq "FASTLANE_PUBLISHED main=$success_head" "$TMP_ROOT/success.log" \
    || fail "success receipt missing"

confirm_repo=$(create_fixture confirm)
confirm_base=$(git -C "$confirm_repo" rev-parse origin/main)
if run_fastlane "$confirm_repo" --base-sha "$confirm_base" --confirm NO >/dev/null 2>&1; then
    fail "invalid confirmation was accepted"
fi

dirty_repo=$(create_fixture dirty)
dirty_base=$(git -C "$dirty_repo" rev-parse origin/main)
printf 'dirty\n' > "$dirty_repo/untracked.txt"
if run_fastlane "$dirty_repo" --base-sha "$dirty_base" --confirm YES-DIRECT-MAIN >/dev/null 2>&1; then
    fail "dirty worktree was accepted"
fi

active_repo=$(create_fixture active)
active_base=$(git -C "$active_repo" rev-parse origin/main)
perl -0pi -e 's/- None\./- `TASK` - `review`/' "$active_repo/docs/dispatch/ACTIVE.md"
git -C "$active_repo" add docs/dispatch/ACTIVE.md
git -C "$active_repo" commit --quiet -m "leave task active"
if run_fastlane "$active_repo" --base-sha "$active_base" --confirm YES-DIRECT-MAIN >/dev/null 2>&1; then
    fail "unfinished ACTIVE task was accepted"
fi

# Scoped ACTIVE gate. The real ledger permanently carries dozens of unrelated
# in-flight tasks from other sessions, so the unscoped gate rejects every
# non-docs publish no matter how clean your own batch is. --task-id narrows the
# check to the caller's own row.
scoped_repo=$(create_fixture scoped)
scoped_base=$(git -C "$scoped_repo" rev-parse origin/main)
scoped_head=$(git -C "$scoped_repo" rev-parse HEAD)
printf '# ACTIVE\n\n## In Flight\n\n- `OTHER-TASK-1` - `in-progress` - someone else\n- `OTHER-TASK-2` - `review` - someone else\n\n## Scope Locks\n\n- None.\n' \
    > "$scoped_repo/docs/dispatch/ACTIVE.md"
git -C "$scoped_repo" add docs/dispatch/ACTIVE.md
git -C "$scoped_repo" commit --quiet -m "unrelated tasks in flight"
scoped_head=$(git -C "$scoped_repo" rev-parse HEAD)
if run_fastlane "$scoped_repo" --base-sha "$scoped_base" --confirm YES-DIRECT-MAIN >/dev/null 2>&1; then
    fail "unscoped gate accepted a ledger with unfinished tasks"
fi
run_fastlane "$scoped_repo" --base-sha "$scoped_base" --confirm YES-DIRECT-MAIN \
    --task-id MY-TASK-42 > "$TMP_ROOT/scoped.log"
[[ "$(git --git-dir="$TMP_ROOT/scoped-remote.git" rev-parse main)" == "$scoped_head" ]] \
    || fail "--task-id did not publish while unrelated tasks were still in flight"

# ...but the caller's OWN unfinished row must still block.
mine_repo=$(create_fixture mine)
mine_base=$(git -C "$mine_repo" rev-parse origin/main)
printf '# ACTIVE\n\n## In Flight\n\n- `OTHER-TASK-1` - `in-progress` - someone else\n- `MY-TASK-42` - `review` - mine\n\n## Scope Locks\n\n- None.\n' \
    > "$mine_repo/docs/dispatch/ACTIVE.md"
git -C "$mine_repo" add docs/dispatch/ACTIVE.md
git -C "$mine_repo" commit --quiet -m "my task still active"
if run_fastlane "$mine_repo" --base-sha "$mine_base" --confirm YES-DIRECT-MAIN \
    --task-id MY-TASK-42 >/dev/null 2>&1; then
    fail "--task-id accepted a publish while the caller's own task was unfinished"
fi

stale_repo=$(create_fixture stale)
stale_base=$(git -C "$stale_repo" rev-parse origin/main)
git -C "$stale_repo" push --quiet origin HEAD:refs/heads/concurrent-advance
git --git-dir="$TMP_ROOT/stale-remote.git" update-ref refs/heads/main \
    "$(git --git-dir="$TMP_ROOT/stale-remote.git" rev-parse concurrent-advance)"
if run_fastlane "$stale_repo" --base-sha "$stale_base" --confirm YES-DIRECT-MAIN >/dev/null 2>&1; then
    fail "stale registered base was accepted"
fi

risk_repo=$(create_fixture risk)
risk_base=$(git -C "$risk_repo" rev-parse origin/main)
printf '# policy\n' > "$risk_repo/AGENTS.md"
git -C "$risk_repo" add AGENTS.md
git -C "$risk_repo" commit --quiet -m policy
if run_fastlane "$risk_repo" --base-sha "$risk_base" --confirm YES-DIRECT-MAIN >/dev/null 2>&1; then
    fail "high-risk change was accepted without explicit review override"
fi
run_fastlane "$risk_repo" --base-sha "$risk_base" --confirm YES-DIRECT-MAIN \
    --allow-high-risk YES-HIGH-RISK-REVIEWED > "$TMP_ROOT/risk.log"

dry_repo=$(create_fixture dry)
dry_base=$(git -C "$dry_repo" rev-parse origin/main)
dry_remote_before=$(git --git-dir="$TMP_ROOT/dry-remote.git" rev-parse main)
run_fastlane "$dry_repo" --base-sha "$dry_base" --confirm YES-DIRECT-MAIN --dry-run \
    > "$TMP_ROOT/dry.log"
[[ "$(git --git-dir="$TMP_ROOT/dry-remote.git" rev-parse main)" == "$dry_remote_before" ]] \
    || fail "dry-run changed remote main"
grep -Fq 'FASTLANE_DRY_RUN: git push origin HEAD:refs/heads/main' "$TMP_ROOT/dry.log" \
    || fail "dry-run receipt missing"

crlf_repo=$(create_fixture crlf)
crlf_base=$(git -C "$crlf_repo" rev-parse origin/main)
printf 'first\r\nsecond\r\n' > "$crlf_repo/web-admin/src/crlf.ts"
git -C "$crlf_repo" add web-admin/src/crlf.ts
git -C "$crlf_repo" commit --quiet -m "add CRLF source"
crlf_head=$(git -C "$crlf_repo" rev-parse HEAD)
run_fastlane "$crlf_repo" --base-sha "$crlf_base" --confirm YES-DIRECT-MAIN \
    > "$TMP_ROOT/crlf.log"
[[ "$(git --git-dir="$TMP_ROOT/crlf-remote.git" rev-parse main)" == "$crlf_head" ]] \
    || fail "valid CRLF commit did not fast-forward main"

if grep -Ev '^\s*#' "$FASTLANE" | grep -Eq 'git push.*(--force|--force-with-lease)'; then
    fail "fastlane script contains a force-push path"
fi

echo "PASS: direct-main fastlane guards confirmation, cleanliness, ACTIVE, stale base, risk, CRLF, and no-force push"
