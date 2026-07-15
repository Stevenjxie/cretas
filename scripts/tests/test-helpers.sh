#!/usr/bin/env bash
set -euo pipefail

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

assert_contains() {
    [[ "$1" == *"$2"* ]] || fail "missing [$2]"
}

assert_exit() {
    local expected="$1"
    shift
    set +e
    "$@"
    local actual=$?
    set -e
    [[ "$actual" == "$expected" ]] || fail "expected exit $expected, got $actual"
}
