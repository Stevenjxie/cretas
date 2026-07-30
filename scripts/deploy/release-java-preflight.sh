#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
DEFAULT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
REPO_ROOT=${RELEASE_JAVA_PREFLIGHT_REPO_ROOT:-$DEFAULT_ROOT}
TESTS=

usage() {
    cat <<'EOF'
Usage: scripts/deploy/release-java-preflight.sh --tests <MavenTestSelector> [--repo-root PATH]

Fast, read-only preflight for a release Maven selector. It validates literal
target test classes and their project imports, and verifies a usable JDK 21+,
before the final Maven clean-package lifecycle (which itself is skipped when the
cached JAR is still valid). Checking the JDK here means a missing JAVA_HOME fails
in seconds rather than one second into the parallel build, after the Web side has
already burned a full compile. It does not replace compilation or Mockito runtime
validation.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --tests) TESTS=${2:-}; shift 2 ;;
        --repo-root) REPO_ROOT=${2:-}; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

[ -n "$TESTS" ] || { echo "ERROR: --tests requires a Maven test selector" >&2; exit 2; }
[ -d "$REPO_ROOT/backend/java/cretas-api/src/test/java" ] || { echo "ERROR: Java test source directory is unavailable" >&2; exit 1; }

# Fail on a missing JDK here, in the read-only preflight, rather than one second
# into the parallel artifact build. `mvnw.cmd` requires JAVA_HOME outright and
# dies with a bare "JAVA_HOME not found" that says nothing about the release;
# meanwhile the Web build keeps compiling for another 60-110s before anyone
# learns the release is already dead.
require_release_jdk() {
    local java_cmd= raw_version major

    if [ -n "${JAVA_HOME:-}" ]; then
        if [ -x "$JAVA_HOME/bin/java" ]; then
            java_cmd="$JAVA_HOME/bin/java"
        elif [ -x "$JAVA_HOME/bin/java.exe" ]; then
            java_cmd="$JAVA_HOME/bin/java.exe"
        else
            echo "ERROR: JAVA_HOME is set but has no runnable bin/java: $JAVA_HOME" >&2
            return 1
        fi
    elif [[ "${OSTYPE:-}" == darwin* || "${OSTYPE:-}" == linux* ]] && command -v java >/dev/null 2>&1; then
        # The POSIX mvnw falls back to java on PATH; mvnw.cmd never does.
        java_cmd=java
    else
        echo "ERROR: JAVA_HOME is unset and no usable JDK was found; the Maven wrapper cannot run" >&2
        echo "       Set JAVA_HOME to a JDK 21 installation before releasing." >&2
        return 1
    fi

    raw_version=$("$java_cmd" -version 2>&1 | sed -nE '1s/.*version "([0-9]+)(\.[0-9]+)*.*/\1/p')
    case "$raw_version" in
        ''|*[!0-9]*)
            echo "ERROR: could not determine the Java version from $java_cmd" >&2
            return 1
            ;;
    esac
    major=$raw_version
    [ "$major" -ge 21 ] 2>/dev/null || {
        echo "ERROR: release requires JDK 21 or newer, found $major from $java_cmd" >&2
        return 1
    }
    return 0
}

require_release_jdk || exit 1

test_root="$REPO_ROOT/backend/java/cretas-api/src/test/java"
# 测试里的 com.cretas.aims.* import 可能落在任何一个后端模块里, 所以这里必须是【全部模块】。
# 少列一个, preflight 就会把合法的选择器判成 "project import cannot be resolved"。
#
# 从聚合 pom 的 <module> 列表推导, 不再硬编码。
#
# Why: 硬编码过一次, 然后就漂了。2026-07-30 拆模块(#2011)新增了 cretas-model /
# cretas-platform / cretas-logistics-app 三个模块, 而这里还写着三个旧的 —— 实测在纯
# origin/main 上, 3 个选择器里 2 个直接失败(Factory 现在住在 cretas-model), 也就是
# release-cretas.sh --phase build 对多数选择器都用不了。按 pom 推导之后, 下次加模块自动跟上。
#
# 读不到就【硬失败】而不是退回一份可能已经过期的硬编码列表: preflight 不知道模块边界就
# 做不了它该做的事, 而一份过期列表恰好会以"合法选择器被拒"的形式表现出来 —— 那正是这次
# 的症状。
aggregator_pom="$REPO_ROOT/backend/java/pom.xml"
if [ ! -f "$aggregator_pom" ]; then
    echo "ERROR: aggregator pom not found: $aggregator_pom" >&2
    exit 1
fi
# `|| true`: 本脚本是 set -euo pipefail, 而 grep 无匹配返回 1 —— 带 pipefail 会让整个赋值
# 失败, 脚本在下面那句明确的错误消息之前就【静默退出】。实测过: 一个没有 <module> 的 pom
# 会让 preflight 一个字都不打就走人。让 grep 不致命, 由下面的空值检查负责吵。
main_roots=$(
    { grep -oE '<module>[^<]+</module>' "$aggregator_pom" || true; } \
        | sed -e 's|<module>||' -e 's|</module>||' \
        | while IFS= read -r module_name; do
            [ -n "$module_name" ] || continue
            candidate="$REPO_ROOT/backend/java/$module_name/src/main/java"
            [ -d "$candidate" ] && printf '%s\n' "$candidate"
        done
)
if [ -z "$main_roots" ]; then
    echo "ERROR: no backend module main source roots resolved from $aggregator_pom" >&2
    exit 1
fi
IFS=',' read -r -a selectors <<< "$TESTS"
test_files=()

selector_to_file() {
    local selector=$1 class_name candidate
    class_name=${selector%%#*}
    case "$class_name" in
        ''|*'*'*|*'?'*|*'!'*)
            echo "ERROR: --tests only accepts explicit test classes for release preflight: $selector" >&2
            return 1
            ;;
    esac
    class_name=${class_name##*.}
    candidate=$(find "$test_root" -type f -name "$class_name.java" -print -quit)
    [ -n "$candidate" ] || { echo "ERROR: test selector has no source file: $selector" >&2; return 1; }
    printf '%s\n' "$candidate"
}

import_exists() {
    local import_name=$1 part path= class_name= candidate main_root
    IFS='.' read -r -a parts <<< "$import_name"
    for part in "${parts[@]}"; do
        # ⛔ 必须用 POSIX 字符类 [[:upper:]], 不能写 [A-Z]。
        # [A-Z] 是**范围**表达式, 按 locale 的 collation 展开: 在 en_US.UTF-8 这类
        # locale 下排序是 aAbBcC... , 于是 [A-Z] 把小写字母也覆盖进去 —— `com` 这种
        # 全小写包名段会在第一段就被判成类名, path 为空、class_name="com",
        # 于是去找 `<main_root>/com.java` 找不到, 报出误导性的
        #   "ERROR: project import cannot be resolved: com.cretas.aims.xxx.Foo"
        # 而那个类其实好端端存在。凡是 --tests 选中的测试类里有 com.cretas.* import
        # 就会中招, 整条发布链在 preflight 阶段直接中止 (2026-07-28 飞轮回接发布实测)。
        # [[:upper:]] 按 locale 的字符**类别**判定, 只匹配真正的大写字母, 不受 collation 影响。
        if [[ "$part" =~ ^[[:upper:]] ]]; then
            class_name=$part
            break
        fi
        path+="/$part"
    done
    [ -n "$class_name" ] || return 0
    while IFS= read -r main_root; do
        [ -n "$main_root" ] || continue
        candidate="$main_root$path/$class_name.java"
        [ -f "$candidate" ] && return 0
    done <<< "$main_roots"
    candidate="$test_root$path/$class_name.java"
    [ -f "$candidate" ] && return 0
    echo "ERROR: project import cannot be resolved: $import_name" >&2
    return 1
}

for selector in "${selectors[@]}"; do
    test_file=$(selector_to_file "$selector")
    test_files+=("$test_file")
done

for test_file in "${test_files[@]}"; do
    while IFS= read -r import_name; do
        import_exists "$import_name"
    done < <(sed -nE 's/^[[:space:]]*import[[:space:]]+(com\.cretas\.aims\.[A-Za-z0-9_\.]+);[[:space:]]*$/\1/p' "$test_file")
done

printf 'Java release preflight passed: %s explicit test class(es), project imports resolved\n' "${#test_files[@]}"
