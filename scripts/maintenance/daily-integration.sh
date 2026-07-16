#!/usr/bin/env bash
set -euo pipefail

# Build one reviewable integration PR per China-local day. GitHub cannot see
# local worktrees or unpushed commits, so only remote codex/* branches whose
# tip commit explicitly contains one of these trailers are eligible:
#
#   Integration-Status: ready
#   Integration-Status: review
#
# Usage:
#   scripts/maintenance/daily-integration.sh
#   DRY_RUN=1 scripts/maintenance/daily-integration.sh

REMOTE="${DAILY_INTEGRATION_REMOTE:-origin}"
BASE_BRANCH="${DAILY_INTEGRATION_BASE:-main}"
BRANCH_GLOB="${DAILY_INTEGRATION_BRANCH_GLOB:-codex/*}"
TIMEZONE="${DAILY_INTEGRATION_TIMEZONE:-Asia/Shanghai}"
DRY_RUN="${DRY_RUN:-0}"
REPORT_PATH="${DAILY_INTEGRATION_REPORT:-${RUNNER_TEMP:-/tmp}/daily-integration-report.md}"

export TZ="$TIMEZONE"
DAY="${DAILY_INTEGRATION_DAY:-$(date +%Y-%m-%d)}"
INTEGRATION_BRANCH="daily/integration-${DAY}"
WORK_DIR=""

cleanup() {
  if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ]; then
    git worktree remove --force "$WORK_DIR" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

status_of_tip() {
  local ref="$1"
  git show -s --format=%B "$ref" \
    | sed -nE 's/^[[:space:]]*Integration-Status:[[:space:]]*(ready|review)[[:space:]]*$/\1/ip' \
    | tail -1 \
    | tr '[:upper:]' '[:lower:]'
}

append_row() {
  local branch="$1" status="$2" outcome="$3" sha="$4"
  printf '| `%s` | `%s` | %s | `%s` |\n' "$branch" "$status" "$outcome" "${sha:0:12}" >> "$REPORT_PATH"
}

remote_pr_number() {
  gh pr list --state all --head "$INTEGRATION_BRANCH" --json number \
    --jq '.[0].number // empty' 2>/dev/null || true
}

remote_pr_state() {
  local number="$1"
  gh pr view "$number" --json state --jq '.state' 2>/dev/null || true
}

main() {
  git rev-parse --git-dir >/dev/null
  if [ "$DRY_RUN" != "1" ]; then
    command -v gh >/dev/null 2>&1 || {
      echo 'gh is required unless DRY_RUN=1' >&2
      return 1
    }
  fi
  git fetch --prune "$REMOTE" \
    "+refs/heads/${BASE_BRANCH}:refs/remotes/${REMOTE}/${BASE_BRANCH}" \
    "+refs/heads/codex/*:refs/remotes/${REMOTE}/codex/*"

  local base_ref="refs/remotes/${REMOTE}/${BASE_BRANCH}"
  git rev-parse --verify "$base_ref" >/dev/null

  mkdir -p "$(dirname "$REPORT_PATH")"
  cat > "$REPORT_PATH" <<EOF
# Daily integration report — ${DAY}

- Base: \`${REMOTE}/${BASE_BRANCH}\` at \`$(git rev-parse "$base_ref")\`
- Selection: pushed \`codex/*\` tips with \`Integration-Status: ready|review\`
- Local dirty worktrees: unavailable to cloud automation and never inferred

| Remote branch | Marker | Outcome | Tip |
|---|---|---|---|
EOF

  local existing_pr=""
  if [ "$DRY_RUN" != "1" ]; then
    existing_pr="$(remote_pr_number)"
    if [ -n "$existing_pr" ] && [ "$(remote_pr_state "$existing_pr")" != "OPEN" ]; then
      printf '\nExisting PR #%s is already closed or merged; no second PR is created today.\n' "$existing_pr" >> "$REPORT_PATH"
      cat "$REPORT_PATH"
      return 0
    fi
  fi

  WORK_DIR="$(mktemp -d "${RUNNER_TEMP:-/tmp}/daily-integration.XXXXXX")"
  rmdir "$WORK_DIR"
  git worktree add --detach "$WORK_DIR" "$base_ref" >/dev/null
  git -C "$WORK_DIR" checkout -B "$INTEGRATION_BRANCH" >/dev/null
  git -C "$WORK_DIR" config user.name "cretas-daily-integration"
  git -C "$WORK_DIR" config user.email "daily-integration@users.noreply.github.com"

  local included=0 branch ref sha marker
  while IFS= read -r ref; do
    [ -n "$ref" ] || continue
    branch="${ref#refs/remotes/${REMOTE}/}"
    sha="$(git rev-parse "$ref")"

    if git merge-base --is-ancestor "$sha" "$base_ref"; then
      append_row "$branch" "-" "already in ${BASE_BRANCH}" "$sha"
      continue
    fi

    marker="$(status_of_tip "$ref")"
    if [ "$marker" != "ready" ] && [ "$marker" != "review" ]; then
      append_row "$branch" "-" "skipped: no explicit marker" "$sha"
      continue
    fi

    if git -C "$WORK_DIR" merge --no-ff --no-edit "$sha" >/dev/null 2>&1; then
      append_row "$branch" "$marker" "included" "$sha"
      included=$((included + 1))
    else
      git -C "$WORK_DIR" merge --abort >/dev/null 2>&1 || true
      append_row "$branch" "$marker" "conflict: recorded, not included" "$sha"
    fi
  done < <(git for-each-ref --format='%(refname)' "refs/remotes/${REMOTE}/${BRANCH_GLOB}" | sort)

  printf '\nIncluded branches: **%s**.\n' "$included" >> "$REPORT_PATH"

  if [ "$DRY_RUN" = "1" ] || [ "$included" -eq 0 ]; then
    cat "$REPORT_PATH"
    return 0
  fi

  # This branch is automation-owned. Rebuilding it from the latest main makes
  # repeated runs on the same day idempotent while still collecting newly-ready
  # branches. --force-with-lease prevents overwriting an unexpected remote edit.
  local remote_sha
  remote_sha="$(git ls-remote --heads "$REMOTE" "refs/heads/${INTEGRATION_BRANCH}" | awk '{print $1}')"
  if [ -n "$remote_sha" ]; then
    git -C "$WORK_DIR" push \
      "--force-with-lease=refs/heads/${INTEGRATION_BRANCH}:${remote_sha}" \
      "$REMOTE" "HEAD:refs/heads/${INTEGRATION_BRANCH}"
  else
    git -C "$WORK_DIR" push "$REMOTE" "HEAD:refs/heads/${INTEGRATION_BRANCH}"
  fi

  local title="chore: daily integration ${DAY}"
  if [ -n "$existing_pr" ]; then
    gh pr edit "$existing_pr" --title "$title" --body-file "$REPORT_PATH" >/dev/null
    echo "Updated PR #${existing_pr}"
  else
    gh pr create --base "$BASE_BRANCH" --head "$INTEGRATION_BRANCH" \
      --title "$title" --body-file "$REPORT_PATH"
  fi
}

if [ "${DAILY_INTEGRATION_LIB_ONLY:-0}" != "1" ]; then
  main "$@"
fi
