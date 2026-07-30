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
MAX_CANDIDATES=${CRETAS_CI_ARTIFACT_MAX_CANDIDATES:-20}

usage() {
    cat >&2 <<'EOF'
usage: release-ci-artifact.sh --tests <MavenTestSelector> [--descriptor <path>]

  --tests       发布要求的测试选择器。CI 制品的 manifest 必须记录【完全相同】的选择器,
                否则这份制品是被另一组测试把关的, 复用它等于跳过了你要求的那组测试。
  --descriptor  描述符写到哪 (默认 <jar cache>/current/release-jar.remote)
EOF
    exit 2
}

while (($#)); do
    case "$1" in
        --tests) (($# >= 2)) || usage; TESTS=$2; shift 2 ;;
        --descriptor) (($# >= 2)) || usage; DESCRIPTOR=$2; shift 2 ;;
        -h|--help) usage ;;
        *) echo "ERROR: unknown argument: $1" >&2; usage ;;
    esac
done

[ -n "$TESTS" ] || { echo "ERROR: --tests 不能为空" >&2; usage; }
case "$TESTS" in *$'\n'*|*$'\r'*) echo "ERROR: --tests 不得含换行" >&2; exit 2 ;; esac
[ -n "$DESCRIPTOR" ] || DESCRIPTOR="$(release_manifest_cache_root)/current/release-jar.remote"

fail() { echo "CI_ARTIFACT_UNAVAILABLE reason=$1" >&2; exit 1; }

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
[ -n "$candidates" ] || fail no_main_artifact_listed

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

[ -n "$ARTIFACT_ID" ] || fail no_artifact_matching_current_backend_tree
[ -n "$ARTIFACT_COMMIT" ] || fail no_artifact_commit

echo "CI_ARTIFACT_CANDIDATE id=$ARTIFACT_ID commit=$ARTIFACT_COMMIT backend_tree=$CURRENT_TREE"

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

# 测试选择器必须逐字相等。CI 的 push 构建默认只跑 *RepositoryQueryValidationTest, 发布方要
# 求别的选择器时这份制品就【不能】用 —— 复用它等于跳过了你要求的那组测试。
CI_TESTS=$(sed -n "s/^manifest_target_tests='\(.*\)'$/\1/p" "$transport_log" | head -1)
[ -n "$CI_TESTS" ] || fail manifest_target_tests_absent
if [ "$CI_TESTS" != "$TESTS" ]; then
    fail "test_selector_mismatch(ci='$CI_TESTS' requested='$TESTS')"
fi

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
