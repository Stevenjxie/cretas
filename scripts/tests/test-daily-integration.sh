#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/maintenance/daily-integration.sh"
TMP="$(mktemp -d)"
trap '[ "${KEEP_TMP:-0}" = "1" ] || rm -rf "$TMP"' EXIT

git init --bare "$TMP/remote.git" >/dev/null
git init -b main "$TMP/seed" >/dev/null
git -C "$TMP/seed" config user.name test
git -C "$TMP/seed" config user.email test@example.com
echo base > "$TMP/seed/shared.txt"
git -C "$TMP/seed" add shared.txt
git -C "$TMP/seed" commit -m base >/dev/null
git -C "$TMP/seed" remote add origin "$TMP/remote.git"
git -C "$TMP/seed" push -u origin main >/dev/null
git --git-dir="$TMP/remote.git" symbolic-ref HEAD refs/heads/main

make_branch() {
  local branch="$1" marker="$2" file="$3" content="$4"
  git -C "$TMP/seed" checkout -B "$branch" main >/dev/null
  printf '%s\n' "$content" > "$TMP/seed/$file"
  git -C "$TMP/seed" add "$file"
  if [ -n "$marker" ]; then
    git -C "$TMP/seed" commit -m "$branch" -m "Integration-Status: $marker" >/dev/null
  else
    git -C "$TMP/seed" commit -m "$branch" >/dev/null
  fi
  git -C "$TMP/seed" push -f origin "$branch" >/dev/null
}

make_branch codex/ready ready ready.txt ready
make_branch codex/review review review.txt review
make_branch codex/unmarked '' unmarked.txt unmarked

# Create two individually valid branches that conflict when integrated in order.
make_branch codex/conflict-a ready shared.txt A
make_branch codex/conflict-b ready shared.txt B

git clone "$TMP/remote.git" "$TMP/run" >/dev/null
REPORT="$TMP/report.md"
(
  cd "$TMP/run"
  DRY_RUN=1 DAILY_INTEGRATION_DAY=2026-07-16 \
    DAILY_INTEGRATION_REPORT="$REPORT" bash "$SCRIPT"
) > "$TMP/output.txt"

grep -q '| `codex/ready` | `ready` | included |' "$REPORT"
grep -q '| `codex/review` | `review` | included |' "$REPORT"
grep -q '| `codex/unmarked` | `-` | skipped: no explicit marker |' "$REPORT"
grep -q 'conflict: recorded, not included' "$REPORT"
grep -q 'Local dirty worktrees: unavailable' "$REPORT"

# Dry-run must never create an integration branch or PR side effect.
if git --git-dir="$TMP/remote.git" show-ref --verify --quiet refs/heads/daily/integration-2026-07-16; then
  echo 'FAIL: dry-run pushed an integration branch' >&2
  exit 1
fi

# The same dated head is queried across all PR states. An open PR is edited;
# a closed/merged PR returns before any second create call.
grep -q 'gh pr list --state all --head "$INTEGRATION_BRANCH"' "$SCRIPT"
grep -q 'gh pr edit "$existing_pr"' "$SCRIPT"
grep -q 'no second PR is created today' "$SCRIPT"

# The helper uses an empty marker commit, avoiding history rewrites.
git -C "$TMP/seed" checkout codex/conflict-b >/dev/null
(
  cd "$TMP/seed"
  bash "$REPO_ROOT/scripts/maintenance/daily-integration-mark-ready.sh" review >/dev/null
)
test "$(git -C "$TMP/seed" log -1 --format='%(trailers:key=Integration-Status,valueonly)')" = review

grep -q '  workflow_dispatch:' "$REPO_ROOT/.github/workflows/daily-integration.yml"
if grep -q '  schedule:' "$REPO_ROOT/.github/workflows/daily-integration.yml"; then
  echo 'daily integration must not consume GitHub runners on a schedule' >&2
  exit 1
fi
grep -q 'cancel-in-progress: false' "$REPO_ROOT/.github/workflows/daily-integration.yml"
grep -q 'pull-requests: write' "$REPO_ROOT/.github/workflows/daily-integration.yml"

python - "$REPO_ROOT" <<'PY'
import pathlib, sys, yaml
root = pathlib.Path(sys.argv[1])
for workflow in (root / '.github/workflows').glob('*.yml'):
    yaml.safe_load(workflow.read_text(encoding='utf-8'))

ci = (root / '.github/workflows/ci.yml').read_text(encoding='utf-8')
e2e = (root / '.github/workflows/e2e-pr.yml').read_text(encoding='utf-8')
post_deploy = (root / '.github/workflows/e2e-post-deploy.yml').read_text(encoding='utf-8')
assert 'JPA repository query startup gate' in ci
assert 'full_audit:' in ci
assert 'pr-batch-policy:' not in ci
assert 'dorny/paths-filter' not in ci
assert 'deploy-staging:' not in ci and 'deploy-prod:' not in ci
assert 'pgvector/pgvector:pg17' in e2e
assert 'Start Java backend (background)' in e2e
assert '\n  schedule:' not in post_deploy, 'post-deploy E2E must be explicit, not nightly'
for name in (
    'ci.yml',
    'daily-integration.yml',
    'e2e-pr.yml',
    'e2e-post-deploy.yml',
    'kb-drift-check.yml',
    'threshold-parity-check.yml',
    'tool-isolation-audit.yml',
):
    text = (root / '.github/workflows' / name).read_text(encoding='utf-8')
    assert '\n  workflow_dispatch:' in text, f'{name} must retain a manual fallback'
    assert '\n  push:' not in text, f'{name} must not run on push'
    assert '\n  pull_request:' not in text, f'{name} must not run on pull requests'
    assert '\n  schedule:' not in text, f'{name} must not run on a schedule'
PY

echo 'PASS: selection, conflict recording, same-day PR idempotency, workflow YAML, and schedule'
