#!/usr/bin/env bash
set -euo pipefail

status="${1:-ready}"
push_after="${2:-}"

if [ "$status" != "ready" ] && [ "$status" != "review" ]; then
  echo 'Usage: daily-integration-mark-ready.sh [ready|review] [--push]' >&2
  exit 2
fi

branch="$(git branch --show-current)"
case "$branch" in
  codex/*) ;;
  *) echo "Refusing to mark non-codex branch: ${branch:-detached HEAD}" >&2; exit 1 ;;
esac

if [ -n "$(git status --porcelain)" ]; then
  echo 'Worktree must be clean before it can be marked for integration.' >&2
  exit 1
fi

git commit --allow-empty \
  -m "chore: mark ${branch} ${status} for daily integration" \
  -m "Integration-Status: ${status}"

echo "Marked ${branch} as ${status}. The scheduler only sees this after push."
if [ "$push_after" = "--push" ]; then
  git push -u origin "$branch"
elif [ -n "$push_after" ]; then
  echo "Unknown option: $push_after" >&2
  exit 2
else
  echo "Push with: git push -u origin $branch"
fi
