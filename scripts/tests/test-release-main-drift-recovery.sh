#!/usr/bin/env bash
# origin/main 在发布期间被并发 session 推进时, 原来是硬失败 + 人工重跑。因为
# backend tree 没变时重建几乎免费 (实测 167s→2s 命中 JAR 复用), 那次人工往返
# 信息量很低、摩擦很高, 现在改为在严格前提下自动前进到新 main 并重新执行。
#
# 这个测试守的是【前提不被放宽】: 自动前进只有在 deploy 阶段、worktree 干净、
# 本次 HEAD 确实已进新 main、且重试未超预算时才允许。任何一条松了, 都可能静默
# 丢掉本次要发布的提交, 或者和高频推送方无限互追。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
ORCHESTRATOR="$ROOT_DIR/scripts/deploy/release-cretas.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

body=$(awk '/^recover_from_main_drift\(\) \{/,/^\}/' "$ORCHESTRATOR")
[ -n "$body" ] || fail "recover_from_main_drift is gone"

# --- 前提 1: build 阶段不参与 (它本来就允许在 feature 分支上跑) ---
grep -Fq '[ "$PHASE" != build ] || return 1' <<<"$body" \
    || fail "drift recovery no longer excludes the build phase"

# --- 前提 2: worktree 必须干净 ---
grep -Fq '[ -z "$dirty" ] || return 1' <<<"$body" \
    || fail "drift recovery can now advance a dirty worktree"

# --- 前提 3: 本次 HEAD 必须已在新 main 里 (最关键的一条) ---
# 少了它, 自动前进会把本次要发布的提交静默丢掉 —— 部署"成功"但发的是别人的东西。
grep -Fq 'merge-base --is-ancestor "$HEAD_SHA" "$origin_sha"' <<<"$body" \
    || fail "drift recovery no longer proves the release commits are in the new main"

# --- 前提 4: 重试有界 ---
grep -Fq '[ "$DRIFT_ATTEMPT" -lt "$DRIFT_RETRY_BUDGET" ]' <<<"$body" \
    || fail "drift recovery is no longer bounded; it can chase a busy pusher forever"
grep -Fq 'CRETAS_RELEASE_DRIFT_ATTEMPT=$((DRIFT_ATTEMPT + 1))' <<<"$body" \
    || fail "the retry counter is not incremented across the re-exec"

# --- 前进动作本身必须是非破坏性的 ---
# reset --hard 会丢掉工作区内容; 干净 worktree 上 checkout --detach 足够且安全。
grep -Fq 'checkout --detach' <<<"$body" \
    || fail "drift recovery does not use a non-destructive checkout"
if grep -Eq 'reset --hard|clean -[a-z]*f' <<<"$body"; then
    fail "drift recovery introduced a destructive git operation"
fi

# --- 失败时必须仍然走原来的硬失败路径 ---
guard=$(awk '/^ensure_exact_main_after_artifacts\(\) \{/,/^\}/' "$ORCHESTRATOR")
grep -Fq 'MAIN_GUARD_STATUS=failed' <<<"$guard" \
    || fail "the guard no longer fails when recovery is not possible"
recover_line=$(grep -Fn 'recover_from_main_drift "$label"' <<<"$guard" | cut -d: -f1 || true)
failed_line=$(grep -Fn 'origin/main moved during $label' <<<"$guard" | cut -d: -f1 || true)
[ -n "$recover_line" ] && [ -n "$failed_line" ] \
    || fail "recovery and the hard-failure path are not both present in the guard"
[ "$recover_line" -lt "$failed_line" ] \
    || fail "recovery is attempted after the guard already failed"

# --- 回执必须记录漂移次数, 否则事后无法判断这次发布经历过什么 ---
grep -Fq '"drift_recoveries"' "$ORCHESTRATOR" \
    || fail "the receipt no longer records how many drift recoveries happened"

# ------------------------------------------------- behavioural: ancestry gate
# 前提 3 的实际语义: 用真实 git 仓库验证"我的提交在不在新 main 里"这个判断本身。
repo="$TMP_ROOT/repo"
mkdir -p "$repo"
(
    cd "$repo"
    git init -q -b main
    git config user.name t
    git config user.email t@e.com
    printf 'base\n' > f.txt && git add f.txt && git commit -qm base
    printf 'mine\n' >> f.txt && git add f.txt && git commit -qm mine
)
mine=$(git -C "$repo" rev-parse HEAD)

# 别的 session 在我之上又推了一个 commit —— 我的提交仍在新 main 里 → 允许前进。
(cd "$repo" && printf 'theirs\n' >> f.txt && git add f.txt && git commit -qm theirs)
advanced=$(git -C "$repo" rev-parse HEAD)
git -C "$repo" merge-base --is-ancestor "$mine" "$advanced" \
    || fail "ancestry check rejected a main that legitimately contains the release commits"

# 别的 session 从我之前的点分叉推了 main —— 我的提交【不在】新 main 里 → 必须拒绝。
(
    cd "$repo"
    git checkout -q -b sidetrack "$mine~1"
    printf 'divergent\n' >> f.txt && git add f.txt && git commit -qm divergent
)
divergent=$(git -C "$repo" rev-parse HEAD)
if git -C "$repo" merge-base --is-ancestor "$mine" "$divergent"; then
    fail "ancestry check accepted a main that does NOT contain the release commits"
fi

echo "PASS: main drift auto-recovery preconditions and ancestry gate"
