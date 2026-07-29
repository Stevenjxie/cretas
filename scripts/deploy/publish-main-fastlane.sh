#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
REMOTE="origin"
BASE_SHA=""
CONFIRM=""
HIGH_RISK_CONFIRM=""
TASK_ID=""
DRY_RUN=0

usage() {
    cat <<'EOF'
Usage: publish-main-fastlane.sh --base-sha <sha> --confirm YES-DIRECT-MAIN
       [--remote origin] [--task-id <ID>]
       [--allow-high-risk YES-HIGH-RISK-REVIEWED] [--dry-run]

Fast-forward a reviewed codex/* branch directly to main without a PR. The
command never force-pushes. Production deployment remains a separate step.
Docs-only batches (docs/, .claude/, *.md; not touching docs/dispatch/) skip
the ACTIVE ledger gate; high-risk path gating always applies.

--task-id scopes the ACTIVE gate to one batch: the named task must no longer
be unfinished, while unrelated in-flight tasks owned by other sessions are
ignored. Without it the gate requires an entirely empty in-flight section,
which in practice never holds.
EOF
}

fail() {
    echo "FASTLANE REJECTED: $*" >&2
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --base-sha)
            [[ $# -ge 2 ]] || fail "--base-sha requires a value"
            BASE_SHA="$2"
            shift 2
            ;;
        --remote)
            [[ $# -ge 2 ]] || fail "--remote requires a value"
            REMOTE="$2"
            shift 2
            ;;
        --confirm)
            [[ $# -ge 2 ]] || fail "--confirm requires a value"
            CONFIRM="$2"
            shift 2
            ;;
        --allow-high-risk)
            [[ $# -ge 2 ]] || fail "--allow-high-risk requires a value"
            HIGH_RISK_CONFIRM="$2"
            shift 2
            ;;
        --task-id)
            [[ $# -ge 2 ]] || fail "--task-id requires a value"
            TASK_ID="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            fail "unknown argument: $1"
            ;;
    esac
done

[[ -n "$BASE_SHA" ]] || fail "--base-sha is required"
[[ "$CONFIRM" == "YES-DIRECT-MAIN" ]] \
    || fail "explicit --confirm YES-DIRECT-MAIN is required"
[[ "$REMOTE" =~ ^[A-Za-z0-9._-]+$ ]] || fail "remote must be a configured Git remote name"

cd "$ROOT_DIR"

branch=$(git symbolic-ref --quiet --short HEAD 2>/dev/null) \
    || fail "detached HEAD is not eligible"
[[ "$branch" == codex/* ]] || fail "branch must use the codex/* prefix (got $branch)"

[[ -z "$(git status --porcelain)" ]] || fail "worktree must be clean"
git cat-file -e "${BASE_SHA}^{commit}" 2>/dev/null || fail "base commit does not resolve: $BASE_SHA"

git fetch "$REMOTE" main --quiet
remote_main=$(git rev-parse "$REMOTE/main")
[[ "$remote_main" == "$BASE_SHA" ]] \
    || fail "origin/main advanced: expected $BASE_SHA, found $remote_main"

head_sha=$(git rev-parse HEAD)
git merge-base --is-ancestor "$BASE_SHA" "$head_sha" \
    || fail "HEAD is not a descendant of the registered base"

if [[ -n "$(git rev-list --merges "$BASE_SHA..$head_sha")" ]]; then
    fail "fastlane history must be linear; merge commits require the PR path"
fi

changed_files=$(git diff --name-only "$BASE_SHA..$head_sha")
[[ -n "$changed_files" ]] || fail "no committed changes to publish"
git -c core.whitespace=cr-at-eol diff --check "$BASE_SHA..$head_sha"

# Docs-only batches are not ledger-registered work; the ACTIVE gate exists so a
# coordinator archives its OWN batch in the same commit. Skip it when every
# changed file is docs-class AND none touches the ledger itself. High-risk path
# gating below still applies unconditionally.
docs_only=1
while IFS= read -r path; do
    case "$path" in
        docs/dispatch/*) docs_only=0; break ;;
        docs/*|.claude/*|*.md|.gitignore) ;;
        *) docs_only=0; break ;;
    esac
done <<<"$changed_files"

if [[ -f docs/dispatch/ACTIVE.md && "$docs_only" -ne 1 ]]; then
    in_flight=$(awk '
        /^## Scope / { exit }
        { print }
    ' docs/dispatch/ACTIVE.md)
    if [[ -n "$TASK_ID" ]]; then
        # Scoped gate. The ledger normally carries dozens of unrelated in-flight
        # tasks owned by other sessions, which makes the unscoped form reject
        # every non-docs publish regardless of the caller's own state. The gate
        # exists so a coordinator archives ITS OWN batch in the same commit, so
        # check exactly that: the caller's row must no longer be unfinished.
        if grep -F -- "$TASK_ID" <<<"$in_flight" \
            | grep -Eq '`(queued|claimed|in-progress|review|blocked)`'; then
            fail "task $TASK_ID is still unfinished in ACTIVE; archive it in the same commit"
        fi
    elif grep -Eq '`(queued|claimed|in-progress|review|blocked)`' <<<"$in_flight"; then
        fail "ACTIVE still contains unfinished tasks; archive and release scope locks in the same commit (pass --task-id <ID> to scope this gate to your own batch)"
    fi
fi

high_risk_files=""
while IFS= read -r path; do
    case "$path" in
        AGENTS.md|.codex/rules/*|.agents/skills/*|.github/workflows/*|scripts/deploy/*|\
        backend/java/cretas-api/src/main/resources/db/*|\
        backend/java/cretas-api/src/main/java/com/cretas/aims/entity/*|\
        backend/java/cretas-api/src/main/java/com/cretas/aims/repository/*|\
        backend/java/cretas-api/src/main/java/com/cretas/aims/security/*)
            high_risk_files+="${path}"$'\n'
            ;;
    esac
done <<<"$changed_files"

if [[ -n "$high_risk_files" && "$HIGH_RISK_CONFIRM" != "YES-HIGH-RISK-REVIEWED" ]]; then
    printf 'High-risk files require PR or explicit owner override:\n%s' "$high_risk_files" >&2
    fail "use --allow-high-risk YES-HIGH-RISK-REVIEWED only after the required deep gates pass"
fi

preflight="$ROOT_DIR/scripts/deploy/release-preflight.sh"
[[ -x "$preflight" || -f "$preflight" ]] || fail "release preflight is missing"
bash "$preflight" --allow-non-main --skip-fetch

echo "FASTLANE_READY branch=$branch base=$BASE_SHA head=$head_sha"
if [[ "$DRY_RUN" == "1" ]]; then
    echo "FASTLANE_DRY_RUN: git push $REMOTE HEAD:refs/heads/main"
    exit 0
fi

# Deliberately omit --force/--force-with-lease. A concurrent main advance must
# make this push fail instead of rewriting shared history.
git push "$REMOTE" HEAD:refs/heads/main
git fetch "$REMOTE" main --quiet
published_main=$(git rev-parse "$REMOTE/main")
[[ "$published_main" == "$head_sha" ]] \
    || fail "post-push origin/main mismatch: expected $head_sha, found $published_main"

bash "$preflight" --skip-fetch
echo "FASTLANE_PUBLISHED main=$published_main"
