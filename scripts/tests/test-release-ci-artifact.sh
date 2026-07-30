#!/usr/bin/env bash
# CI 制品优先路径的测试。
#
# 两部分:
#   1) 描述符加载器的真值测试 —— 用一个真的临时 git 仓库, 让两跳 backend_tree 校验真的在跑,
#      而不是打桩 git 之后测一个自己编的返回值。
#   2) source-contract 断言 —— 那些"跨境 + 需要服务器"因而 CI 里跑不了的接线点, 用 grep 钉住。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
RELEASE_SCRIPT="$ROOT_DIR/scripts/deploy/release-cretas.sh"
CI_SCRIPT="$ROOT_DIR/scripts/deploy/release-ci-artifact.sh"
VERIFY_SCRIPT="$ROOT_DIR/scripts/deploy/ecs/oss-verify-artifact.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
contains() { grep -Fq -- "$2" "$1" || fail "missing '$2' in $(basename "$1")"; }

for f in "$DEPLOY_SCRIPT" "$RELEASE_SCRIPT" "$CI_SCRIPT" "$VERIFY_SCRIPT"; do
    bash -n "$f" || fail "syntax error in $f"
done
[ -x "$CI_SCRIPT" ] || fail "release-ci-artifact.sh 不可执行"

# ---------------------------------------------------------------- 1. 加载器 --
# shellcheck source=scripts/deploy/release-jar-manifest.sh
. "$ROOT_DIR/scripts/deploy/release-jar-manifest.sh"

HELPERS=$(awk '
    /^# BEGIN_REMOTE_ARTIFACT_DESCRIPTOR_HELPERS$/ {copy = 1; next}
    /^# END_REMOTE_ARTIFACT_DESCRIPTOR_HELPERS$/ {exit}
    copy {print}
' "$DEPLOY_SCRIPT")
[ -n "$HELPERS" ] || fail "找不到 REMOTE_ARTIFACT_DESCRIPTOR_HELPERS 区块"
eval "$HELPERS"

PROJECT_ROOT="$TMP_ROOT/repo"
mkdir -p "$PROJECT_ROOT/backend/java/cretas-api"
(
    cd "$PROJECT_ROOT"
    git init -q .
    git config user.email t@example.com
    git config user.name t
    echo pom > backend/java/cretas-api/pom.xml
    git add -A
    git commit -qm base
    git update-ref refs/remotes/origin/main HEAD
) || fail "临时仓库准备失败"

GOOD_COMMIT=$(git -C "$PROJECT_ROOT" rev-parse HEAD)
GOOD_TREE=$(git -C "$PROJECT_ROOT" rev-parse "origin/main:backend/java/cretas-api")
SHA64=$(printf 'a%.0s' $(seq 64))
MD5HEX=$(printf 'b%.0s' $(seq 32))

write_descriptor() { # path key=value...
    local path=$1; shift
    : > "$path"
    local kv
    for kv in "$@"; do printf '%s\n' "$kv" >> "$path"; done
}

good_descriptor() {
    write_descriptor "$1" \
        "format=cretas-remote-artifact-v1" \
        "build_commit=$GOOD_COMMIT" \
        "backend_tree=$GOOD_TREE" \
        "jar_sha256=$SHA64" \
        "jar_md5=$MD5HEX" \
        "jar_size_bytes=176283380" \
        "target_tests=*RepositoryQueryValidationTest" \
        "attested=true" \
        "staged_cache_path=/www/wwwroot/cretas/release-cache/sha256/$SHA64.jar"
}

D="$TMP_ROOT/d"
good_descriptor "$D"
load_remote_artifact_descriptor "$D" >/dev/null 2>&1 || fail "合法描述符被拒"
[ "$REMOTE_ARTIFACT_JAR_SHA256" = "$SHA64" ] || fail "sha256 未回填"
[ "$REMOTE_ARTIFACT_JAR_MD5" = "$MD5HEX" ] || fail "md5 未回填"
[ "$REMOTE_ARTIFACT_BUILD_COMMIT" = "$GOOD_COMMIT" ] || fail "build_commit 未回填"
[ "$REMOTE_ARTIFACT_TARGET_TESTS" = "*RepositoryQueryValidationTest" ] || fail "target_tests 未回填"

reject() { # name; then mutations
    local name=$1; shift
    good_descriptor "$D"
    "$@"
    if load_remote_artifact_descriptor "$D" >/dev/null 2>&1; then
        fail "应拒绝但通过了: $name"
    fi
}
set_field() { # key value
    local key=$1 value=$2
    # 原地替换某一行, 保持其余不动
    awk -v k="$key" -v v="$value" '
        index($0, k "=") == 1 { print k "=" v; next } { print }
    ' "$D" > "$D.new" && mv "$D.new" "$D"
}
drop_field() {
    grep -v "^$1=" "$D" > "$D.new" && mv "$D.new" "$D"
}

reject "未知 format"        set_field format cretas-remote-artifact-v2
reject "attested 非 true"   set_field attested false
reject "attested 缺失"      drop_field attested
reject "backend_tree 与 origin/main 不符" set_field backend_tree "$(printf 'c%.0s' $(seq 40))"
reject "build_commit 本地不存在"          set_field build_commit "$(printf 'd%.0s' $(seq 40))"
reject "jar_sha256 长度错"  set_field jar_sha256 abc
reject "jar_sha256 大写"    set_field jar_sha256 "$(printf 'A%.0s' $(seq 64))"
reject "jar_md5 长度错"     set_field jar_md5 abc
reject "size 为 0"          set_field jar_size_bytes 0
reject "size 非数字"        set_field jar_size_bytes 12x
reject "target_tests 为空"  set_field target_tests ""
reject "描述符不存在"       rm -f "$D"

# 重复 key 必须拒绝: release_manifest_field 在 count != 1 时失败。两份互相矛盾的
# jar_sha256 里"取第一个"会让攻击者用一行覆盖另一行。
good_descriptor "$D"
printf 'jar_sha256=%s\n' "$(printf 'e%.0s' $(seq 64))" >> "$D"
if load_remote_artifact_descriptor "$D" >/dev/null 2>&1; then
    fail "重复 jar_sha256 被接受"
fi

# build_commit 存在但其 backend tree 不同 —— 第二跳校验必须挡住。
(
    cd "$PROJECT_ROOT"
    echo changed > backend/java/cretas-api/pom.xml
    git add -A && git commit -qm drift
) || fail "制造 drift commit 失败"
DRIFT_COMMIT=$(git -C "$PROJECT_ROOT" rev-parse HEAD)
good_descriptor "$D"
set_field build_commit "$DRIFT_COMMIT"
if load_remote_artifact_descriptor "$D" >/dev/null 2>&1; then
    fail "build_commit 的 backend tree 与 origin/main 不同却被接受"
fi

# ------------------------------------------------- 2. source-contract 断言 --

# 远端路径下本机没有 jar, claim 没命中就必须硬失败 —— 绝不能落到任何上传方法上。
contains "$DEPLOY_SCRIPT" 'elif [ "$REMOTE_ARTIFACT_ONLY" = "true" ]; then'
contains "$DEPLOY_SCRIPT" '远端制品未在服务器缓存命中，且本机无 JAR 可上传'
# 摘要来自描述符而不是对本地文件做 md5sum
contains "$DEPLOY_SCRIPT" 'LOCAL_MD5=$REMOTE_ARTIFACT_JAR_MD5'
contains "$DEPLOY_SCRIPT" 'LOCAL_SHA256=$REMOTE_ARTIFACT_JAR_SHA256'
# 描述符校验不过是硬失败, 不是静默回退本地构建
contains "$DEPLOY_SCRIPT" '拒绝继续（不静默回退本地构建）'

# 完整性预检没有被删掉, 只是换了执行点: 本地分支仍保留, 服务器侧新增。
contains "$DEPLOY_SCRIPT" 'ch/qos/logback/classic/spi/ThrowableProxy.class'
contains "$VERIFY_SCRIPT" 'ch/qos/logback/classic/spi/ThrowableProxy.class'
contains "$VERIFY_SCRIPT" 'jar_integrity_verified=true'
# 完整性检查必须在 staging 之前, 否则 corrupt jar 会先成为生产候选品再被发现
INTEGRITY_LINE=$(grep -n 'jar_integrity_missing_logback_nested_jar' "$VERIFY_SCRIPT" | head -1 | cut -d: -f1)
STAGE_LINE=$(grep -n 'staged_to_cache=stored' "$VERIFY_SCRIPT" | head -1 | cut -d: -f1)
[ -n "$INTEGRITY_LINE" ] && [ -n "$STAGE_LINE" ] || fail "找不到完整性检查或 staging 行"
[ "$INTEGRITY_LINE" -lt "$STAGE_LINE" ] || fail "完整性检查排在 staging 之后"

# claim 需要 md5, 远端路径下只能由 ECS 提供
contains "$VERIFY_SCRIPT" 'echo "md5=$(md5sum'

# ---- bundle 必须走 stdin, 不得回到命令行 ----
# 事故实测: bundle 约 14KB, 放命令行时同一条 14,111 字符的远端命令经 Windows OpenSSH 完整
# 送达, 经 Git-for-Windows 的 ssh.exe 尾部被截断 —— 排在最后的 --source-digest 静默消失,
# ECS 只报 source_digest_not_supplied, 看起来像"调用方忘了传 pin"。从 bash 起的 pwsh 继承
# MSYS 的 PATH, 命中的正是坏的那个, 也就是 release-cretas.sh 的真实路径。
PS1="$ROOT_DIR/scripts/deploy/Publish-GitHubArtifactViaLightsailOss.ps1"
contains "$PS1" '--payload-stdin'
contains "$PS1" '$payload = $manifestB64 + "`n" + $attestationB64'
if grep -Fq -- '--attestation-b64 $attestationB64' "$PS1"; then
    fail "PS1 把 attestation bundle 放回命令行了 —— MSYS 的 ssh.exe 会截断尾部参数"
fi
contains "$VERIFY_SCRIPT" '--payload-stdin) payload_stdin=1'
# payload 解不开必须是硬错, 不能当"没有 manifest"继续
contains "$VERIFY_SCRIPT" 'error=payload_manifest_undecodable'

# release-cretas.sh: 默认必须是关闭的
contains "$RELEASE_SCRIPT" 'PREFER_CI_ARTIFACT=false'
contains "$RELEASE_SCRIPT" '--prefer-ci-artifact) PREFER_CI_ARTIFACT=true; shift ;;'
# 两条 Java 部署通道都要把描述符传下去, 少一条就会出现"build 用了 CI 制品但 deploy 又重建"
for marker in 'deploy-backend.sh" --env prod' 'deploy-cretas-parallel.sh'; do
    grep -Fq -- "$marker" "$RELEASE_SCRIPT" || fail "找不到部署通道: $marker"
done
[ "$(grep -c 'CRETAS_REMOTE_ARTIFACT_DESCRIPTOR="$CI_ARTIFACT_DESCRIPTOR"' "$RELEASE_SCRIPT")" = "2" ] \
    || fail "描述符没有传给全部两条 Java 部署通道"
# 回退必须吵, 且原因要进台账
contains "$RELEASE_SCRIPT" '回退本地构建'
contains "$RELEASE_SCRIPT" '"ci_artifact": {"status": "%s"'
# CI 路径不得消耗 Maven 回退预算
CI_BLOCK=$(awk '/^build_java\(\) \{/,/^\}/' "$RELEASE_SCRIPT")
printf '%s' "$CI_BLOCK" | grep -q 'JAVA_BUILD_COUNT=\$((JAVA_BUILD_COUNT + 1))' \
    || fail "build_java 里找不到 JAVA_BUILD_COUNT 递增"
CI_RETURN_LINE=$(printf '%s\n' "$CI_BLOCK" | grep -n 'JAVA_BUILD_STATUS=success-ci-artifact' | cut -d: -f1)
COUNT_LINE=$(printf '%s\n' "$CI_BLOCK" | grep -n 'JAVA_BUILD_COUNT=\$((JAVA_BUILD_COUNT + 1))' | cut -d: -f1)
[ -n "$CI_RETURN_LINE" ] && [ -n "$COUNT_LINE" ] || fail "定位失败"
[ "$CI_RETURN_LINE" -lt "$COUNT_LINE" ] \
    || fail "CI 成功路径排在 JAVA_BUILD_COUNT 递增之后, 会白吃掉一次 Maven 回退预算"

# ---- both 路径: 取制品与 Web 构建必须【并行】, 不得串行 ----
# 实测串行收益方向不定: Java160/Web150 → 并行163s vs 串行188s(慢25s); Java142/Web80 →
# 并行144s vs 串行约135s(快9s)。Web 在 80s↔150s 摆动, 方向不定的改动不该进发布路径。
# 并行则稳定: 取制品 55~69s 完全落在 Web 耗时内, 总时长 max(取制品,Web) < max(Java,Web)。
contains "$RELEASE_SCRIPT" 'run_ci_fetch_parallel_web() {'
contains "$RELEASE_SCRIPT" 'BUILD_MODE=ci-artifact-parallel-web'
# 必须先做 ~2s 廉价探测: 探测不过就直接本地并行构建, 不能白等一趟跨境传输
contains "$RELEASE_SCRIPT" '--probe-only'
contains "$CI_SCRIPT" 'CI_ARTIFACT_PROBE_OK'
contains "$CI_SCRIPT" 'if ((PROBE_ONLY)); then'
# 探测通过≠一定能用(选择器覆盖/attestation 要等制品送到), 所以必须有晚失败回退且要吵
contains "$RELEASE_SCRIPT" 'BUILD_MODE=ci-artifact-late-failure-java-fallback'
contains "$RELEASE_SCRIPT" 'Web 已构建, 现串行补建 Java'
# 两个后台任务必须各写各的日志, 不能同时 cat(duration_run 会 cat, 交错成乱码)
contains "$RELEASE_SCRIPT" 'fetch_state="$RUN_LOG_DIR/ci-fetch.state"'
contains "$RELEASE_SCRIPT" 'unavailable:fetch-state-missing'
# 并行路径里 Web 构建后必须仍然 validate, 否则跳过了 parallel-artifacts 原有的校验
BOTH_BLOCK=$(awk '/^run_ci_fetch_parallel_web\(\) \{/,/^\}/' "$RELEASE_SCRIPT")
printf '%s' "$BOTH_BLOCK" | grep -q 'release-web-manifest.sh" validate'     || fail "并行路径构建 Web 后没有 validate"
# 状态文件缺失必须当失败, 不能猜
printf '%s' "$BOTH_BLOCK" | grep -q 'CI_ARTIFACT_STATUS=unavailable:fetch-state-missing'     || fail "fetch 状态文件缺失时没有 fail-closed"

# ---- 测试选择器判据必须是集合包含, 不能是字符串相等 ----
# CI push 构建用通配 (*RepositoryQueryValidationTest, 本仓库实测 33 个类), 而
# release-java-preflight.sh 刻意拒绝通配 —— 字符串相等会让两者永远配不上, 功能形同不存在。
contains "$CI_SCRIPT" 'expand_test_selector'
contains "$CI_SCRIPT" 'ci_selector_does_not_cover_requested'
contains "$CI_SCRIPT" 'requested_selector_matches_no_test_class'
contains "$CI_SCRIPT" 'ci_selector_matches_no_test_class'
if grep -Fq 'if [ "$CI_TESTS" != "$TESTS" ]; then' "$CI_SCRIPT"; then
    fail "选择器判据退回了字符串相等 —— 经 release-cretas.sh 会永远配不上"
fi

# release-ci-artifact.sh 自己绝不回退, 只以非零退出交给调用方决定
contains "$CI_SCRIPT" 'CI_ARTIFACT_UNAVAILABLE reason='
# 只看非注释行: 注释里提到 "clean package" 是在解释它替代了什么, 不是调用。
if grep -vE '^[[:space:]]*#' "$CI_SCRIPT" | grep -Eq '(^|[^-[:alnum:]])mvnw?([[:space:]]|$)|clean package'; then
    fail "release-ci-artifact.sh 不应自己跑 Maven"
fi
# 选择器判据见下方「集合包含」断言组 (旧的 test_selector_mismatch 已被取代)
# 必须要求 attestation, 不能只看 transport
contains "$CI_SCRIPT" 'not_attested'
contains "$CI_SCRIPT" 'not_trusted'

# ---- ci.yml 的 push paths 必须覆盖【整个】 backend_tree 路径 ----
# 需要成立的不变量: 「那棵树变了 ⟺ CI 被触发」。paths 比 RELEASE_BACKEND_PATH 窄, 就会有
# 一类 commit 改变了 backend_tree 却没有对应制品 —— 那个 commit 上永远取不到匹配制品,
# 发布只能回退本地构建。实测这个洞命中过 13% 的 backend commit(全是纯测试改动)。
#
# 而且不只是效率: 制品的 vouching 是「ci.yml 在打包之前跑过那组测试」, 测试源就在这棵树里。
# 测试改了却复用旧制品 = 拿旧测试的结论给新测试背书。
CI_YML="$ROOT_DIR/.github/workflows/ci.yml"
[ -f "$CI_YML" ] || fail "找不到 ci.yml"
# shellcheck source=scripts/deploy/release-jar-manifest.sh
backend_path=$(sed -n 's/^RELEASE_BACKEND_PATH="\(.*\)"$/\1/p' \
    "$ROOT_DIR/scripts/deploy/release-jar-manifest.sh" | head -1)
[ -n "$backend_path" ] || fail "取不到 RELEASE_BACKEND_PATH"
# ⚠️ 必须 `--`: 模式以 '-' 开头, 否则 grep 当成选项报 "unknown option"(踩过)。
grep -Fq -- "- '$backend_path/**'" "$CI_YML" \
    || fail "ci.yml 的 push paths 未覆盖整个 $backend_path/** —— 存在改树却不触发 CI 的 commit"

# ---- --prefer-ci-artifact 默认开, 且必须有一个显式关闭出口 ----
# 2026-07-31 Steve 拍板翻成默认开: 默认关的时候整条 CI 制品链路对日常发布形同不存在
# (标准发布命令不带这个 flag), 实测 234s vs 405s。
contains "$RELEASE_SCRIPT" 'CRETAS_RELEASE_PREFER_CI_ARTIFACT:-1'
if grep -Fq -- 'CRETAS_RELEASE_PREFER_CI_ARTIFACT:-0}" = "1"' "$RELEASE_SCRIPT"; then
    fail "--prefer-ci-artifact 退回了默认关 —— 那等于把整条 CI 制品链路从日常发布上摘掉"
fi
# 默认开就必须留可逆出口, 否则出问题时只能改脚本
contains "$RELEASE_SCRIPT" '--no-prefer-ci-artifact)'
# 关掉时必须【显式导出 0】: 只导出 1 的话, --no-prefer-ci-artifact 配上环境里已有的 =1
# 会变成 Java 侧关而 Web 侧开 —— 又一个"只在一半路径生效"的半吊子开关。
contains "$RELEASE_SCRIPT" 'export CRETAS_RELEASE_PREFER_CI_ARTIFACT=0'
contains "$RELEASE_SCRIPT" 'export CRETAS_RELEASE_PREFER_CI_ARTIFACT=1'

# 反向: 仍然不许把 web-admin 塞进来。on.push.paths 是 workflow 级的, 加进来会让
# java-build-test 被纯前端改动触发, 白跑 4~5 分钟(web 有自己的 web-dist.yml)。
if grep -Eq "^[[:space:]]*-[[:space:]]*'?web-admin/" "$CI_YML"; then
    fail "web-admin 被加进了 ci.yml 的 push paths —— 会触发无意义的 Java 构建"
fi

echo "PASS: test-release-ci-artifact.sh"
