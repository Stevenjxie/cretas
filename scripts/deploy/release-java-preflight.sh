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
target test classes and their project imports before the single final Maven
clean-package lifecycle. It does not replace compilation or Mockito runtime
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

test_root="$REPO_ROOT/backend/java/cretas-api/src/test/java"
main_root="$REPO_ROOT/backend/java/cretas-api/src/main/java"
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
    local import_name=$1 part path= class_name= candidate
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
    candidate="$main_root$path/$class_name.java"
    [ -f "$candidate" ] && return 0
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
