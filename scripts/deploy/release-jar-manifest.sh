#!/usr/bin/env bash

RELEASE_MANIFEST_FORMAT="cretas-release-jar-manifest-v1"
RELEASE_BACKEND_PATH="backend/java/cretas-api"
RELEASE_JAR_NAME="cretas-backend-system-1.0.0.jar"
RELEASE_MANIFEST_NAME="release-jar.manifest"
RELEASE_BUILD_REPORT_NAME="release-jar.report.json"

release_manifest_cache_root() {
    printf '%s\n' "${CRETAS_JAR_CACHE_DIR:-$HOME/.cache/cretas/java-deploy}"
}

release_manifest_default_path() {
    printf '%s/current/%s\n' "$(release_manifest_cache_root)" "$RELEASE_MANIFEST_NAME"
}

release_manifest_field() {
    local manifest=$1
    local key=$2

    awk -v key="$key" '
        index($0, key "=") == 1 {
            count++
            value = substr($0, length(key) + 2)
            sub(/\r$/, "", value)
        }
        END {
            if (count != 1) exit 1
            print value
        }
    ' "$manifest"
}

release_manifest_require_clean_exact_origin_main() {
    local repo_root=$1
    local head_sha origin_sha

    head_sha=$(git -C "$repo_root" rev-parse HEAD 2>/dev/null) || return 1
    origin_sha=$(git -C "$repo_root" rev-parse origin/main 2>/dev/null) || return 1
    [ "$head_sha" = "$origin_sha" ] || return 1
    release_manifest_require_clean_worktree "$repo_root"
}

release_manifest_require_clean_worktree() {
    local repo_root=$1

    [ -z "$(git -C "$repo_root" status --porcelain --untracked-files=normal 2>/dev/null)" ] || return 1
}

release_manifest_verify_jar() {
    local jar_path=$1

    [ -f "$jar_path" ] || return 1
    unzip -tqq "$jar_path" >/dev/null 2>&1
}

release_manifest_validate() {
    local manifest=$1
    local repo_root=$2
    local destination=${3:-}
    local format success build_commit backend_tree jar_sha jar_relative
    local jdk_vendor jdk_version maven_command target_tests
    local current_tree built_tree manifest_dir jar_path actual_sha destination_tmp

    [ -f "$manifest" ] || return 1
    release_manifest_require_clean_exact_origin_main "$repo_root" || return 1

    format=$(release_manifest_field "$manifest" format) || return 1
    success=$(release_manifest_field "$manifest" success) || return 1
    build_commit=$(release_manifest_field "$manifest" build_commit) || return 1
    backend_tree=$(release_manifest_field "$manifest" backend_tree) || return 1
    jar_sha=$(release_manifest_field "$manifest" jar_sha256) || return 1
    jar_relative=$(release_manifest_field "$manifest" jar_path) || return 1
    jdk_vendor=$(release_manifest_field "$manifest" jdk_vendor) || return 1
    jdk_version=$(release_manifest_field "$manifest" jdk_version) || return 1
    maven_command=$(release_manifest_field "$manifest" maven_command) || return 1
    target_tests=$(release_manifest_field "$manifest" target_tests) || return 1

    [ "$format" = "$RELEASE_MANIFEST_FORMAT" ] || return 1
    [ "$success" = "true" ] || return 1
    [[ "$build_commit" =~ ^[0-9a-fA-F]{40}$ ]] || return 1
    [[ "$backend_tree" =~ ^[0-9a-fA-F]{40}$ ]] || return 1
    [[ "$jar_sha" =~ ^[0-9a-fA-F]{64}$ ]] || return 1
    [ "$jar_relative" = "$RELEASE_JAR_NAME" ] || return 1
    [ -n "$jdk_vendor" ] && [ -n "$jdk_version" ] || return 1
    [ -n "$maven_command" ] && [ -n "$target_tests" ] || return 1

    current_tree=$(git -C "$repo_root" rev-parse "origin/main:$RELEASE_BACKEND_PATH" 2>/dev/null) || return 1
    [ "$backend_tree" = "$current_tree" ] || return 1
    git -C "$repo_root" cat-file -e "${build_commit}^{commit}" 2>/dev/null || return 1
    built_tree=$(git -C "$repo_root" rev-parse "${build_commit}:$RELEASE_BACKEND_PATH" 2>/dev/null) || return 1
    [ "$built_tree" = "$current_tree" ] || return 1

    manifest_dir=$(cd "$(dirname "$manifest")" 2>/dev/null && pwd) || return 1
    jar_path="$manifest_dir/$jar_relative"
    release_manifest_verify_jar "$jar_path" || return 1
    actual_sha=$(sha256sum "$jar_path" 2>/dev/null | awk '{print tolower($1)}') || return 1
    [ "$(printf '%s' "$jar_sha" | tr '[:upper:]' '[:lower:]')" = "$actual_sha" ] || return 1

    if [ -n "$destination" ]; then
        mkdir -p "$(dirname "$destination")" || return 1
        destination_tmp="${destination}.release-manifest.$$"
        cp "$jar_path" "$destination_tmp" || { rm -f "$destination_tmp"; return 1; }
        mv -f "$destination_tmp" "$destination" || { rm -f "$destination_tmp"; return 1; }
    fi

    RELEASE_MANIFEST_BUILD_COMMIT=$build_commit
    RELEASE_MANIFEST_BACKEND_TREE=$backend_tree
    RELEASE_MANIFEST_JAR_SHA256=$actual_sha
    RELEASE_MANIFEST_TARGET_TESTS=$target_tests
    return 0
}

release_manifest_java_property() {
    local property=$1
    local java_cmd=java

    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        java_cmd="$JAVA_HOME/bin/java"
    elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java.exe" ]; then
        java_cmd="$JAVA_HOME/bin/java.exe"
    fi
    "$java_cmd" -XshowSettings:properties -version 2>&1 \
        | awk -v property="$property" '
            $0 ~ "^[[:space:]]*" property "[[:space:]]*=" {
                sub(/^[^=]*=[[:space:]]*/, "")
                sub(/\r$/, "")
                print
                exit
            }
        '
}

release_manifest_write() {
    local repo_root=$1
    local source_jar=$2
    local manifest=$3
    local maven_command=$4
    local target_tests=$5
    local build_wall_seconds=${6:-0}
    local build_commit backend_tree jdk_vendor jdk_version cache_dir jar_tmp manifest_tmp jar_sha jar_size
    local report report_tmp

    release_manifest_require_clean_worktree "$repo_root" || return 1
    release_manifest_verify_jar "$source_jar" || return 1
    case "$maven_command$target_tests" in
        *$'\n'*|*$'\r'*) return 1 ;;
    esac
    case "$build_wall_seconds" in ''|*[!0-9]*) return 1 ;; esac

    build_commit=$(git -C "$repo_root" rev-parse HEAD 2>/dev/null) || return 1
    backend_tree=$(git -C "$repo_root" rev-parse "HEAD:$RELEASE_BACKEND_PATH" 2>/dev/null) || return 1
    jdk_vendor=$(release_manifest_java_property java.vendor) || return 1
    jdk_version=$(release_manifest_java_property java.version) || return 1
    [ -n "$jdk_vendor" ] && [ -n "$jdk_version" ] || return 1

    cache_dir=$(dirname "$manifest")
    mkdir -p "$cache_dir" || return 1
    jar_tmp="$cache_dir/.${RELEASE_JAR_NAME}.$$"
    manifest_tmp="$cache_dir/.${RELEASE_MANIFEST_NAME}.$$"
    cp "$source_jar" "$jar_tmp" || { rm -f "$jar_tmp"; return 1; }
    jar_sha=$(sha256sum "$jar_tmp" | awk '{print tolower($1)}') || { rm -f "$jar_tmp"; return 1; }
    jar_size=$(wc -c < "$jar_tmp" | tr -d '[:space:]') || { rm -f "$jar_tmp"; return 1; }
    report="${CRETAS_RELEASE_BUILD_REPORT_PATH:-$cache_dir/$RELEASE_BUILD_REPORT_NAME}"
    mkdir -p "$(dirname "$report")" || { rm -f "$jar_tmp"; return 1; }
    report_tmp="${report}.release-manifest.$$"

    {
        printf 'format=%s\n' "$RELEASE_MANIFEST_FORMAT"
        printf 'success=true\n'
        printf 'build_commit=%s\n' "$build_commit"
        printf 'backend_tree=%s\n' "$backend_tree"
        printf 'jar_sha256=%s\n' "$jar_sha"
        printf 'jar_path=%s\n' "$RELEASE_JAR_NAME"
        printf 'jdk_vendor=%s\n' "$jdk_vendor"
        printf 'jdk_version=%s\n' "$jdk_version"
        printf 'maven_command=%s\n' "$maven_command"
        printf 'target_tests=%s\n' "$target_tests"
        printf 'maven_wall_seconds=%s\n' "$build_wall_seconds"
        printf 'jar_size_bytes=%s\n' "$jar_size"
    } > "$manifest_tmp" || { rm -f "$jar_tmp" "$manifest_tmp"; return 1; }

    {
        printf '{\n'
        printf '  "format": "cretas-release-jar-report-v1",\n'
        printf '  "success": true,\n'
        printf '  "build_commit": "%s",\n' "$build_commit"
        printf '  "backend_tree": "%s",\n' "$backend_tree"
        printf '  "jar_sha256": "%s",\n' "$jar_sha"
        printf '  "jar_size_bytes": %s,\n' "$jar_size"
        printf '  "maven_wall_seconds": %s,\n' "$build_wall_seconds"
        printf '  "target_tests": "%s"\n' "$(printf '%s' "$target_tests" | sed 's/\\/\\\\/g; s/"/\\"/g')"
        printf '}\n'
    } > "$report_tmp" || { rm -f "$jar_tmp" "$manifest_tmp" "$report_tmp"; return 1; }

    mv -f "$jar_tmp" "$cache_dir/$RELEASE_JAR_NAME" \
        && mv -f "$report_tmp" "$report" \
        && mv -f "$manifest_tmp" "$manifest" \
        || { rm -f "$jar_tmp" "$manifest_tmp" "$report_tmp"; return 1; }
    return 0
}

# Decide whether the cached JAR already IS the artifact this build would produce.
#
# backend_tree is the git tree hash of backend/java/cretas-api, i.e. a hash of
# content, not of history. A rebase, a squash merge, or a commit that only
# touched web-admin all leave it identical, and the previously built JAR remains
# exactly correct. Recompiling in that case costs ~3 minutes and buys nothing.
#
# Reuse is deliberately conservative and requires ALL of:
#   - a well-formed manifest recording a successful build
#   - the recorded target-test selector equals the requested one (otherwise the
#     cached JAR was gated by a different test set and reuse would skip tests)
#   - the recorded backend tree equals HEAD's backend tree
#   - the cached JAR still exists, is a readable archive, and matches its SHA
# Anything else falls through to a real compile. This mirrors the checks in
# release_manifest_validate, except it anchors on HEAD instead of origin/main
# because the build phase legitimately runs on a reviewed feature branch.
release_manifest_build_reusable() {
    local repo_root=$1
    local target_tests=$2
    local manifest=$3
    local format success backend_tree jar_sha jar_relative recorded_tests
    local current_tree manifest_dir jar_path actual_sha

    [ -f "$manifest" ] || return 1

    format=$(release_manifest_field "$manifest" format) || return 1
    [ "$format" = "$RELEASE_MANIFEST_FORMAT" ] || return 1
    success=$(release_manifest_field "$manifest" success) || return 1
    [ "$success" = "true" ] || return 1

    recorded_tests=$(release_manifest_field "$manifest" target_tests) || return 1
    [ "$recorded_tests" = "$target_tests" ] || return 1

    backend_tree=$(release_manifest_field "$manifest" backend_tree) || return 1
    [[ "$backend_tree" =~ ^[0-9a-fA-F]{40}$ ]] || return 1
    current_tree=$(git -C "$repo_root" rev-parse "HEAD:$RELEASE_BACKEND_PATH" 2>/dev/null) || return 1
    [ "$backend_tree" = "$current_tree" ] || return 1

    jar_relative=$(release_manifest_field "$manifest" jar_path) || return 1
    [ "$jar_relative" = "$RELEASE_JAR_NAME" ] || return 1
    jar_sha=$(release_manifest_field "$manifest" jar_sha256) || return 1
    [[ "$jar_sha" =~ ^[0-9a-fA-F]{64}$ ]] || return 1

    manifest_dir=$(cd "$(dirname "$manifest")" 2>/dev/null && pwd) || return 1
    jar_path="$manifest_dir/$jar_relative"
    release_manifest_verify_jar "$jar_path" || return 1
    actual_sha=$(sha256sum "$jar_path" 2>/dev/null | awk '{print tolower($1)}') || return 1
    [ "$(printf '%s' "$jar_sha" | tr '[:upper:]' '[:lower:]')" = "$actual_sha" ] || return 1

    RELEASE_MANIFEST_REUSED_TREE=$backend_tree
    return 0
}

release_manifest_build() {
    local repo_root=$1
    local target_tests=$2
    local manifest=$3
    local backend_dir="$repo_root/$RELEASE_BACKEND_PATH"
    local jar_path="$backend_dir/target/$RELEASE_JAR_NAME"
    local wrapper command_text build_started_at build_wall_seconds

    [ -n "$target_tests" ] || { echo "ERROR: --tests requires a non-empty Maven test selector" >&2; return 2; }
    case "$target_tests" in *$'\n'*|*$'\r'*) return 2 ;; esac
    release_manifest_require_clean_worktree "$repo_root" \
        || { echo "ERROR: manifest build requires a clean worktree" >&2; return 1; }

    if [ -z "${CRETAS_RELEASE_FORCE_JAVA_BUILD:-}" ] \
        && release_manifest_build_reusable "$repo_root" "$target_tests" "$manifest"; then
        printf 'Release JAR reused: backend tree %s and target tests unchanged; skipping Maven\n' \
            "$RELEASE_MANIFEST_REUSED_TREE"
        printf 'Release manifest: %s\n' "$manifest"
        printf 'Release build report: %s\n' "${CRETAS_RELEASE_BUILD_REPORT_PATH:-$(dirname "$manifest")/$RELEASE_BUILD_REPORT_NAME}"
        return 0
    fi

    if [ -n "${CRETAS_MAVEN_WRAPPER:-}" ]; then
        wrapper=$CRETAS_MAVEN_WRAPPER
    elif [[ "${OSTYPE:-}" == darwin* || "${OSTYPE:-}" == linux* ]]; then
        wrapper=./mvnw
    else
        wrapper=./mvnw.cmd
    fi
    command_text="$wrapper clean package -Dtest=$target_tests"
    build_started_at=$(date +%s)

    (
        cd "$backend_dir"
        [ ! -f "$wrapper" ] || chmod +x "$wrapper" 2>/dev/null || true
        "$wrapper" clean package "-Dtest=$target_tests"
    ) || return 1
    build_wall_seconds=$(( $(date +%s) - build_started_at ))

    release_manifest_require_clean_worktree "$repo_root" \
        || { echo "ERROR: worktree changed during release build; manifest not written" >&2; return 1; }
    release_manifest_write "$repo_root" "$jar_path" "$manifest" "$command_text" "$target_tests" "$build_wall_seconds" || return 1
    printf 'Release manifest: %s\n' "$manifest"
    printf 'Release build report: %s\n' "${CRETAS_RELEASE_BUILD_REPORT_PATH:-$(dirname "$manifest")/$RELEASE_BUILD_REPORT_NAME}"
}

release_manifest_usage() {
    cat <<'EOF'
Usage:
  ./scripts/deploy/release-jar-manifest.sh build --tests <MavenTestSelector> [--manifest <path>]
  ./scripts/deploy/release-jar-manifest.sh validate [--manifest <path>] [--destination <jar>]

The build entry runs exactly one `mvn clean package -Dtest=<selector>` and writes
a manifest-backed release JAR. Deploy validation requires a clean HEAD exactly
equal to origin/main; the recorded build commit may differ when its backend tree
is identical (for example after a squash merge).
EOF
}

release_manifest_main() {
    local command=${1:-}
    local script_dir repo_root manifest tests= destination=
    shift || true
    script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
    repo_root=$(cd "$script_dir/../.." && pwd)
    manifest=$(release_manifest_default_path)

    while [ $# -gt 0 ]; do
        case "$1" in
            --tests) tests=${2:-}; shift 2 ;;
            --manifest) manifest=${2:-}; shift 2 ;;
            --destination) destination=${2:-}; shift 2 ;;
            -h|--help) release_manifest_usage; return 0 ;;
            *) echo "ERROR: unknown argument: $1" >&2; release_manifest_usage >&2; return 2 ;;
        esac
    done

    case "$command" in
        build) release_manifest_build "$repo_root" "$tests" "$manifest" ;;
        validate) release_manifest_validate "$manifest" "$repo_root" "$destination" ;;
        *) release_manifest_usage >&2; return 2 ;;
    esac
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    set -euo pipefail
    release_manifest_main "$@"
fi
