#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

assert_contains() {
    local expected=$1
    grep -Fq -- "$expected" "$DEPLOY_SCRIPT" || fail "missing contract: $expected"
}

# Extract only the nested helper; this executes no deploy setup or network code.
ARTIFACT_HELPER=$(awk '
    /^    reuse_exact_ci_artifact\(\) \{/ {copy = 1}
    copy {
        line = $0
        sub(/^    /, "", line)
        print line
    }
    copy && $0 == "    }" {exit}
' "$DEPLOY_SCRIPT")
eval "$ARTIFACT_HELPER"

FIXTURE_REPO="$TMP_ROOT/repo"
FIXTURE_ARTIFACT="$TMP_ROOT/artifact"
mkdir -p "$FIXTURE_REPO" "$FIXTURE_ARTIFACT"
(
    cd "$FIXTURE_REPO"
    git init -q -b main
    git config user.name test
    git config user.email test@example.com
    printf 'fixture\n' > source.txt
    git add source.txt
    git commit -qm fixture
    git update-ref refs/remotes/origin/main HEAD
)

JAR_NAME="cretas-backend-system-1.0.0.jar"
REPO="Stevenjxie/cretas"
HAS_GH=false
UPLOAD_STATUS_DIR="$TMP_ROOT/status"
HEAD_SHA=$(git -C "$FIXTURE_REPO" rev-parse HEAD)
printf 'verified jar bytes\n' > "$FIXTURE_ARTIFACT/$JAR_NAME"
(
    cd "$FIXTURE_ARTIFACT"
    sha256sum "$JAR_NAME" > "$JAR_NAME.sha256"
    printf '%s\n' "$HEAD_SHA" > "$JAR_NAME.commit"
)

# A valid exact-origin/main artifact is copied into the normal Maven target.
(
    cd "$FIXTURE_REPO"
    ENABLE_CI_ARTIFACT_REUSE=1 CI_ARTIFACT_TEST_DIR="$FIXTURE_ARTIFACT"
    reuse_exact_ci_artifact
    cmp "$FIXTURE_ARTIFACT/$JAR_NAME" "backend/java/cretas-api/target/$JAR_NAME"
) || fail "valid exact-commit artifact was not reused"

# A race candidate must be written to its isolated destination instead of
# sharing Maven's target directory.
(
    cd "$FIXTURE_REPO"
    rm -f "backend/java/cretas-api/target/$JAR_NAME"
    ENABLE_CI_ARTIFACT_REUSE=1 CI_ARTIFACT_TEST_DIR="$FIXTURE_ARTIFACT"
    reuse_exact_ci_artifact "$TMP_ROOT/isolated/$JAR_NAME"
    cmp "$FIXTURE_ARTIFACT/$JAR_NAME" "$TMP_ROOT/isolated/$JAR_NAME"
    [ ! -f "backend/java/cretas-api/target/$JAR_NAME" ]
) || fail "race artifact candidate was not isolated from Maven target"

# Explicit disable must bypass even a valid injected artifact.
if (
    cd "$FIXTURE_REPO"
    ENABLE_CI_ARTIFACT_REUSE=1 DISABLE_CI_ARTIFACT_REUSE=1 CI_ARTIFACT_TEST_DIR="$FIXTURE_ARTIFACT" reuse_exact_ci_artifact
); then
    fail "DISABLE_CI_ARTIFACT_REUSE=1 did not disable reuse"
fi

# Default behavior must not touch even an injected CI source. CI reuse is a
# manual, already-existing fallback only.
if (
    cd "$FIXTURE_REPO"
    CI_ARTIFACT_TEST_DIR="$FIXTURE_ARTIFACT" reuse_exact_ci_artifact
); then
    fail "CI artifact reuse ran without explicit opt-in"
fi

# A commit manifest mismatch must fail closed.
printf 'wrong-commit\n' > "$FIXTURE_ARTIFACT/$JAR_NAME.commit"
if (
    cd "$FIXTURE_REPO"
    ENABLE_CI_ARTIFACT_REUSE=1 CI_ARTIFACT_TEST_DIR="$FIXTURE_ARTIFACT" reuse_exact_ci_artifact
); then
    fail "commit manifest mismatch was accepted"
fi
printf '%s\n' "$HEAD_SHA" > "$FIXTURE_ARTIFACT/$JAR_NAME.commit"

# A missing integrity manifest must fail closed rather than reaching upload.
rm "$FIXTURE_ARTIFACT/$JAR_NAME.sha256"
if (
    cd "$FIXTURE_REPO"
    ENABLE_CI_ARTIFACT_REUSE=1 CI_ARTIFACT_TEST_DIR="$FIXTURE_ARTIFACT" reuse_exact_ci_artifact
); then
    fail "missing checksum manifest was accepted"
fi
(
    cd "$FIXTURE_ARTIFACT"
    sha256sum "$JAR_NAME" > "$JAR_NAME.sha256"
)

# A checksum that does not name and hash the exact JAR must fail closed.
printf '%064d  another.jar\n' 0 > "$FIXTURE_ARTIFACT/$JAR_NAME.sha256"
if (
    cd "$FIXTURE_REPO"
    ENABLE_CI_ARTIFACT_REUSE=1 CI_ARTIFACT_TEST_DIR="$FIXTURE_ARTIFACT" reuse_exact_ci_artifact
); then
    fail "checksum manifest for another file was accepted"
fi
(
    cd "$FIXTURE_ARTIFACT"
    sha256sum "$JAR_NAME" > "$JAR_NAME.sha256"
)

# HEAD drift from origin/main must reject an otherwise valid artifact.
(
    cd "$FIXTURE_REPO"
    printf 'new commit\n' >> source.txt
    git add source.txt
    git commit -qm drift
)
if (
    cd "$FIXTURE_REPO"
    ENABLE_CI_ARTIFACT_REUSE=1 CI_ARTIFACT_TEST_DIR="$FIXTURE_ARTIFACT" reuse_exact_ci_artifact
); then
    fail "non-origin/main HEAD reused a main artifact"
fi

# Missing/unavailable injected download fails immediately; caller retains the
# existing local clean-package fallback contract.
git -C "$FIXTURE_REPO" update-ref refs/remotes/origin/main HEAD
if (
    cd "$FIXTURE_REPO"
    ENABLE_CI_ARTIFACT_REUSE=1 CI_ARTIFACT_TEST_DIR="$TMP_ROOT/missing" reuse_exact_ci_artifact
); then
    fail "unavailable artifact source was accepted"
fi

assert_contains '.name == \"$ARTIFACT_NAME\"'
assert_contains 'GH_HTTP_TIMEOUT="${CI_ARTIFACT_DOWNLOAD_TIMEOUT:-180}" gh api'
assert_contains 'run_first_success_build_race "$BUILD_RACE_DIR" "ci" ci_build_candidate "maven" maven_build_candidate'
assert_contains 'build_local_jar "clean package"'

echo "PASS: exact origin/main artifact reuse validates name, commit, and SHA-256 with safe fallback"
