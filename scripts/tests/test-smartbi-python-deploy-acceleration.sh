#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY_SCRIPT="$ROOT/scripts/deploy/deploy-smartbi-python.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

grep -Fq "<<'ENDSSH'" "$DEPLOY_SCRIPT" || fail "remote dependency heredoc must be quoted"
grep -Fq 'requirements_sha256=' "$DEPLOY_SCRIPT" || fail "requirements hash missing"
grep -Fq 'installed_sha256=' "$DEPLOY_SCRIPT" || fail "venv state hash missing"
grep -Fq 'pip check' "$DEPLOY_SCRIPT" || fail "pip consistency check missing"

REMOTE_BODY="$TMP_DIR/remote-dependencies.sh"
awk '/<<'\''ENDSSH'\''/ { capture=1; next } capture && /^ENDSSH$/ { exit } capture { print }' \
    "$DEPLOY_SCRIPT" > "$REMOTE_BODY"
chmod +x "$REMOTE_BODY"

REMOTE_DIR="$TMP_DIR/remote"
FAKE_BIN="$TMP_DIR/bin"
MOCK_STATE="$TMP_DIR/installed.txt"
MOCK_LOG="$TMP_DIR/pip.log"
mkdir -p "$REMOTE_DIR" "$FAKE_BIN"

cat > "$FAKE_BIN/python3.8" <<'FAKEPY'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "--version" ]]; then
    echo "Python 3.8.20"
elif [[ "${1:-}" == "-m" && "${2:-}" == "venv" ]]; then
    mkdir -p "$3/bin"
    cp "$0" "$3/bin/python"
    chmod +x "$3/bin/python"
elif [[ "${1:-}" == "-m" && "${2:-}" == "pip" && "${3:-}" == "freeze" ]]; then
    [[ -f "$MOCK_STATE" ]] && cat "$MOCK_STATE"
elif [[ "${1:-}" == "-m" && "${2:-}" == "pip" && "${3:-}" == "--version" ]]; then
    echo "pip 24.0 from mock"
elif [[ "${1:-}" == "-m" && "${2:-}" == "pip" && "${3:-}" == "check" ]]; then
    echo "No broken requirements found."
elif [[ "${1:-}" == "-m" && "${2:-}" == "pip" && "${3:-}" == "install" ]]; then
    printf '%s\n' "$*" >> "$MOCK_LOG"
    if [[ " $* " == *" -r requirements.txt "* ]]; then
        printf 'cryptography==42.0.0\n' > "$MOCK_STATE"
    fi
elif [[ "${1:-}" == "-c" && "${2:-}" == *"platform.python_implementation"* ]]; then
    echo "CPython-3.8.20-mock-venv"
elif [[ "${1:-}" == "-c" && "${2:-}" == *"import main"* ]]; then
    echo "mock import OK"
else
    echo "unexpected fake python invocation: $*" >&2
    exit 9
fi
FAKEPY
chmod +x "$FAKE_BIN/python3.8"

cat > "$REMOTE_DIR/requirements.txt" <<'REQ'
cryptography>=42 ; python_version < "4" # `touch SHOULD_NOT_EXIST`
REQ
touch "$REMOTE_DIR/.env.example"
export PATH="$FAKE_BIN:$PATH" MOCK_STATE MOCK_LOG

FIRST_OUTPUT="$(bash "$REMOTE_BODY" "$REMOTE_DIR")"
grep -Fq '[Dependencies] cache miss' <<< "$FIRST_OUTPUT" || fail "first run should miss"
[[ -f "$REMOTE_DIR/.deploy-requirements-manifest" ]] || fail "manifest not written"
[[ ! -e "$REMOTE_DIR/SHOULD_NOT_EXIST" && ! -e "$ROOT/SHOULD_NOT_EXIST" ]] || fail "requirement line executed"
FIRST_INSTALLS="$(wc -l < "$MOCK_LOG" | tr -d ' ')"
[[ "$FIRST_INSTALLS" == "2" ]] || fail "first run should run two pip install commands"

SECOND_OUTPUT="$(bash "$REMOTE_BODY" "$REMOTE_DIR")"
grep -Fq '[Dependencies] cache hit - skipping pip install' <<< "$SECOND_OUTPUT" || fail "second run should hit"
SECOND_INSTALLS="$(wc -l < "$MOCK_LOG" | tr -d ' ')"
[[ "$SECOND_INSTALLS" == "$FIRST_INSTALLS" ]] || fail "cache hit ran pip install"

printf 'cryptography==41.0.0\n' > "$MOCK_STATE"
THIRD_OUTPUT="$(bash "$REMOTE_BODY" "$REMOTE_DIR")"
grep -Fq 'cache miss - running pip install (venv package state changed)' <<< "$THIRD_OUTPUT" || fail "tampered venv should miss"
THIRD_INSTALLS="$(wc -l < "$MOCK_LOG" | tr -d ' ')"
[[ "$THIRD_INSTALLS" == "4" ]] || fail "tampered venv should reinstall"

printf '\nhttpx==0.27.0\n' >> "$REMOTE_DIR/requirements.txt"
FOURTH_OUTPUT="$(bash "$REMOTE_BODY" "$REMOTE_DIR")"
grep -Fq 'cache miss - running pip install (requirements.txt content changed)' <<< "$FOURTH_OUTPUT" || fail "changed requirements should miss"
FOURTH_INSTALLS="$(wc -l < "$MOCK_LOG" | tr -d ' ')"
[[ "$FOURTH_INSTALLS" == "6" ]] || fail "changed requirements should reinstall"

echo "PASS: SmartBI Python dependency cache hit/miss and heredoc safety"
