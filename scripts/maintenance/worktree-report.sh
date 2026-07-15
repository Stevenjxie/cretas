#!/usr/bin/env bash
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE="origin/main"

usage() {
    echo "Usage: worktree-report.sh [--repo PATH] [--base COMMITISH]"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --repo)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            REPO="$2"
            shift 2
            ;;
        --base)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            BASE="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

REPO="$(cd "$REPO" && pwd)"
git -C "$REPO" rev-parse --verify "${BASE}^{commit}" >/dev/null 2>&1 || {
    echo "Base is not a commit: $BASE" >&2
    exit 2
}

merged_pr_heads=""
if command -v gh >/dev/null 2>&1; then
    remote_url="$(git -C "$REPO" remote get-url origin 2>/dev/null || true)"
    repo_slug="$(sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##' <<<"$remote_url")"
    if [[ -n "$repo_slug" ]]; then
        merged_pr_heads="$(gh pr list --repo "$repo_slug" --state merged --limit 1000 \
            --json headRefName --jq '.[].headRefName' 2>/dev/null || true)"
    fi
fi

git -C "$REPO" worktree list --porcelain |
awk '/^worktree / { print substr($0, 10) }' |
while IFS= read -r path; do
    branch="$(git -C "$path" symbolic-ref --quiet --short HEAD 2>/dev/null || echo DETACHED)"
    state="clean"
    [[ -z "$(git -C "$path" status --porcelain)" ]] || state="dirty"
    ahead="$(git -C "$path" rev-list --count "$BASE"..HEAD)"
    last_commit="$(git -C "$path" show -s --format=%h HEAD)"
    last_commit_time="$(git -C "$path" show -s --format=%cI HEAD)"

    main_status="ahead"
    if git -C "$path" merge-base --is-ancestor HEAD "$BASE"; then
        main_status="merged"
    elif [[ "$branch" != "DETACHED" ]] && grep -Fxq "$branch" <<<"$merged_pr_heads"; then
        main_status="pr-merged"
    elif [[ "$state" == "clean" ]]; then
        cherry_output="$(git -C "$path" cherry "$BASE" HEAD)"
        has_unique_patch="false"
        while IFS= read -r cherry_line; do
            if [[ "$cherry_line" == +* ]]; then
                has_unique_patch="true"
            fi
        done <<<"$cherry_output"
        if [[ "$has_unique_patch" == "false" ]]; then
            main_status="patch-equivalent"
        fi
    else
        main_status="unchecked-dirty"
    fi

    action="keep"
    if [[ "$path" != "$REPO" && "$state" == "clean" && "$main_status" != "ahead" ]]; then
        action="cleanup-candidate"
    fi

    printf 'PATH=%s\tBRANCH=%s\tSTATE=%s\tMAIN=%s\tAHEAD=%s\tLAST_COMMIT=%s\tLAST_COMMIT_TIME=%s\tACTION=%s\n' \
        "$path" "$branch" "$state" "$main_status" "$ahead" "$last_commit" "$last_commit_time" "$action"
done
