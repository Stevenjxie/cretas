#!/usr/bin/env bash
# 用一份 CI 构建好的、provenance 已验证的制品顶替本地 Maven 构建。
#
# 本地 clean package 是发布里最大的单项开销 (javac 4083 文件 110s 单线程 + 打 7236 个 zip
# 条目 15s, 实测)。CI 在 PR/push 阶段已经把这活干过一遍了, 而且从 #2020 起那份产物带
# Sigstore provenance —— 可以在 ECS 上离线验到"这些字节来自我们的 ci.yml、来自我们要部署的
# 那个 commit"。
#
# 制品字节【一次都不经过本机】: GitHub → 东京 Lightsail → 上海 OSS → ECS 内网, 直接落进
# deploy-backend.sh 的 claim_remote_sha256_artifact 会去取的那个缓存目录。本机只搬运
# 几 KB 的控制信息与签名 bundle。
#
# 产出一份【远端制品描述符】而不是本地 jar。deploy-backend.sh 读它拿 sha256/md5/大小,
# 然后靠 claim 直接命中服务器上那份, 整段构建 + 上传都不发生。
#
# ⛔ 全程 fail closed。任何一步不满足就以非零退出, 由调用方决定是否回退本地构建 ——
# 这个脚本自己【绝不】偷偷回退, 那会把"用了 CI 制品"和"其实重编了一遍"混成一件事。
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
# shellcheck source=scripts/deploy/release-jar-manifest.sh
. "$SCRIPT_DIR/release-jar-manifest.sh"

REPO=${CRETAS_RELEASE_REPO:-Stevenjxie/cretas}
DEST_PREFIX=${CRETAS_RELEASE_OSS_PREFIX:-deploy/backend/}
DESCRIPTOR_FORMAT="cretas-remote-artifact-v1"
PUBLISH_PS1="$SCRIPT_DIR/Publish-GitHubArtifactViaLightsailOss.ps1"

TESTS=""
DESCRIPTOR=""
PROBE_ONLY=0
PREWARM=0
MAX_CANDIDATES=${CRETAS_CI_ARTIFACT_MAX_CANDIDATES:-20}
# 与 deploy-backend.sh 同源的两个值 —— 预热短路要问 ECS「那份 jar 还在不在」, 问的必须是
# claim_remote_sha256_artifact 稍后真正会去取的那个目录, 否则短路会基于一个不相干的路径。
ECS_SERVER=${CRETAS_BACKEND_SERVER:-root@47.100.235.168}
ECS_JAR_CACHE_DIR=${CRETAS_REMOTE_JAR_CACHE_DIR:-/www/wwwroot/cretas/release-cache/sha256}

usage() {
    cat >&2 <<'EOF'
usage: release-ci-artifact.sh --tests <MavenTestSelector> [--descriptor <path>]

  --tests       发布要求的测试选择器。CI 制品的 manifest 必须记录【完全相同】的选择器,
                否则这份制品是被另一组测试把关的, 复用它等于跳过了你要求的那组测试。
  --descriptor  描述符写到哪 (默认 <jar cache>/current/release-jar.remote)
  --probe-only  只判断「有没有一份 backend_tree 匹配的候选制品」就退出, 不做任何传输。
                约 2s。给调用方一个廉价的前置判断: 探测不过就直接走本地构建, 一秒不浪费;
                探测过了才值得承诺「取制品与其它构建并行」这种安排。
  --prewarm     合并后立即跑, 把运输挪出发布窗口。语义与不带它时【完全相同】, 唯一区别是
                「CI 还没构建完 / 还没有匹配制品」不算失败(退 0 并打 pending) —— 合并后
                几分钟内 CI 通常还在跑, 那不是错误, 只是还没轮到。
EOF
    exit 2
}

while (($#)); do
    case "$1" in
        --tests) (($# >= 2)) || usage; TESTS=$2; shift 2 ;;
        --descriptor) (($# >= 2)) || usage; DESCRIPTOR=$2; shift 2 ;;
        --probe-only) PROBE_ONLY=1; shift ;;
        --prewarm) PREWARM=1; shift ;;
        -h|--help) usage ;;
        *) echo "ERROR: unknown argument: $1" >&2; usage ;;
    esac
done

[ -n "$TESTS" ] || { echo "ERROR: --tests 不能为空" >&2; usage; }
case "$TESTS" in *$'\n'*|*$'\r'*) echo "ERROR: --tests 不得含换行" >&2; exit 2 ;; esac
[ -n "$DESCRIPTOR" ] || DESCRIPTOR="$(release_manifest_cache_root)/current/release-jar.remote"

fail() { echo "CI_ARTIFACT_UNAVAILABLE reason=$1" >&2; exit 1; }

# --prewarm 下, 「还没有可用制品」不是失败 —— 合并后几分钟 CI 通常还在跑。其它任何原因
# (传输失败/验签不过/选择器覆盖不足)仍然是硬失败: 预热是把工作提前, 不是把标准放松。
fail_unless_prewarm() {
    if ((PREWARM)); then
        echo "CI_ARTIFACT_PREWARM=pending reason=$1"
        exit 0
    fi
    fail "$1"
}

# 把 Maven -Dtest 选择器展开成类名集合。逗号分隔; 去掉 `#method` 后缀; 类名里的 * / ? 交给
# find -name 当 glob 处理。
#
# ⚠️ 定义必须排在预热短路之前 —— 短路要用它判「要求 ⊆ 描述符记录的 CI 选择器」。
# (原先它定义在运输之后, 因为只有那时才用得到。)
expand_test_selector() {
    local selector=$1 part class
    local -a parts=()
    IFS=',' read -r -a parts <<< "$selector"
    for part in "${parts[@]}"; do
        class=${part%%#*}
        # 去空白; 空段(如 "A,,B")直接跳过
        class=${class//[[:space:]]/}
        [ -n "$class" ] || continue
        find "$PROJECT_ROOT/backend/java" -path '*/src/test/*' -name "${class}.java" 2>/dev/null \
            | sed 's|.*/||; s|\.java$||'
    done | grep -v '^$' | LC_ALL=C sort -u
}

# 要求的选择器是否被 covering_selector 覆盖(集合包含)。展开为空一律当不覆盖 ——
# 空集是任何集合的子集, 不挡住会静默通过。
selector_is_covered() {
    local requested=$1 covering=$2 req_set cov_set missing
    req_set=$(expand_test_selector "$requested")
    cov_set=$(expand_test_selector "$covering")
    [ -n "$req_set" ] || return 1
    [ -n "$cov_set" ] || return 1
    missing=$(LC_ALL=C comm -23 <(printf '%s\n' "$req_set") <(printf '%s\n' "$cov_set"))
    [ -z "$missing" ]
}

# ---- 1. 与 release_manifest_validate 同一把闸: 干净且恰好等于 origin/main ----
# prod 只从 main 部署是硬规则; 这里提前挡住, 免得白跑一趟跨境链路。
release_manifest_require_clean_exact_origin_main "$PROJECT_ROOT" \
    || fail head_not_clean_origin_main

command -v gh >/dev/null 2>&1 || fail gh_not_installed
command -v pwsh >/dev/null 2>&1 || fail pwsh_not_installed
[ -f "$PUBLISH_PS1" ] || fail publish_script_missing

CURRENT_TREE=$(git -C "$PROJECT_ROOT" rev-parse "origin/main:$RELEASE_BACKEND_PATH" 2>/dev/null) \
    || fail cannot_resolve_backend_tree
release_manifest_is_lower_hex "$CURRENT_TREE" 40 || fail backend_tree_malformed

# ---- 2b. 已经预热过就跳过整段运输 ----
# 那 71s 跨境运输之所以落在发布窗口里, 只是因为它是在发布那一刻才发起的 —— 制品在 push 后
# 几分钟就躺在 GitHub 上了。合并后先跑一次 `--prewarm`, 这里就直接命中, 发布时只剩
# deploy-backend.sh 的一次 claim。
#
# 短路条件必须【完整】覆盖"这份描述符现在还能不能用", 少一条就会把一份不适用的描述符
# 当成预热成功, 然后在部署期晚失败:
#   a) 描述符存在且 format 对
#   b) backend_tree == 当前 —— 内容不同的制品绝不能复用
#   c) attested=true —— 没验过签的不算数
#   d) 本次要求的选择器 ⊆ 描述符记录的 CI 选择器 (集合包含, 与 #2031 同一判据)
#   e) ECS 上那份 jar 还在, 且字节数对得上
#
# (e) 刻意只做存在性 + 大小的【廉价前置】检查: sha256/md5/unzip 三项由部署期
# claim_remote_sha256_artifact 做权威校验。在这里重算 176MB 的 sha 是白花时间; 而只信描述符
# 不问服务器, 则会在缓存被清理后给出一个必然晚失败的"预热命中"。
descriptor_field() {
    awk -v key="$1" '
        index($0, key "=") == 1 { count++; value = substr($0, length(key) + 2); sub(/\r$/, "", value) }
        END { if (count != 1) exit 1; print value }
    ' "$DESCRIPTOR"
}

if [ -f "$DESCRIPTOR" ]; then
    d_format=$(descriptor_field format 2>/dev/null || true)
    d_tree=$(descriptor_field backend_tree 2>/dev/null || true)
    d_attested=$(descriptor_field attested 2>/dev/null || true)
    d_tests=$(descriptor_field target_tests 2>/dev/null || true)
    d_sha=$(descriptor_field jar_sha256 2>/dev/null || true)
    d_size=$(descriptor_field jar_size_bytes 2>/dev/null || true)

    if [ "$d_format" = "$DESCRIPTOR_FORMAT" ] \
        && [ "$d_tree" = "$CURRENT_TREE" ] \
        && [ "$d_attested" = "true" ] \
        && release_manifest_is_lower_hex "$d_sha" 64 \
        && [ -n "$d_size" ] \
        && selector_is_covered "$TESTS" "$d_tests" \
        && ssh -n -o BatchMode=yes -o ConnectTimeout=10 "$ECS_SERVER" \
              "[ -f '$ECS_JAR_CACHE_DIR/$d_sha.jar' ] && [ \"\$(stat -c %s '$ECS_JAR_CACHE_DIR/$d_sha.jar')\" = '$d_size' ]" \
              >/dev/null 2>&1; then
        d_commit=$(descriptor_field build_commit)
        echo "CI_ARTIFACT_PREWARM_HIT commit=$d_commit jar_sha256=$d_sha"
        # 探测方只看退出码(全仓无人 grep 这行), 但日志里留一行同名的, 免得"探测通过"在
        # 预热与非预热两条路上长得不一样。
        ((PROBE_ONLY)) && echo "CI_ARTIFACT_PROBE_OK commit=$d_commit prewarmed=true"
        echo "CI_ARTIFACT_DESCRIPTOR=$DESCRIPTOR"
        echo "CI_ARTIFACT_READY commit=$d_commit jar_sha256=$d_sha staged=prewarmed"
        exit 0
    fi
fi

# ---- 2. 找一份可用制品 ----
# 制品的 commit 【不必】等于 HEAD: backend_tree 是内容哈希, 一个只改文档的 commit 之后,
# 上一个 backend commit 的制品仍然完全正确。这与 release_manifest_validate 允许
# build_commit != HEAD 的理由是同一个。
#
# 但 attestation 必须按【那份制品自己的 commit】来验 —— 拿 HEAD 去 pin 会验不过, 而
# 放宽成不 pin 就等于任意 commit 的签名都能用。所以两件事都要:
#   a) source-digest = 制品的 commit  (签名绑定)
#   b) 该 commit 的 backend_tree == origin/main 的  (内容等价)
# 过滤全部交给 jq 并用 @tsv 出结果。
#
# 先前这里用 python 把 JSON 转成 TSV, 结果 MSYS 下 python 的 print() 输出 CRLF, 每个 sha 都
# 带一个尾随 \r → 长度 41 → 校验不过 → 脚本报 no_artifact_matching_current_backend_tree。
# 那条消息和"确实还没有可用制品"长得一模一样, 是会被当成正常 fail-closed 咽下去的假结果。
# 少一个解释器就少一层这种翻译。
#
# name == "cretas-java-" + head_sha 这一条不能省: 任何人都能手工上传一个同名制品, 名字对不上
# 说明它不是那条 workflow 产出的。
candidates=$(GH_HTTP_TIMEOUT=20 gh api \
    "repos/$REPO/actions/artifacts?per_page=100" \
    --jq '.artifacts[]
          | select(.expired == false)
          | select(.workflow_run.head_branch == "main")
          | select(.name == "cretas-java-" + .workflow_run.head_sha)
          | [(.id | tostring), .workflow_run.head_sha]
          | @tsv' 2>/dev/null) \
    || fail gh_api_failed
[ -n "$candidates" ] || fail_unless_prewarm no_main_artifact_listed

ARTIFACT_ID="" ARTIFACT_COMMIT=""
checked=0
while IFS=$'\t' read -r cand_id cand_sha; do
    # 防御性剥 CR: 这条链路上任何一环(gh / jq / 未来某个中间工具)在 Windows 上多写一个 \r,
    # 都会让长度校验静默失败, 而失败长得像"没有可用制品"。
    cand_id=${cand_id%$'\r'}
    cand_sha=${cand_sha%$'\r'}
    [ -n "${cand_id:-}" ] || continue
    [ "$checked" -lt "$MAX_CANDIDATES" ] || break
    checked=$((checked + 1))
    release_manifest_is_lower_hex "$cand_sha" 40 || continue
    # 本机可能还没有那个 commit(制品比本地 fetch 新)。cat-file 先确认对象在, 否则
    # rev-parse 会报错而我们只想跳过这个候选。
    git -C "$PROJECT_ROOT" cat-file -e "${cand_sha}^{commit}" 2>/dev/null || continue
    cand_tree=$(git -C "$PROJECT_ROOT" rev-parse "${cand_sha}:$RELEASE_BACKEND_PATH" 2>/dev/null) || continue
    [ "$cand_tree" = "$CURRENT_TREE" ] || continue
    ARTIFACT_ID=$cand_id
    ARTIFACT_COMMIT=$cand_sha
    break
done <<< "$candidates"

[ -n "$ARTIFACT_ID" ] || fail_unless_prewarm no_artifact_matching_current_backend_tree
[ -n "$ARTIFACT_COMMIT" ] || fail no_artifact_commit

echo "CI_ARTIFACT_CANDIDATE id=$ARTIFACT_ID commit=$ARTIFACT_COMMIT backend_tree=$CURRENT_TREE"

# --probe-only 到此为止。刻意【只】保证「存在 backend_tree 匹配的候选」——
# 选择器覆盖与 attestation 都要等制品真的送到才能判, 探测不做也不假装做。
# 所以探测通过≠一定能用, 调用方必须为「晚失败」留好回退路径。
if ((PROBE_ONLY)); then
    echo "CI_ARTIFACT_PROBE_OK id=$ARTIFACT_ID commit=$ARTIFACT_COMMIT"
    exit 0
fi


# ---- 3. 跑传输链路; 制品字节不经过本机 ----
ps1_win=$(cygpath -w "$PUBLISH_PS1" 2>/dev/null || printf '%s' "$PUBLISH_PS1")
transport_log=$(mktemp)
trap 'rm -f "$transport_log"' EXIT

if ! pwsh -NoProfile -NonInteractive -File "$ps1_win" \
        -Repository "$REPO" \
        -ArtifactId "$ARTIFACT_ID" \
        -TreeSha "$CURRENT_TREE" \
        -SourceDigest "$ARTIFACT_COMMIT" \
        -DestinationPrefix "$DEST_PREFIX" \
        -StageToCache > "$transport_log" 2>&1; then
    echo "--- transport log (tail) ---" >&2
    tail -20 "$transport_log" >&2
    fail transport_or_verification_failed
fi
cat "$transport_log"

field() {
    # 单行 key=value; 出现 0 次或多于 1 次都当失败, 不给静默空值。
    awk -v key="$1" '
        index($0, key "=") == 1 { count++; value = substr($0, length(key) + 2); sub(/\r$/, "", value) }
        END { if (count != 1) exit 1; print value }
    ' "$transport_log"
}

TRUST=$(field deployable_trust_verified) || fail trust_field_unreadable
ATTESTED=$(field attestation_verified)   || fail attestation_field_unreadable
JAR_SHA=$(field sha256)                  || fail sha256_field_unreadable
JAR_MD5=$(field md5)                     || fail md5_field_unreadable
STAGED=$(field staged_to_cache)          || fail staged_field_unreadable
CACHE_PATH=$(field cache_path)           || fail cache_path_field_unreadable

# ---- 4. 逐条硬断言 ----
[ "$TRUST" = "true" ]    || fail "not_trusted(trust=$TRUST)"
[ "$ATTESTED" = "true" ] || fail "not_attested(attested=$ATTESTED)"
release_manifest_is_lower_hex "$JAR_SHA" 64 || fail jar_sha_malformed
release_manifest_is_lower_hex "$JAR_MD5" 32 || fail jar_md5_malformed
case "$STAGED" in stored|hit) ;; *) fail "not_staged(staged=$STAGED)" ;; esac

# 判据是【集合包含】而不是字符串相等: CI 实际跑过的测试类集合必须【涵盖】发布方要求的那组。
#
# 为什么不能用字符串相等(第一版就是, 错了): CI 的 push 构建用 `*RepositoryQueryValidationTest`
# (本仓库实测匹配 33 个类), 而 release-java-preflight.sh 【刻意拒绝通配】—— 它要把每个选择器
# 映射到真实测试文件并验证 import 可解析, 通配没法映射。两个约束都是对的, 但字符串相等让它们
# 永远配不上: 经 release-cretas.sh 这条路, 那份制品一次都用不了。
#
# 集合包含才是这件事的本来含义: 「这份制品被至少你要求的那组测试把关过」。
#   要求 ⊆ CI 跑过  → 可用
#   要求 ⊄ CI 跑过  → 不可用(有你要的测试 CI 没跑)
#
# 展开在【本地 HEAD 的测试源】上做。这一步的正确性依赖前面已经通过的 backend_tree 相等检查 ——
# tree 相同意味着测试源逐字节相同, 所以在这里展开与在制品那个 commit 上展开是同一个集合。
CI_TESTS=$(sed -n "s/^manifest_target_tests='\(.*\)'$/\1/p" "$transport_log" | head -1)
[ -n "$CI_TESTS" ] || fail manifest_target_tests_absent

REQUESTED_SET=$(expand_test_selector "$TESTS")
CI_SET=$(expand_test_selector "$CI_TESTS")
# 展开为空一律拒绝: 一个匹配不到任何测试类的选择器无法证明任何事, 而"空集是任何集合的子集"
# 会让它静默通过。
[ -n "$REQUESTED_SET" ] || fail "requested_selector_matches_no_test_class(requested='$TESTS')"
[ -n "$CI_SET" ] || fail "ci_selector_matches_no_test_class(ci='$CI_TESTS')"

MISSING=$(LC_ALL=C comm -23 <(printf '%s\n' "$REQUESTED_SET") <(printf '%s\n' "$CI_SET"))
if [ -n "$MISSING" ]; then
    fail "ci_selector_does_not_cover_requested(missing=$(printf '%s' "$MISSING" | tr '\n' ',' | sed 's/,$//'))"
fi
echo "CI_ARTIFACT_TEST_COVERAGE requested=$(printf '%s\n' "$REQUESTED_SET" | grep -c .) ci_ran=$(printf '%s\n' "$CI_SET" | grep -c .) ci_selector='$CI_TESTS'"

JAR_SIZE=$(field oss_to_ecs_bytes) || fail size_field_unreadable
case "$JAR_SIZE" in ''|*[!0123456789]*|0*) fail size_malformed ;; esac

# ---- 5. 写描述符 ----
mkdir -p "$(dirname "$DESCRIPTOR")" || fail descriptor_dir_uncreatable
tmp="${DESCRIPTOR}.tmp.$$"
{
    printf 'format=%s\n' "$DESCRIPTOR_FORMAT"
    printf 'build_commit=%s\n' "$ARTIFACT_COMMIT"
    printf 'backend_tree=%s\n' "$CURRENT_TREE"
    printf 'jar_sha256=%s\n' "$JAR_SHA"
    printf 'jar_md5=%s\n' "$JAR_MD5"
    printf 'jar_size_bytes=%s\n' "$JAR_SIZE"
    printf 'target_tests=%s\n' "$CI_TESTS"
    printf 'attested=true\n'
    printf 'staged_cache_path=%s\n' "$CACHE_PATH"
    printf 'artifact_id=%s\n' "$ARTIFACT_ID"
} > "$tmp" || { rm -f "$tmp"; fail descriptor_write_failed; }
mv -f "$tmp" "$DESCRIPTOR" || { rm -f "$tmp"; fail descriptor_move_failed; }

echo "CI_ARTIFACT_DESCRIPTOR=$DESCRIPTOR"
echo "CI_ARTIFACT_READY commit=$ARTIFACT_COMMIT jar_sha256=$JAR_SHA staged=$STAGED"
