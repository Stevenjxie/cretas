#!/usr/bin/env bash
# Verify a published artifact by pulling it back over the Shanghai OSS internal
# endpoint and re-hashing it on the ECS that will actually run it.
#
# This step exists because transport success is not trust. A byte-perfect
# delivery still proves nothing about whether the JAR was built from a reviewed
# tree and passed the target test selector.
#
# Trust is established by a Sigstore provenance attestation (signed by GitHub
# Actions, verified here against a trusted root pinned on this host) plus the
# release manifest that names the test selector. Without a verified attestation we
# report deployable_trust_verified=false and say so out loud -- an unsigned
# manifest rides inside the same ZIP as the JAR, so on its own it lets any
# producer mint its own "tests passed" statement.
#
# Nothing in the transport path (Windows orchestrator, Tokyo relay, OSS) is
# trusted to judge provenance. They only carry the bundle; tampering with it makes
# verification fail closed here.
set -euo pipefail
set +x
umask 027

BUCKET=${CRETAS_RELEASE_OSS_BUCKET:-cretas-media}
OSSUTIL=${OSSUTIL:-/usr/local/bin/ossutil64}
OSSUTIL_CONFIG=${OSSUTIL_CONFIG:-$HOME/.ossutilconfig}
INTERNAL_ENDPOINT="oss-cn-shanghai-internal.aliyuncs.com"
ACCEPTANCE_PREFIX="codex-network-test/"

usage() {
  cat >&2 <<'EOF'
usage: oss-verify-artifact.sh --prefix <p> --tree-sha <sha> --jar-sha256 <hex> \
                              --size <bytes> [--manifest <path>] [--purge-acceptance]
                              [--stage-to-cache]
                              [--attestation-b64 <base64>] [--source-digest <commit>]
                              [--payload-stdin]

  --payload-stdin  stdin 读两行 base64: 第 1 行 manifest, 第 2 行 attestation bundle(可空)。
                   自动化路径用这个, 别用 --attestation-b64 (14KB 参数会被 MSYS ssh 截断)。
EOF
  exit 2
}

# Where the Sigstore trusted root is pinned. It must NOT arrive with the artifact:
# shipping the trust root alongside the thing it verifies is exactly the mistake
# the unsigned manifest makes. Provision it out of band with
#   gh attestation trusted-root > /etc/cretas/sigstore-trusted-root.jsonl
# (works unauthenticated; ECS reaches api.github.com in ~0.28s, measured).
TRUSTED_ROOT=${CRETAS_ATTEST_TRUSTED_ROOT:-/etc/cretas/sigstore-trusted-root.jsonl}
ATTEST_REPO=${CRETAS_ATTEST_REPO:-Stevenjxie/cretas}
ATTEST_SIGNER_WORKFLOW=${CRETAS_ATTEST_SIGNER_WORKFLOW:-Stevenjxie/cretas/.github/workflows/ci.yml}
GH_BIN=${CRETAS_GH_BIN:-/usr/bin/gh}

prefix= tree_sha= jar_sha= expected_size= manifest= purge=0 manifest_stdin=0 stage=0
attestation_b64= source_digest= payload_stdin=0
payload_manifest_b64= payload_attestation_b64=
while (($#)); do
  case "$1" in
    --prefix) (($# >= 2)) || usage; prefix=$2; shift 2 ;;
    --tree-sha) (($# >= 2)) || usage; tree_sha=$2; shift 2 ;;
    --jar-sha256) (($# >= 2)) || usage; jar_sha=$2; shift 2 ;;
    --size) (($# >= 2)) || usage; expected_size=$2; shift 2 ;;
    --manifest) (($# >= 2)) || usage; manifest=$2; shift 2 ;;
    --manifest-stdin) manifest_stdin=1; shift ;;
    --purge-acceptance) purge=1; shift ;;
    --stage-to-cache) stage=1; shift ;;
    # 自动化路径请用 --payload-stdin。--attestation-b64 保留给人工排查(直接在 ECS 上跑),
    # 因为它受命令行长度/引用规则影响 —— 见文件头那段 MSYS ssh 截断的实测。
    --attestation-b64) (($# >= 2)) || usage; attestation_b64=$2; shift 2 ;;
    --payload-stdin) payload_stdin=1; shift ;;
    --source-digest) (($# >= 2)) || usage; source_digest=$2; shift 2 ;;
    *) usage ;;
  esac
done

# A CI artifact's manifest lives inside the downloaded ZIP on the Tokyo host, so
# it reaches us as bytes rather than a path. Reading it here keeps it off the
# Windows filesystem entirely.
#
# --payload-stdin 是自动化路径用的形式: 两行 base64, 第一行 manifest, 第二行 attestation
# bundle(允许为空)。base64 字母表不含换行, 所以两行分隔不可能与内容冲突, 不需要自造分隔符。
#
# 为什么不走命令行: bundle 约 14KB, 而 --attestation-b64 那种形式在 MSYS 的
# /usr/bin/ssh.exe 下会被截断 —— 实测同一条 14,111 字符的命令, Windows OpenSSH
# (C:\Windows\System32\OpenSSH\ssh.exe) 送达完整, Git-for-Windows 的 ssh.exe 把尾部截掉,
# 于是最后一个参数 --source-digest 静默消失, ECS 报 source_digest_not_supplied。
# 从 bash 起的 pwsh 继承 MSYS 的 PATH, 命中的正是坏的那个 —— 也就是真实发布路径。
manifest_content=
if ((payload_stdin)); then
  IFS= read -r payload_manifest_b64 || payload_manifest_b64=
  IFS= read -r payload_attestation_b64 || payload_attestation_b64=
  payload_manifest_b64=${payload_manifest_b64%$'\r'}
  payload_attestation_b64=${payload_attestation_b64%$'\r'}
  if [[ -n $payload_manifest_b64 ]]; then
    manifest_content=$(printf '%s' "$payload_manifest_b64" | base64 -d 2>/dev/null) || {
      echo "error=payload_manifest_undecodable" >&2
      exit 2
    }
  fi
  if [[ -n $payload_attestation_b64 ]]; then
    attestation_b64=$payload_attestation_b64
  fi
  manifest_stdin=1
elif ((manifest_stdin)); then
  manifest_content=$(cat)
fi

[[ $prefix == */ ]] || prefix="$prefix/"
[[ $jar_sha =~ ^[0-9a-f]{64}$ ]] || usage
[[ $tree_sha =~ ^[0-9a-f]{7,40}$ ]] || usage
[[ $expected_size =~ ^[1-9][0-9]*$ ]] || usage
# A malformed pin must be a usage error, never a silently dropped constraint.
if [[ -n $source_digest ]]; then
  [[ $source_digest =~ ^[0-9a-f]{40}$ ]] || usage
fi
if [[ -n $attestation_b64 ]]; then
  [[ $attestation_b64 =~ ^[A-Za-z0-9+/=]+$ ]] || usage
fi
case "$prefix" in
  deploy/backend/|codex-network-test/) ;;
  *) echo "error=prefix_not_approved" >&2; exit 2 ;;
esac
# Refuse an unpurgeable prefix up front rather than after downloading 168MB.
# The check is repeated at the delete site as defence in depth.
if ((purge)) && [[ $prefix != "$ACCEPTANCE_PREFIX" ]]; then
  echo "error=refusing_to_purge_non_acceptance_prefix" >&2
  exit 2
fi
# Staging puts the jar where deploy-backend.sh's claim_remote_sha256_artifact will
# find and install it. An acceptance-prefix object is a throwaway network-test
# object; letting one land there would make a test artifact installable.
if ((stage)) && [[ $prefix == "$ACCEPTANCE_PREFIX" ]]; then
  echo "error=refusing_to_stage_acceptance_object_into_deploy_cache" >&2
  exit 2
fi

key="${prefix}${tree_sha}/${jar_sha}.jar"
object="oss://${BUCKET}/${key}"

work_dir=$(mktemp -d)
cleanup() { rm -rf -- "$work_dir"; }
trap cleanup EXIT INT TERM

stat_output=$("$OSSUTIL" stat "$object" -c "$OSSUTIL_CONFIG" -e "$INTERNAL_ENDPOINT" 2>&1) || {
  echo "error=oss_stat_failed" >&2
  exit 1
}
remote_size=$(printf '%s\n' "$stat_output" |
  awk -F: 'tolower($1) ~ /^ *content-length *$/ { gsub(/ /, "", $2); print $2 }')
[[ -n $remote_size ]] || { echo "error=oss_stat_no_content_length" >&2; exit 1; }
if [[ $remote_size != "$expected_size" ]]; then
  echo "error=remote_size_mismatch expected=$expected_size actual=$remote_size" >&2
  exit 1
fi

started=$(date +%s.%N)
"$OSSUTIL" cp "$object" "$work_dir/artifact.jar" \
  -c "$OSSUTIL_CONFIG" -e "$INTERNAL_ENDPOINT" -f >/dev/null 2>&1 || {
  echo "error=oss_download_failed" >&2
  exit 1
}
finished=$(date +%s.%N)

local_size=$(stat -c %s "$work_dir/artifact.jar")
if [[ $local_size != "$expected_size" ]]; then
  echo "error=downloaded_size_mismatch expected=$expected_size actual=$local_size" >&2
  exit 1
fi
local_sha=$(sha256sum "$work_dir/artifact.jar" | cut -d ' ' -f 1)
if [[ $local_sha != "$jar_sha" ]]; then
  echo "error=downloaded_sha256_mismatch" >&2
  exit 1
fi

elapsed=$(awk -v a="$started" -v b="$finished" 'BEGIN { printf "%.3f", b - a }')
rate=$(awk -v n="$local_size" -v s="$elapsed" \
  'BEGIN { if (s > 0) printf "%.3f", n / s / 1048576; else printf "0.000" }')

# Field reader kept semantically identical to release_manifest_field() in
# scripts/deploy/release-jar-manifest.sh: exact key prefix (not -F=, because
# target_tests holds a Maven selector that itself contains '='), a duplicate or
# missing key is an error rather than a silent empty, and a trailing CR is
# stripped so a CRLF manifest does not poison the comparison.
manifest_field() {
  awk -v key="$2" '
    index($0, key "=") == 1 {
      count++
      value = substr($0, length(key) + 2)
      sub(/\r$/, "", value)
    }
    END {
      if (count != 1) exit 1
      print value
    }
  ' "$1"
}

# An unsigned manifest can prove internal consistency, but not provenance. It
# travels inside the same ZIP as the JAR, so accepting it as a trust root would
# let any producer mint its own "tests passed" statement. Until a trusted
# signature/attestation is verified, deployable trust must remain false.
trust=false
manifest_consistency=false
trust_reason=no_manifest_supplied
if ((manifest_stdin)); then
  if [[ -n $manifest_content ]]; then
    manifest="$work_dir/release-jar.manifest"
    printf '%s\n' "$manifest_content" > "$manifest"
  else
    trust_reason=manifest_stdin_was_empty
  fi
fi
if [[ -n $manifest ]]; then
  if [[ ! -f $manifest ]]; then
    trust_reason=manifest_not_found
  else
    manifest_tree=$(manifest_field "$manifest" backend_tree || true)
    manifest_jar=$(manifest_field "$manifest" jar_sha256 | tr '[:upper:]' '[:lower:]' || true)
    manifest_tests=$(manifest_field "$manifest" target_tests || true)
    if [[ -z $manifest_tree || -z $manifest_jar ]]; then
      trust_reason=manifest_incomplete
    elif [[ $manifest_jar != "$jar_sha" ]]; then
      trust_reason=manifest_jar_sha_mismatch
    elif [[ $manifest_tree != "$tree_sha" ]]; then
      trust_reason=manifest_tree_mismatch
    elif [[ -z $manifest_tests ]]; then
      trust_reason=manifest_has_no_test_selector
    else
      manifest_consistency=true
      trust_reason=manifest_consistent_but_unauthenticated
    fi
  fi
fi

# ---- provenance ----
# This is the signature the trust=false comment above was waiting for.
#
# What the attestation actually proves: these exact JAR bytes were produced by
# ATTEST_SIGNER_WORKFLOW in ATTEST_REPO, from source commit --source-digest. That
# makes the *workflow definition at that commit* the vouching authority instead of
# a text file that rode along in the same ZIP. ci.yml runs the test selector
# BEFORE packaging, so an attested artifact for commit X implies those tests
# passed at X -- which is a claim nobody in the transport path can forge.
#
# Everything here fails closed. A missing bundle, a missing trusted root, an
# unverifiable signature, a bundle for a different commit or a different workflow
# all leave attest_verified=false. There is deliberately no network fallback: if
# the pinned root is absent we refuse rather than quietly reaching for Sigstore's
# public-good default, because "verified against something we found at runtime"
# is not the same claim as "verified against the root we pinned".
attest_verified=false
attest_reason=no_attestation_supplied
if [[ -n $attestation_b64 ]]; then
  bundle="$work_dir/attestation.jsonl"
  if ! printf '%s' "$attestation_b64" | base64 -d > "$bundle" 2>/dev/null || [[ ! -s $bundle ]]; then
    attest_reason=attestation_b64_undecodable
  elif [[ ! -x $GH_BIN ]]; then
    attest_reason=gh_not_installed
  elif [[ ! -s $TRUSTED_ROOT ]]; then
    attest_reason=trusted_root_missing
  elif [[ -z $source_digest ]]; then
    # Without the commit pin an attestation for ANY commit of this repo would
    # satisfy verification, including an older one whose tests differed.
    attest_reason=source_digest_not_supplied
  else
    attest_args=(
      attestation verify "$work_dir/artifact.jar"
      --bundle "$bundle"
      --custom-trusted-root "$TRUSTED_ROOT"
      --repo "$ATTEST_REPO"
      --signer-workflow "$ATTEST_SIGNER_WORKFLOW"
      --source-digest "$source_digest"
      --deny-self-hosted-runners
    )
    if attest_output=$("$GH_BIN" "${attest_args[@]}" 2>&1); then
      attest_verified=true
      attest_reason=attestation_verified
    else
      attest_reason=attestation_verify_failed
      # One line, no bundle contents: enough to tell a missing root from a real
      # signature failure without dumping a certificate chain into the log.
      printf 'attestation_verify_stderr=%s\n' \
        "$(printf '%s' "$attest_output" | tr '\n' ' ' | cut -c1-200)" >&2
    fi
  fi
fi

# Trust needs both halves and says so: the attestation authenticates the bytes and
# their origin, the manifest is what names the test selector an auditor will ask
# about. A missing manifest is deliberately NOT tolerated even with a good
# signature -- CI emits both, so one showing up without the other is a signal, not
# a degraded-but-acceptable state.
if [[ $attest_verified == true && $manifest_consistency == true ]]; then
  trust=true
  trust_reason=attested_and_manifest_consistent
elif [[ $attest_verified == true ]]; then
  trust_reason="attested_but_$trust_reason"
fi

echo "oss_to_ecs_bytes=$local_size"
echo "oss_to_ecs_seconds=$elapsed"
echo "oss_to_ecs_megabytes_per_second=$rate"
echo "sha256=$local_sha"
# deploy-backend.sh's claim_remote_sha256_artifact gates on sha256 AND md5. When the
# deploy has no local JAR to hash it needs both from here.
echo "md5=$(md5sum "$work_dir/artifact.jar" | cut -d ' ' -f 1)"
echo "transport_verified=true"
echo "deployable_trust_verified=$trust"
echo "manifest_consistency_verified=$manifest_consistency"
echo "attestation_verified=$attest_verified"
echo "attestation_reason=$attest_reason"
echo "trust_reason=$trust_reason"
if [[ $manifest_consistency == true ]]; then
  # Quoted: a Maven selector contains spaces and '=', and an auditor needs to
  # see which test set actually vouched for this artifact.
  echo "manifest_target_tests='$manifest_tests'"
fi

if ((stage)); then
  # Now gated on deployable_trust_verified, not just transport.
  #
  # The previous version deliberately gated on transport alone, and that was the
  # right call at the time: deployable_trust_verified was false by design, so
  # gating on it would have made staging permanently dead. That premise is gone --
  # a verified attestation can now make it true -- so the exception goes with it.
  #
  # Why this matters: staging writes into the path claim_remote_sha256_artifact
  # installs from. Anything landing there is a candidate for production. Requiring
  # provenance here is the difference between "these bytes arrived intact" and
  # "these bytes came from our CI at the commit we intend to deploy".
  if [[ $trust != true ]]; then
    echo "error=refusing_to_stage_untrusted_artifact trust_reason=$trust_reason" >&2
    exit 1
  fi

  # Fat-JAR integrity, checked on the host that will actually run these bytes.
  #
  # 2026-04-24 事故: maven 增量编译偶发产生 corrupt fat jar — 缺
  # ch.qos.logback.classic.spi.ThrowableProxy。Spring Boot 起来后任何 exception 触发
  # logback rendering 都 cascade ClassNotFound, 服务 crashloop 而 nginx 健康检查在短暂
  # 窗口里可能仍返 200。
  #
  # deploy-backend.sh 一直在本地做这个检查。当部署改为不再拉本地 jar 时, 那个检查会
  # 随之消失 —— 所以搬到这里。搬过来不是等价替换而是更强: 检查对象从"一份随后要被上传的
  # 本地副本"变成"真正会被装上去运行的那份字节"。unzip -tqq 只校验外层 CRC, 抓不到
  # 嵌套 jar 内缺 class 这一类。
  nested=$(unzip -l "$work_dir/artifact.jar" 2>/dev/null |
    grep -oE 'BOOT-INF/lib/logback-classic-[0-9.]+\.jar' | head -1)
  if [[ -z $nested ]]; then
    echo "error=jar_integrity_missing_logback_nested_jar" >&2
    exit 1
  fi
  probe_dir="$work_dir/nested"
  mkdir -p "$probe_dir"
  if ! unzip -j -q -o "$work_dir/artifact.jar" "$nested" -d "$probe_dir" 2>/dev/null; then
    echo "error=jar_integrity_nested_jar_unreadable nested=$nested" >&2
    exit 1
  fi
  if ! unzip -l "$probe_dir/$(basename "$nested")" 2>/dev/null |
    grep -q 'ch/qos/logback/classic/spi/ThrowableProxy.class'; then
    echo "error=jar_integrity_missing_throwable_proxy nested=$nested" >&2
    exit 1
  fi
  echo "jar_integrity_verified=true nested=$nested"

  cache_dir="${CRETAS_REMOTE_JAR_CACHE_DIR:-/www/wwwroot/cretas/release-cache/sha256}"
  cache_path="$cache_dir/$jar_sha.jar"
  install -d -m 700 "$cache_dir"
  if [[ -f $cache_path ]]      && [[ "$(sha256sum "$cache_path" | cut -d ' ' -f 1)" == "$jar_sha" ]]; then
    echo "staged_to_cache=hit"
  else
    tmp="$cache_dir/.$jar_sha.$$"
    cp -- "$work_dir/artifact.jar" "$tmp"
    chmod 444 "$tmp"
    mv -f -- "$tmp" "$cache_path"
    echo "staged_to_cache=stored"
  fi
  echo "cache_path=$cache_path"
fi

if ((purge)); then
  if [[ $prefix != "$ACCEPTANCE_PREFIX" ]]; then
    echo "error=refusing_to_purge_non_acceptance_prefix" >&2
    exit 1
  fi
  "$OSSUTIL" rm "$object" -c "$OSSUTIL_CONFIG" -e "$INTERNAL_ENDPOINT" -f >/dev/null 2>&1 ||
    { echo "error=acceptance_purge_failed" >&2; exit 1; }
  echo "acceptance_object_purged=true"
fi
