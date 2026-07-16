#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
POM="$ROOT_DIR/backend/java/cretas-api/pom.xml"

assert_contains() {
    local expected=$1
    if ! grep -Fq -- "$expected" "$POM"; then
        echo "FAIL: missing '$expected' in $POM" >&2
        exit 1
    fi
}

assert_contains '<checkStaleness>true</checkStaleness>'
assert_contains '<staleMillis>0</staleMillis>'

# Spring Boot's parent already binds repackage. An additional explicit goal
# executes the 170+ MB fat-JAR rewrite twice on every package.
if grep -Fq -- '<goal>repackage</goal>' "$POM"; then
    echo "FAIL: pom.xml explicitly binds Spring Boot repackage more than once" >&2
    exit 1
fi

echo "PASS: Maven warm-build and single-repackage contracts are present"
