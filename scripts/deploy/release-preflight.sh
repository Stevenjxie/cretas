#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
DEFAULT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)

REPO_ROOT="${RELEASE_PREFLIGHT_REPO_ROOT:-$DEFAULT_ROOT}"
FETCH_ORIGIN="${RELEASE_PREFLIGHT_FETCH_ORIGIN:-1}"
REQUIRE_EXACT_MAIN="${RELEASE_PREFLIGHT_REQUIRE_EXACT_MAIN:-1}"
REQUIRE_CLEAN="${RELEASE_PREFLIGHT_REQUIRE_CLEAN:-1}"
FLYWAY_SRC_DIR="${RELEASE_PREFLIGHT_FLYWAY_SRC_DIR:-backend/java/cretas-api/src/main/resources/db/flyway}"
FLYWAY_TGT_DIR="${RELEASE_PREFLIGHT_FLYWAY_TGT_DIR:-backend/java/cretas-api/target/classes/db/flyway}"

usage() {
    cat <<'EOF'
Usage: scripts/deploy/release-preflight.sh [options]

Fast, read-only release gates. This script never runs Maven or production operations.

Options:
  --repo-root PATH       repository to inspect (default: current project)
  --skip-fetch           do not refresh origin/main
  --allow-non-main       do not require HEAD to equal origin/main
  --allow-dirty          do not fail only because the worktree is dirty
  -h, --help             show this help
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --repo-root)
            [ "$#" -ge 2 ] || { echo "ERROR: --repo-root requires a path" >&2; exit 2; }
            REPO_ROOT="$2"
            shift 2
            ;;
        --skip-fetch) FETCH_ORIGIN=0; shift ;;
        --allow-non-main) REQUIRE_EXACT_MAIN=0; shift ;;
        --allow-dirty) REQUIRE_CLEAN=0; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

case "$FETCH_ORIGIN:$REQUIRE_EXACT_MAIN:$REQUIRE_CLEAN" in
    *[!01:]*) echo "ERROR: preflight boolean settings must be 0 or 1" >&2; exit 2 ;;
esac

now_seconds() {
    date +%s
}

TOTAL_STARTED=$(now_seconds)

print_total() {
    local result="$1"
    local elapsed=$(( $(now_seconds) - TOTAL_STARTED ))
    echo "==> release preflight $result (total: ${elapsed}s)"
}

run_stage() {
    local label="$1"
    local function_name="$2"
    local started rc elapsed

    started=$(now_seconds)
    echo "--> $label"
    set +e
    "$function_name"
    rc=$?
    set -e
    elapsed=$(( $(now_seconds) - started ))
    if [ "$rc" -ne 0 ]; then
        echo "[FAIL] $label (${elapsed}s)" >&2
        print_total "FAILED" >&2
        exit "$rc"
    fi
    echo "[PASS] $label (${elapsed}s)"
}

fail_gate() {
    echo "   ERROR: $*" >&2
    return 1
}

git_gate() {
    cd "$REPO_ROOT" || { fail_gate "repository path not found: $REPO_ROOT"; return 1; }
    git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { fail_gate "not a Git worktree: $REPO_ROOT"; return 1; }

    if [ "$FETCH_ORIGIN" = "1" ]; then
        git fetch --quiet origin main || { fail_gate "git fetch origin main failed"; return 1; }
    fi
    git rev-parse --verify origin/main >/dev/null 2>&1 || { fail_gate "origin/main is unavailable"; return 1; }

    local head_sha origin_sha dirty
    head_sha=$(git rev-parse HEAD) || return 1
    origin_sha=$(git rev-parse origin/main) || return 1
    echo "   HEAD:        $head_sha"
    echo "   origin/main: $origin_sha"
    if [ "$REQUIRE_EXACT_MAIN" = "1" ] && [ "$head_sha" != "$origin_sha" ]; then
        fail_gate "HEAD must equal origin/main (use --allow-non-main only for non-release diagnostics)"
        return 1
    fi

    if [ "$REQUIRE_CLEAN" = "1" ]; then
        dirty=$(git status --porcelain --untracked-files=all) || return 1
        if [ -n "$dirty" ]; then
            printf '%s\n' "$dirty" | sed 's/^/   /' >&2
            fail_gate "worktree must be clean (use --allow-dirty only for local diagnostics)"
            return 1
        fi
    fi
}

flyway_gate() {
    cd "$REPO_ROOT" || return 1
    if [ ! -d "$FLYWAY_SRC_DIR" ]; then
        echo "   SKIP: Flyway source directory not present"
        return 0
    fi

    local src_paths src_names duplicates uncommitted target_names version
    src_paths=$(find "$FLYWAY_SRC_DIR" -type f -name 'V*.sql' -print 2>/dev/null | LC_ALL=C sort)
    src_names=""
    if [ -n "$src_paths" ]; then
        src_names=$(printf '%s\n' "$src_paths" | sed 's#^.*/##' | LC_ALL=C sort)
    fi

    duplicates=""
    if [ -n "$src_names" ]; then
        duplicates=$(printf '%s\n' "$src_names" | awk -F'__' '{print $1}' | uniq -d)
    fi
    if [ -n "$duplicates" ]; then
        echo "   duplicate Flyway versions:" >&2
        while IFS= read -r version; do
            printf '     %s\n' "$version" >&2
        done <<< "$duplicates"
        fail_gate "Flyway version collision detected"
        return 1
    fi

    uncommitted=$(git status --short -- "$FLYWAY_SRC_DIR/" 2>/dev/null \
        | awk '/^\?\? / || /^A  / || /^AM / {print $2}')
    if [ -n "$uncommitted" ]; then
        printf '%s\n' "$uncommitted" | sed 's/^/   /' >&2
        fail_gate "uncommitted Flyway migration would be packaged"
        return 1
    fi

    if [ -d "$FLYWAY_TGT_DIR" ]; then
        target_names=$(find "$FLYWAY_TGT_DIR" -type f -name 'V*.sql' -print 2>/dev/null \
            | sed 's#^.*/##' | LC_ALL=C sort)
        if [ -n "$target_names" ] && [ "$src_names" != "$target_names" ]; then
            fail_gate "target/classes Flyway manifest differs from source; clean target before release"
            return 1
        fi
    fi
    echo "   migrations: $(printf '%s\n' "$src_names" | awk 'NF {count++} END {print count+0}')"
}

release_candidate_files() {
    {
        if git rev-parse --verify HEAD^ >/dev/null 2>&1; then
            git diff --name-only --diff-filter=ACMR HEAD^ HEAD
        fi
        git diff --name-only --diff-filter=ACMR
        git diff --cached --name-only --diff-filter=ACMR
        git ls-files --others --exclude-standard
    } | awk 'NF' | LC_ALL=C sort -u
}

shell_gate() {
    cd "$REPO_ROOT" || return 1
    command -v bash >/dev/null 2>&1 || { echo "   SKIP: bash unavailable"; return 0; }

    local candidates=() files=() file
    mapfile -t candidates < <(release_candidate_files)
    for file in "${candidates[@]}"; do
        case "$file" in
            *.sh) [ -f "$file" ] && files+=("$file") ;;
        esac
    done
    if [ "${#files[@]}" -eq 0 ]; then
        echo "   SKIP: no shell files in release delta"
        return 0
    fi
    for file in "${files[@]}"; do
        [ -f "$file" ] || continue
        bash -n "$file" || { fail_gate "shell syntax failed: $file"; return 1; }
    done
    echo "   checked: ${#files[@]} shell files"
}

yaml_gate() {
    cd "$REPO_ROOT" || return 1
    local candidates=() files=() file python_cmd=""
    mapfile -t candidates < <(release_candidate_files)
    for file in "${candidates[@]}"; do
        case "$file" in
            *.yml|*.yaml) [ -f "$file" ] && files+=("$file") ;;
        esac
    done
    if [ "${#files[@]}" -eq 0 ]; then
        echo "   SKIP: no YAML files in release delta"
        return 0
    fi

    if command -v python3 >/dev/null 2>&1 && python3 -c 'import yaml' >/dev/null 2>&1; then
        python_cmd=python3
    elif command -v python >/dev/null 2>&1 && python -c 'import yaml' >/dev/null 2>&1; then
        python_cmd=python
    else
        echo "   SKIP: PyYAML parser unavailable (${#files[@]} files found)"
        return 0
    fi

    "$python_cmd" - "${files[@]}" <<'PY'
import pathlib
import sys
import yaml

for filename in sys.argv[1:]:
    with pathlib.Path(filename).open("r", encoding="utf-8") as handle:
        yaml.safe_load(handle)
PY
    local rc=$?
    if [ "$rc" -ne 0 ]; then
        fail_gate "YAML syntax or UTF-8 validation failed"
        return 1
    fi
    echo "   checked: ${#files[@]} YAML files"
}

encoding_gate() {
    cd "$REPO_ROOT" || return 1
    local checker="scripts/utils/encoding-checker.ps1"
    if [ ! -f "$checker" ]; then
        echo "   SKIP: encoding checker not present"
        return 0
    fi
    if command -v pwsh >/dev/null 2>&1; then
        pwsh -NoProfile -File "$checker" || { fail_gate "encoding checker failed"; return 1; }
    elif command -v powershell.exe >/dev/null 2>&1; then
        powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$checker" || { fail_gate "encoding checker failed"; return 1; }
    elif command -v powershell >/dev/null 2>&1; then
        powershell -NoProfile -ExecutionPolicy Bypass -File "$checker" || { fail_gate "encoding checker failed"; return 1; }
    else
        echo "   SKIP: PowerShell unavailable"
        return 0
    fi
    echo "   checker: $checker"
}

diff_gate() {
    cd "$REPO_ROOT" || return 1
    # CRLF is a valid line ending in this Windows-first repository. Treat the
    # carriage return as part of EOL while continuing to reject spaces/tabs
    # before it and all other whitespace errors reported by `git diff --check`.
    git -c core.whitespace=cr-at-eol diff --check \
        || { fail_gate "unstaged diff check failed"; return 1; }
    git -c core.whitespace=cr-at-eol diff --cached --check \
        || { fail_gate "staged diff check failed"; return 1; }
    if git rev-parse --verify HEAD^ >/dev/null 2>&1; then
        git -c core.whitespace=cr-at-eol diff --check HEAD^ HEAD \
            || { fail_gate "HEAD commit diff check failed"; return 1; }
    fi
}

echo "Cretas release preflight (fast/read-only; no Maven, SSH, or production operations)"
run_stage "git release truth" git_gate
run_stage "Flyway static checks" flyway_gate
run_stage "shell syntax" shell_gate
run_stage "YAML syntax" yaml_gate
run_stage "encoding check" encoding_gate
run_stage "diff check" diff_gate
print_total "PASSED"
