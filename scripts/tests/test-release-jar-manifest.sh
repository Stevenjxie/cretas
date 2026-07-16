#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
HELPER="$ROOT_DIR/scripts/deploy/release-jar-manifest.sh"
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

source "$HELPER"

FIXTURE_REPO="$TMP_ROOT/repo"
BACKEND="$FIXTURE_REPO/backend/java/cretas-api"
FIXTURE_HELPER="$FIXTURE_REPO/scripts/deploy/release-jar-manifest.sh"
MANIFEST="$TMP_ROOT/cache/current/$RELEASE_MANIFEST_NAME"
DESTINATION="$TMP_ROOT/destination/$RELEASE_JAR_NAME"
MVN_LOG="$TMP_ROOT/mvn.log"
FAKE_MVN="$TMP_ROOT/fake-mvn"
mkdir -p "$BACKEND" "$(dirname "$FIXTURE_HELPER")"
cp "$HELPER" "$FIXTURE_HELPER"
chmod +x "$FIXTURE_HELPER"
(
    cd "$FIXTURE_REPO"
    git init -q -b main
    git config user.name test
    git config user.email test@example.com
    printf '<project/>\n' > backend/java/cretas-api/pom.xml
    printf 'backend/java/cretas-api/target/\n' > .gitignore
    git add .gitignore backend/java/cretas-api/pom.xml scripts/deploy/release-jar-manifest.sh
    git commit -qm backend
    git update-ref refs/remotes/origin/main HEAD
)

# Build on a clean feature commit. The eventual release validator, not the
# producer, requires exact origin/main; this permits squash-merge reuse.
(
    cd "$FIXTURE_REPO"
    printf '<project>feature</project>\n' > backend/java/cretas-api/pom.xml
    git add backend/java/cretas-api/pom.xml
    git commit -qm feature-backend
)
BASE_COMMIT=$(git -C "$FIXTURE_REPO" rev-parse HEAD~1)
BUILD_COMMIT=$(git -C "$FIXTURE_REPO" rev-parse HEAD)

cat > "$FAKE_MVN" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$MVN_LOG"
mkdir -p target/fake-content
printf 'compiled once\n' > target/fake-content/Main.class
(cd target/fake-content && jar --create --file "../cretas-backend-system-1.0.0.jar" Main.class)
EOF
chmod +x "$FAKE_MVN"

TARGET_TESTS='ReleaseDecisionToolTest,RepositoryQueryValidationTest'
MVN_LOG="$MVN_LOG" CRETAS_MAVEN_WRAPPER="$FAKE_MVN" \
    "$FIXTURE_HELPER" build --tests "$TARGET_TESTS" --manifest "$MANIFEST"

[ "$(wc -l < "$MVN_LOG" | tr -d '[:space:]')" = "1" ] \
    || fail "one release invoked Maven more than once"
grep -Fxq "clean package -Dtest=$TARGET_TESTS" "$MVN_LOG" \
    || fail "single Maven invocation did not combine clean package and target tests"
grep -Fxq "target_tests=$TARGET_TESTS" "$MANIFEST" \
    || fail "manifest did not record actual target tests"
grep -Fxq "maven_command=$FAKE_MVN clean package -Dtest=$TARGET_TESTS" "$MANIFEST" \
    || fail "manifest did not record the actual Maven command"
grep -Fq 'jdk_vendor=' "$MANIFEST" || fail "manifest missing JDK vendor"
grep -Fq 'jdk_version=' "$MANIFEST" || fail "manifest missing JDK version"
grep -Fxq 'success=true' "$MANIFEST" || fail "manifest missing success=true"
grep -Fxq "build_commit=$BUILD_COMMIT" "$MANIFEST" \
    || fail "manifest did not record the feature build commit"
git -C "$FIXTURE_REPO" update-ref refs/remotes/origin/main "$BUILD_COMMIT"
release_manifest_validate "$MANIFEST" "$FIXTURE_REPO" "$DESTINATION" \
    || fail "valid same-commit manifest was rejected"
unzip -tqq "$DESTINATION" || fail "validated destination is not a real ZIP/JAR"

# Create a distinct squash-style commit with the exact feature tree and base as
# parent. The build commit remains resolvable but is not the release HEAD.
FEATURE_TREE=$(git -C "$FIXTURE_REPO" rev-parse "$BUILD_COMMIT^{tree}")
SQUASH_COMMIT=$(printf 'squashed feature\n' | git -C "$FIXTURE_REPO" commit-tree "$FEATURE_TREE" -p "$BASE_COMMIT")
git -C "$FIXTURE_REPO" update-ref refs/remotes/origin/main "$SQUASH_COMMIT"
git -C "$FIXTURE_REPO" reset -q --hard "$SQUASH_COMMIT"
[ "$BUILD_COMMIT" != "$SQUASH_COMMIT" ] || fail "squash fixture did not create a different commit"
release_manifest_validate "$MANIFEST" "$FIXTURE_REPO" "$DESTINATION" \
    || fail "squash commit with identical backend tree was rejected"

# A backend tree change must reject the old manifest. The deploy contract then
# immediately selects one local clean package, never a second Java compile.
(
    cd "$FIXTURE_REPO"
    printf '<project>changed</project>\n' > backend/java/cretas-api/pom.xml
    git add backend/java/cretas-api/pom.xml
    git commit -qm backend-change
    git update-ref refs/remotes/origin/main HEAD
)
if release_manifest_validate "$MANIFEST" "$FIXTURE_REPO" "$DESTINATION"; then
    fail "different backend tree reused stale manifest"
fi
grep -Fq 'manifest 未命中，立即执行本地 clean package' "$DEPLOY_SCRIPT" \
    || fail "manifest miss is not wired to immediate local clean package"

# No manifest follows the same clean-build fallback contract.
rm -f "$MANIFEST"
if release_manifest_validate "$MANIFEST" "$FIXTURE_REPO" "$DESTINATION"; then
    fail "missing manifest was accepted"
fi
grep -Fq 'build_local_jar "clean package"' "$DEPLOY_SCRIPT" \
    || fail "missing manifest has no clean-package fallback"
[ "$(awk '/^    build_local_jar\(\) \{/{copy=1} copy{print} copy && /^    \}/{exit}' "$DEPLOY_SCRIPT" \
    | grep -Fc 'run_mvn $goals')" = "1" ] \
    || fail "deploy local builder can invoke Maven more than once per attempt"
if grep -Fq 'run_mvn package ||' "$DEPLOY_SCRIPT"; then
    fail "deploy retains an implicit second package compile after clean-package failure"
fi

# Restore a valid tree/manifest for integrity and dirty-worktree negatives.
git -C "$FIXTURE_REPO" reset -q --hard "$SQUASH_COMMIT"
git -C "$FIXTURE_REPO" update-ref refs/remotes/origin/main "$SQUASH_COMMIT"
SOURCE_JAR="$BACKEND/target/$RELEASE_JAR_NAME"
release_manifest_write "$FIXTURE_REPO" "$SOURCE_JAR" "$MANIFEST" \
    "fake-mvn clean package -Dtest=$TARGET_TESTS" "$TARGET_TESTS"

sed -i 's/^jar_sha256=.*/jar_sha256=0000000000000000000000000000000000000000000000000000000000000000/' "$MANIFEST"
if release_manifest_validate "$MANIFEST" "$FIXTURE_REPO" "$DESTINATION"; then
    fail "SHA mismatch was accepted"
fi
release_manifest_write "$FIXTURE_REPO" "$SOURCE_JAR" "$MANIFEST" \
    "fake-mvn clean package -Dtest=$TARGET_TESTS" "$TARGET_TESTS"

printf 'untracked\n' > "$FIXTURE_REPO/untracked.txt"
if release_manifest_validate "$MANIFEST" "$FIXTURE_REPO" "$DESTINATION"; then
    fail "dirty release worktree with untracked file was accepted"
fi
rm "$FIXTURE_REPO/untracked.txt"

printf 'damaged jar bytes\n' > "$(dirname "$MANIFEST")/$RELEASE_JAR_NAME"
DAMAGED_SHA=$(sha256sum "$(dirname "$MANIFEST")/$RELEASE_JAR_NAME" | awk '{print $1}')
sed -i "s/^jar_sha256=.*/jar_sha256=$DAMAGED_SHA/" "$MANIFEST"
if release_manifest_validate "$MANIFEST" "$FIXTURE_REPO" "$DESTINATION"; then
    fail "damaged JAR with matching SHA was accepted"
fi

# Parser must never execute manifest values.
printf 'maven_command=$(touch %s/pwned)\n' "$TMP_ROOT" >> "$MANIFEST"
release_manifest_validate "$MANIFEST" "$FIXTURE_REPO" "$DESTINATION" >/dev/null 2>&1 || true
[ ! -e "$TMP_ROOT/pwned" ] || fail "manifest parser executed untrusted content"

echo "PASS: release JAR manifest trust, target-test build, fallback, and integrity contracts"
