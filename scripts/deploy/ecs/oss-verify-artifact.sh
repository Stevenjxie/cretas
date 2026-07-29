#!/usr/bin/env bash
# Verify a published artifact by pulling it back over the Shanghai OSS internal
# endpoint and re-hashing it on the ECS that will actually run it.
#
# This step exists because transport success is not trust. A byte-perfect
# delivery still proves nothing about whether the JAR was built from a reviewed
# tree and passed the target test selector. Those are decided by the release
# manifest, not by this script, so unless a manifest vouches for the artifact we
# report deployable_trust_verified=false and say so out loud.
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
EOF
  exit 2
}

prefix= tree_sha= jar_sha= expected_size= manifest= purge=0
while (($#)); do
  case "$1" in
    --prefix) (($# >= 2)) || usage; prefix=$2; shift 2 ;;
    --tree-sha) (($# >= 2)) || usage; tree_sha=$2; shift 2 ;;
    --jar-sha256) (($# >= 2)) || usage; jar_sha=$2; shift 2 ;;
    --size) (($# >= 2)) || usage; expected_size=$2; shift 2 ;;
    --manifest) (($# >= 2)) || usage; manifest=$2; shift 2 ;;
    --purge-acceptance) purge=1; shift ;;
    *) usage ;;
  esac
done

[[ $prefix == */ ]] || prefix="$prefix/"
[[ $jar_sha =~ ^[0-9a-f]{64}$ ]] || usage
[[ $tree_sha =~ ^[0-9a-f]{7,40}$ ]] || usage
[[ $expected_size =~ ^[1-9][0-9]*$ ]] || usage
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

# Trust is a manifest question, never a transport question.
trust=false
trust_reason=no_manifest_supplied
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
      trust=true
      trust_reason=manifest_matches_tree_and_tests
    fi
  fi
fi

echo "oss_to_ecs_bytes=$local_size"
echo "oss_to_ecs_seconds=$elapsed"
echo "oss_to_ecs_megabytes_per_second=$rate"
echo "sha256=$local_sha"
echo "transport_verified=true"
echo "deployable_trust_verified=$trust"
echo "trust_reason=$trust_reason"
if [[ $trust == true ]]; then
  # Quoted: a Maven selector contains spaces and '=', and an auditor needs to
  # see which test set actually vouched for this artifact.
  echo "manifest_target_tests='$manifest_tests'"
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
