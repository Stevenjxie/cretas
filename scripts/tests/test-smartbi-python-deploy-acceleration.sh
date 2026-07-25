#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY_SCRIPT="$ROOT/scripts/deploy/deploy-smartbi-python.sh"
REQUIREMENTS="$ROOT/backend/python/requirements.txt"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

grep -Fq "<<'ENDSSH'" "$DEPLOY_SCRIPT" || fail "remote dependency heredoc must be quoted"
grep -Fq 'requirements_sha256=' "$DEPLOY_SCRIPT" || fail "requirements hash missing"
grep -Fq 'installed_sha256=' "$DEPLOY_SCRIPT" || fail "venv state hash missing"
grep -Fq 'pip check' "$DEPLOY_SCRIPT" || fail "pip consistency check missing"
grep -Fq 'python3.11' "$DEPLOY_SCRIPT" || fail "Python 3.11 runtime contract missing"
grep -Fq 'venv-current' "$DEPLOY_SCRIPT" || fail "atomic runtime selector missing"
grep -Fq 'rollback_prod_runtime' "$DEPLOY_SCRIPT" || fail "production runtime rollback missing"
grep -Fq 'restore_python_runtime_units' "$DEPLOY_SCRIPT" \
    || fail "runtime unit rollback is missing"
grep -Fq 'rollback_dir="$bundle/rollback"' "$DEPLOY_SCRIPT" \
    || fail "SHA-scoped runtime unit snapshot is missing"
grep -Fq '"postgres":"connected"' "$DEPLOY_SCRIPT" \
    || fail "semantic PostgreSQL health gate is missing"
grep -Fq '"model_available":true' "$DEPLOY_SCRIPT" \
    || fail "post-restart classifier health gate is missing"
grep -Fq '/api/classifier/health' "$DEPLOY_SCRIPT" \
    || fail "classifier middleware route smoke missing"
grep -Fq 'https://mirrors.aliyun.com/pypi/simple' "$DEPLOY_SCRIPT" \
    || fail "approved production PyPI mirror missing"
grep -Fq -- '--extra-index-url https://download.pytorch.org/whl/cpu' "$REQUIREMENTS" \
    || fail "official PyTorch CPU wheel index missing"
grep -Fq 'fastapi==0.124.4' "$REQUIREMENTS" \
    || fail "production-proven FastAPI version is not pinned"
grep -Fq 'starlette==0.44.0' "$REQUIREMENTS" \
    || fail "production-proven Starlette version is not pinned"
grep -Fq 'torch==2.4.1+cpu ; python_version >= "3.8"' "$REQUIREMENTS" \
    || fail "Python 3.11 CPU Torch contract missing"
for runtime_file in \
    "$ROOT/scripts/systemd/cretas-python.service" \
    "$ROOT/scripts/systemd/cretas-gold-etl-refresh.service" \
    "$ROOT/scripts/systemd/cretas-gold-etl-refresh.service.d/python-runtime.conf" \
    "$ROOT/scripts/systemd/cretas-corpus-refresh.service.d/python-runtime.conf" \
    "$ROOT/scripts/cron/restaurant-ai-eval.sh" \
    "$ROOT/scripts/cron/refresh-demo-rest.sh"; do
    grep -Fq 'venv-current' "$runtime_file" \
        || fail "runtime consumer is not pinned to venv-current: $runtime_file"
done
for postgres_environment in \
    'Environment=POSTGRES_DB=smartbi_prod_db' \
    'Environment=POSTGRES_ENABLED=true' \
    'Environment=POSTGRES_HOST=localhost' \
    'Environment=POSTGRES_PORT=5432' \
    'Environment=POSTGRES_USER=smartbi_user'; do
    grep -Fq "$postgres_environment" "$ROOT/scripts/systemd/cretas-python.service" \
        || fail "main Python unit lost PostgreSQL runtime contract: $postgres_environment"
done

REMOTE_BODY="$TMP_DIR/remote-dependencies.sh"
awk '/<<'\''ENDSSH'\''/ { capture=1; next } capture && /^ENDSSH$/ { exit } capture { print }' \
    "$DEPLOY_SCRIPT" > "$REMOTE_BODY"
chmod +x "$REMOTE_BODY"

REMOTE_DIR="$TMP_DIR/remote"
FAKE_BIN="$TMP_DIR/bin"
MOCK_STATE="$TMP_DIR/installed.txt"
MOCK_LOG="$TMP_DIR/pip.log"
mkdir -p "$REMOTE_DIR" "$FAKE_BIN"

cat > "$FAKE_BIN/python3.11" <<'FAKEPY'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "--version" ]]; then
    echo "Python 3.11.13"
elif [[ "${1:-}" == "-m" && "${2:-}" == "venv" ]]; then
    mkdir -p "$3/bin"
    cp "$0" "$3/bin/python"
    chmod +x "$3/bin/python"
elif [[ "${1:-}" == "-m" && "${2:-}" == "pip" && "${3:-}" == "freeze" ]]; then
    if [[ -f "$MOCK_STATE" ]]; then cat "$MOCK_STATE"; fi
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
    echo "CPython-3.11.13-mock-venv"
elif [[ "${1:-}" == "-c" && "${2:-}" == *"/api/classifier/health"* ]]; then
    if [[ "${MOCK_CLASSIFIER_FAIL:-0}" == "1" ]]; then
        echo "mock classifier middleware failure" >&2
        exit 17
    fi
    echo "mock classifier route OK"
elif [[ "${1:-}" == "-c" && "${2:-}" == *"import main"* ]]; then
    echo "mock import OK"
elif [[ "${1:-}" == "-c" && "${2:-}" == *"import torch"* ]]; then
    echo "mock torch CPU OK"
else
    echo "unexpected fake python invocation: $*" >&2
    exit 9
fi
FAKEPY
chmod +x "$FAKE_BIN/python3.11"

cat > "$REMOTE_DIR/requirements.txt" <<'REQ'
cryptography>=42 ; python_version < "4" # `touch SHOULD_NOT_EXIST`
REQ
touch "$REMOTE_DIR/.env.example"
export PATH="$FAKE_BIN:$PATH" MOCK_STATE MOCK_LOG

FIRST_OUTPUT="$(bash "$REMOTE_BODY" "$REMOTE_DIR" python3.11 venv311 https://mirrors.aliyun.com/pypi/simple)"
grep -Fq '[Dependencies] cache miss' <<< "$FIRST_OUTPUT" || fail "first run should miss"
[[ -f "$REMOTE_DIR/.deploy-requirements-manifest.venv311" ]] || fail "versioned manifest not written"
[[ ! -e "$REMOTE_DIR/SHOULD_NOT_EXIST" && ! -e "$ROOT/SHOULD_NOT_EXIST" ]] || fail "requirement line executed"
FIRST_INSTALLS="$(wc -l < "$MOCK_LOG" | tr -d ' ')"
[[ "$FIRST_INSTALLS" == "2" ]] || fail "first run should run two pip install commands"

SECOND_OUTPUT="$(bash "$REMOTE_BODY" "$REMOTE_DIR" python3.11 venv311 https://mirrors.aliyun.com/pypi/simple)"
grep -Fq '[Dependencies] cache hit - skipping pip install' <<< "$SECOND_OUTPUT" || fail "second run should hit"
SECOND_INSTALLS="$(wc -l < "$MOCK_LOG" | tr -d ' ')"
[[ "$SECOND_INSTALLS" == "$FIRST_INSTALLS" ]] || fail "cache hit ran pip install"

set +e
MOCK_CLASSIFIER_FAIL=1 bash "$REMOTE_BODY" \
    "$REMOTE_DIR" python3.11 venv311 https://mirrors.aliyun.com/pypi/simple \
    > "$TMP_DIR/classifier-failure.log" 2>&1
classifier_status=$?
set -e
[[ "$classifier_status" -ne 0 ]] || fail "classifier route regression did not abort deploy"
grep -Fq 'classifier route smoke FAILED' "$TMP_DIR/classifier-failure.log" \
    || fail "classifier route failure was not reported"

printf 'cryptography==41.0.0\n' > "$MOCK_STATE"
THIRD_OUTPUT="$(bash "$REMOTE_BODY" "$REMOTE_DIR" python3.11 venv311 https://mirrors.aliyun.com/pypi/simple)"
grep -Fq 'cache miss - running pip install (venv package state changed)' <<< "$THIRD_OUTPUT" || fail "tampered venv should miss"
THIRD_INSTALLS="$(wc -l < "$MOCK_LOG" | tr -d ' ')"
[[ "$THIRD_INSTALLS" == "4" ]] || fail "tampered venv should reinstall"

printf '\nhttpx==0.27.0\n' >> "$REMOTE_DIR/requirements.txt"
FOURTH_OUTPUT="$(bash "$REMOTE_BODY" "$REMOTE_DIR" python3.11 venv311 https://mirrors.aliyun.com/pypi/simple)"
grep -Fq 'cache miss - running pip install (requirements.txt content changed)' <<< "$FOURTH_OUTPUT" || fail "changed requirements should miss"
FOURTH_INSTALLS="$(wc -l < "$MOCK_LOG" | tr -d ' ')"
[[ "$FOURTH_INSTALLS" == "6" ]] || fail "changed requirements should reinstall"

RUNTIME_SWITCH_BODY="$TMP_DIR/runtime-switch.sh"
awk '/<<'\''RUNTIME_SWITCH'\''/ { capture=1; next } capture && /^RUNTIME_SWITCH$/ { exit } capture { print }' \
    "$DEPLOY_SCRIPT" > "$RUNTIME_SWITCH_BODY"
chmod +x "$RUNTIME_SWITCH_BODY"

case "$(uname -s)" in
    MINGW*|MSYS*)
        echo "SKIP: native symlink runtime test requires Linux"
        ;;
    *)
        RUNTIME_ROOT="$TMP_DIR/runtime"
        mkdir -p "$RUNTIME_ROOT/venv38/bin" "$RUNTIME_ROOT/venv311/bin"
        printf '#!/usr/bin/env bash\nexit 0\n' > "$RUNTIME_ROOT/venv38/bin/python"
        printf '#!/usr/bin/env bash\nexit 0\n' > "$RUNTIME_ROOT/venv311/bin/python"
        chmod +x "$RUNTIME_ROOT/venv38/bin/python" "$RUNTIME_ROOT/venv311/bin/python"

        bash "$RUNTIME_SWITCH_BODY" "$RUNTIME_ROOT" "$RUNTIME_ROOT/venv38"
        [[ "$(readlink -f "$RUNTIME_ROOT/venv-current")" == "$RUNTIME_ROOT/venv38" ]] \
            || fail "runtime selector did not seed the rollback venv"
        bash "$RUNTIME_SWITCH_BODY" "$RUNTIME_ROOT" "$RUNTIME_ROOT/venv311"
        [[ "$(readlink -f "$RUNTIME_ROOT/venv-current")" == "$RUNTIME_ROOT/venv311" ]] \
            || fail "runtime selector did not switch atomically to venv311"

        set +e
        bash "$RUNTIME_SWITCH_BODY" "$RUNTIME_ROOT" "$TMP_DIR/outside" >/dev/null 2>&1
        unsafe_status=$?
        set -e
        [[ "$unsafe_status" -ne 0 ]] || fail "runtime selector accepted an escaped target"

        rm -f "$RUNTIME_ROOT/venv-current"
        printf 'not a symlink\n' > "$RUNTIME_ROOT/venv-current"
        set +e
        bash "$RUNTIME_SWITCH_BODY" "$RUNTIME_ROOT" "$RUNTIME_ROOT/venv311" >/dev/null 2>&1
        nonsymlink_status=$?
        set -e
        [[ "$nonsymlink_status" -ne 0 ]] || fail "runtime selector replaced a non-symlink path"
        ;;
esac

echo "PASS: SmartBI Python dependency cache hit/miss and heredoc safety"
