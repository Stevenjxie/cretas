#!/usr/bin/env bash
# 预热短路的真值测试。
#
# 这条短路危险在于: 它的作用是【跳过一整段跨境运输 + 验签】。判松了就会把一份不适用的
# 描述符当成"预热成功", 然后在部署期晚失败(或者更糟, 部署一份内容不对的 jar)。
# 所以每一个否定条件都要有一条独立用例, 不能只测正路径。
#
# 用真临时 git 仓库 + 真的选择器展开; 只 stub 掉出网的三个命令(gh / pwsh / ssh)。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SRC="$ROOT_DIR/scripts/deploy/release-ci-artifact.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

pass_count=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass_count=$((pass_count + 1)); echo "  ok - $*"; }

bash -n "$SRC" || fail "语法错误"

REPO_DIR="$TMP_ROOT/repo"
mkdir -p "$REPO_DIR/scripts/deploy" "$REPO_DIR/scripts/lib" "$TMP_ROOT/bin" \
         "$REPO_DIR/backend/java/cretas-api/src/test/java/x"
cp "$SRC" "$REPO_DIR/scripts/deploy/"
cp "$ROOT_DIR/scripts/deploy/release-jar-manifest.sh" "$REPO_DIR/scripts/deploy/"
: > "$REPO_DIR/scripts/deploy/Publish-GitHubArtifactViaLightsailOss.ps1"

# 两个真实存在的测试类, 让 expand_test_selector 有东西可展开。
: > "$REPO_DIR/backend/java/cretas-api/src/test/java/x/AlphaRepositoryQueryValidationTest.java"
: > "$REPO_DIR/backend/java/cretas-api/src/test/java/x/BetaRepositoryQueryValidationTest.java"

(
    cd "$REPO_DIR"
    git init -q .
    git config user.email t@example.com
    git config user.name t
    git add -A
    git commit -qm base
    git update-ref refs/remotes/origin/main HEAD
) || fail "临时仓库准备失败"

TREE=$(git -C "$REPO_DIR" rev-parse "origin/main:backend/java/cretas-api")
[ -n "$TREE" ] || fail "拿不到 backend_tree"

# ---- stubs ----
cat > "$TMP_ROOT/bin/gh" <<'STUB'
#!/usr/bin/env bash
# 制品列表恒为空 —— 保证「走到这里」等于「短路没命中」, 用例才分得清。
echo "GH_CALLED" >> "${STUB_LOG:-/dev/null}"
exit 0
STUB
cat > "$TMP_ROOT/bin/pwsh" <<'STUB'
#!/usr/bin/env bash
exit 1
STUB
cat > "$TMP_ROOT/bin/ssh" <<'STUB'
#!/usr/bin/env bash
echo "SSH_CALLED" >> "${STUB_LOG:-/dev/null}"
exit "${STUB_SSH_RC:-0}"
STUB
chmod +x "$TMP_ROOT/bin/"*

DESC="$TMP_ROOT/desc.remote"
write_descriptor() {
    cat > "$DESC" <<EOF
format=${1:-cretas-remote-artifact-v1}
build_commit=$(git -C "$REPO_DIR" rev-parse HEAD)
backend_tree=${2:-$TREE}
jar_sha256=${3:-$(printf 'a%.0s' {1..64})}
jar_md5=$(printf 'b%.0s' {1..32})
jar_size_bytes=176289596
target_tests=${4:-*RepositoryQueryValidationTest}
attested=${5:-true}
staged_cache_path=/www/wwwroot/cretas/release-cache/sha256/x.jar
artifact_id=123
EOF
}

run() {
    # $1=期望退出码, $2=用例名, 其余透传给脚本
    local want=$1 name=$2; shift 2
    STUB_LOG="$TMP_ROOT/stub.log" : > "$TMP_ROOT/stub.log"
    set +e
    STUB_LOG="$TMP_ROOT/stub.log" STUB_SSH_RC="${STUB_SSH_RC:-0}" \
    PATH="$TMP_ROOT/bin:$PATH" \
        bash "$REPO_DIR/scripts/deploy/release-ci-artifact.sh" \
            --descriptor "$DESC" "$@" > "$TMP_ROOT/out.log" 2>&1
    local rc=$?
    set -e
    [ "$rc" = "$want" ] || { cat "$TMP_ROOT/out.log" >&2; fail "$name: 期望 exit=$want 实得 $rc"; }
}

# ---- 1. 正路径: 描述符完整 + ECS 说文件在 → 短路命中, 且【没有】调用 gh ----
write_descriptor
STUB_SSH_RC=0 run 0 "正路径" --tests 'AlphaRepositoryQueryValidationTest'
grep -q 'CI_ARTIFACT_PREWARM_HIT' "$TMP_ROOT/out.log" || fail "正路径: 没打 PREWARM_HIT"
grep -q 'CI_ARTIFACT_READY' "$TMP_ROOT/out.log" || fail "正路径: 没打 READY(调用方靠它认)"
grep -q 'GH_CALLED' "$TMP_ROOT/stub.log" && fail "正路径: 短路后仍调了 gh —— 那 3.6s 没省掉"
ok "命中时跳过整段运输, 且不再调 gh"

# ---- 2..5 每个否定条件都必须【独立】挡住 ----
# 挡住 = 落到候选搜索(stub gh 返回空) → 非预热模式下 exit 1。
write_descriptor cretas-remote-artifact-v1 "$(printf 'c%.0s' {1..40})"
STUB_SSH_RC=0 run 1 "树不匹配" --tests 'AlphaRepositoryQueryValidationTest'
grep -q 'GH_CALLED' "$TMP_ROOT/stub.log" || fail "树不匹配: 没有落到候选搜索"
ok "backend_tree 不匹配 → 不短路"

write_descriptor cretas-remote-artifact-v1 "$TREE" "" "" false
STUB_SSH_RC=0 run 1 "未验签" --tests 'AlphaRepositoryQueryValidationTest'
ok "attested=false → 不短路"

write_descriptor cretas-remote-artifact-v1 "$TREE" "" 'AlphaRepositoryQueryValidationTest'
STUB_SSH_RC=0 run 1 "选择器未覆盖" --tests 'BetaRepositoryQueryValidationTest'
ok "要求 ⊄ 描述符记录的 CI 选择器 → 不短路"

write_descriptor wrong-format
STUB_SSH_RC=0 run 1 "格式不对" --tests 'AlphaRepositoryQueryValidationTest'
ok "format 不对 → 不短路"

write_descriptor
STUB_SSH_RC=1 run 1 "ECS 上没有" --tests 'AlphaRepositoryQueryValidationTest'
grep -q 'SSH_CALLED' "$TMP_ROOT/stub.log" || fail "ECS 用例: 根本没问服务器"
ok "ECS 缓存已被清理 → 不短路(而不是给一个必然晚失败的假命中)"

# ---- 6. --prewarm 语义: 还没有制品不算失败, 但只对"没有制品"放宽 ----
rm -f "$DESC"
STUB_SSH_RC=0 run 0 "prewarm 待定" --tests 'AlphaRepositoryQueryValidationTest' --prewarm
grep -q 'CI_ARTIFACT_PREWARM=pending' "$TMP_ROOT/out.log" || fail "prewarm: 没打 pending"
ok "--prewarm 且尚无制品 → 退 0 并打 pending"

STUB_SSH_RC=0 run 1 "非 prewarm 无制品" --tests 'AlphaRepositoryQueryValidationTest'
grep -q 'CI_ARTIFACT_UNAVAILABLE' "$TMP_ROOT/out.log" || fail "非 prewarm: 没打 UNAVAILABLE"
ok "不带 --prewarm 且尚无制品 → 仍是硬失败(语义没被放宽)"

# ---- 7. 结构: 短路必须排在 gh api 之前 ----
sc=$(grep -n 'CI_ARTIFACT_PREWARM_HIT' "$SRC" | head -1 | cut -d: -f1)
gh_line=$(grep -n 'gh api' "$SRC" | head -1 | cut -d: -f1)
[ -n "$sc" ] && [ -n "$gh_line" ] && [ "$sc" -lt "$gh_line" ] \
    || fail "短路(行 ${sc:-?})必须排在 gh api(行 ${gh_line:-?})之前, 否则白付 API 往返"
ok "短路排在 gh api 之前 ($sc < $gh_line)"

echo "PASS: ${pass_count} 项断言全部通过"
