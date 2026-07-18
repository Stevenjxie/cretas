#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
JAVA_PREFLIGHT="$ROOT_DIR/scripts/deploy/release-java-preflight.sh"
ARTIFACTS_SCRIPT="$ROOT_DIR/scripts/deploy/release-cretas-artifacts.sh"
PARALLEL_DEPLOY="$ROOT_DIR/scripts/deploy/deploy-cretas-parallel.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

make_repo() {
    local repo=$1 remote=$2
    mkdir -p "$repo/backend/java/cretas-api/src/main/java/com/cretas/aims/dto"
    mkdir -p "$repo/backend/java/cretas-api/src/test/java/com/cretas/aims/service"
    mkdir -p "$repo/scripts/deploy"
    printf 'package com.cretas.aims.dto; public class ValidDto {}\n' \
        > "$repo/backend/java/cretas-api/src/main/java/com/cretas/aims/dto/ValidDto.java"
    cat > "$repo/backend/java/cretas-api/src/test/java/com/cretas/aims/service/ValidReleaseTest.java" <<'EOF'
package com.cretas.aims.service;
import com.cretas.aims.dto.ValidDto;
class ValidReleaseTest { ValidDto value; }
EOF
    git -C "$repo" init -q -b main
    git -C "$repo" config user.email fixture@example.com
    git -C "$repo" config user.name Fixture
    git -C "$repo" add .
    git -C "$repo" commit -qm fixture
    git init --bare -q "$remote"
    git -C "$repo" remote add origin "$remote"
    git -C "$repo" push -q -u origin main
    git -C "$repo" fetch -q origin main
}

# The static preflight is deliberately narrow: selector and project-import
# mistakes fail before an expensive release Maven lifecycle.
PREFLIGHT_REPO="$TMP_ROOT/preflight-repo"
make_repo "$PREFLIGHT_REPO" "$TMP_ROOT/preflight-origin.git"
"$JAVA_PREFLIGHT" --repo-root "$PREFLIGHT_REPO" --tests ValidReleaseTest \
    > "$TMP_ROOT/preflight-valid.log"
grep -Fq 'Java release preflight passed: 1 explicit test class(es)' "$TMP_ROOT/preflight-valid.log" \
    || fail "valid selector/import preflight did not pass"

sed -i 's/ValidDto/MissingDto/g' "$PREFLIGHT_REPO/backend/java/cretas-api/src/test/java/com/cretas/aims/service/ValidReleaseTest.java"
if "$JAVA_PREFLIGHT" --repo-root "$PREFLIGHT_REPO" --tests ValidReleaseTest >"$TMP_ROOT/preflight-missing-import.log" 2>&1; then
    fail "unresolved project import was accepted"
fi
grep -Fq 'project import cannot be resolved' "$TMP_ROOT/preflight-missing-import.log" \
    || fail "missing import failure was not actionable"
git -C "$PREFLIGHT_REPO" checkout -- backend/java/cretas-api/src/test/java/com/cretas/aims/service/ValidReleaseTest.java

ARTIFACT_REPO="$TMP_ROOT/artifact-repo"
make_repo "$ARTIFACT_REPO" "$TMP_ROOT/artifact-origin.git"
cp "$JAVA_PREFLIGHT" "$ARTIFACT_REPO/scripts/deploy/release-java-preflight.sh"
cp "$ARTIFACTS_SCRIPT" "$ARTIFACT_REPO/scripts/deploy/release-cretas-artifacts.sh"
cat > "$ARTIFACT_REPO/scripts/deploy/release-jar-manifest.sh" <<'EOF'
#!/usr/bin/env bash
printf 'java:%s\n' "$*" >> "$MOCK_RELEASE_LOG"
EOF
cat > "$ARTIFACT_REPO/scripts/deploy/release-web-manifest.sh" <<'EOF'
#!/usr/bin/env bash
printf 'web:%s\n' "$*" >> "$MOCK_RELEASE_LOG"
EOF
chmod +x "$ARTIFACT_REPO/scripts/deploy/"*.sh
git -C "$ARTIFACT_REPO" add scripts/deploy
git -C "$ARTIFACT_REPO" commit -qm scripts
git -C "$ARTIFACT_REPO" push -q origin main
git -C "$ARTIFACT_REPO" fetch -q origin main
MOCK_RELEASE_LOG="$TMP_ROOT/artifacts.log" bash "$ARTIFACT_REPO/scripts/deploy/release-cretas-artifacts.sh" \
    --tests ValidReleaseTest > "$TMP_ROOT/artifacts-output.log"
grep -Fxq 'java:build --tests ValidReleaseTest' "$TMP_ROOT/artifacts.log" \
    || fail "parallel artifact builder did not invoke Java manifest build"
grep -Fxq 'web:build' "$TMP_ROOT/artifacts.log" \
    || fail "parallel artifact builder did not invoke Web manifest build"
grep -Fq 'Trusted Java + Web artifacts built concurrently' "$TMP_ROOT/artifacts-output.log" \
    || fail "parallel artifact receipt missing"

DEPLOY_REPO="$TMP_ROOT/deploy-repo"
make_repo "$DEPLOY_REPO" "$TMP_ROOT/deploy-origin.git"
cp "$PARALLEL_DEPLOY" "$DEPLOY_REPO/scripts/deploy/deploy-cretas-parallel.sh"
for helper in release-preflight.sh release-jar-manifest.sh release-web-manifest.sh; do
    cat > "$DEPLOY_REPO/scripts/deploy/$helper" <<'EOF'
#!/usr/bin/env bash
printf '%s:%s\n' "$(basename "$0")" "$*" >> "$MOCK_DEPLOY_LOG"
EOF
done
cat > "$DEPLOY_REPO/scripts/deploy/deploy-backend.sh" <<'EOF'
#!/usr/bin/env bash
printf 'backend:%s\n' "$*" >> "$MOCK_DEPLOY_LOG"
EOF
cat > "$DEPLOY_REPO/scripts/deploy/deploy-web-admin.sh" <<'EOF'
#!/usr/bin/env bash
printf 'web:%s\n' "$*" >> "$MOCK_DEPLOY_LOG"
EOF
chmod +x "$DEPLOY_REPO/scripts/deploy/"*.sh
git -C "$DEPLOY_REPO" add scripts/deploy
git -C "$DEPLOY_REPO" commit -qm scripts
git -C "$DEPLOY_REPO" push -q origin main
git -C "$DEPLOY_REPO" fetch -q origin main

MOCK_DEPLOY_LOG="$TMP_ROOT/deploy.log" bash "$DEPLOY_REPO/scripts/deploy/deploy-cretas-parallel.sh" \
    --confirm-prod YES-PROD \
    --confirm-independent-services YES-INDEPENDENT-SERVICES \
    > "$TMP_ROOT/deploy-output.log"
grep -Fxq 'backend:--env prod' "$TMP_ROOT/deploy.log" || fail "parallel deploy did not launch Java child"
grep -Fxq 'web:--env prod --confirm-prod YES-PROD' "$TMP_ROOT/deploy.log" || fail "parallel deploy did not launch Web child"
grep -Fq 'Parallel production release completed' "$TMP_ROOT/deploy-output.log" \
    || fail "parallel deployment receipt missing"

if MOCK_DEPLOY_LOG="$TMP_ROOT/deploy.log" bash "$DEPLOY_REPO/scripts/deploy/deploy-cretas-parallel.sh" \
    --confirm-prod YES-PROD > "$TMP_ROOT/missing-confirm.log" 2>&1; then
    fail "parallel deploy accepted missing independent-service confirmation"
fi
grep -Fq 'YES-INDEPENDENT-SERVICES' "$TMP_ROOT/missing-confirm.log" \
    || fail "missing independent-service confirmation was not actionable"

echo "PASS: Java static preflight and controlled parallel release contracts"
