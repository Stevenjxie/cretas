#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
PROD_CONFIRM=
INDEPENDENT_CONFIRM=

usage() {
    cat <<'EOF'
Usage:
  scripts/deploy/deploy-cretas-parallel.sh \
    --confirm-prod YES-PROD \
    --confirm-independent-services YES-INDEPENDENT-SERVICES

Validates both trusted artifacts, then runs Web atomic deployment and Java
blue-green deployment concurrently. Use only when the two releases are known
to be API-compatible in either activation order. Each child keeps its own
integrity, health, rollback and stale-asset gates; this wrapper never bypasses
them.

When one side fails, the sibling is cancelled immediately if it is still in a
reversible phase (build/upload), so the half-release never ships. If it has
already entered its irreversible phase (Java blue-green switch, Web atomic
directory swap) it is deliberately left to finish — killing it there would leave
a worse intermediate state — and the operator is alerted at once instead of the
parent waiting in silence.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --confirm-prod) PROD_CONFIRM=${2:-}; shift 2 ;;
        --confirm-independent-services) INDEPENDENT_CONFIRM=${2:-}; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

[ "$PROD_CONFIRM" = "YES-PROD" ] || { echo "ERROR: production confirmation requires --confirm-prod YES-PROD" >&2; exit 2; }
[ "$INDEPENDENT_CONFIRM" = "YES-INDEPENDENT-SERVICES" ] \
    || { echo "ERROR: parallel release requires --confirm-independent-services YES-INDEPENDENT-SERVICES" >&2; exit 2; }
[ -z "$(git -C "$PROJECT_ROOT" status --porcelain --untracked-files=normal)" ] \
    || { echo "ERROR: parallel deploy requires a clean worktree" >&2; exit 1; }

git -C "$PROJECT_ROOT" fetch --quiet origin main
[ "$(git -C "$PROJECT_ROOT" rev-parse HEAD)" = "$(git -C "$PROJECT_ROOT" rev-parse origin/main)" ] \
    || { echo "ERROR: parallel deploy requires HEAD == origin/main" >&2; exit 1; }

"$SCRIPT_DIR/release-preflight.sh" --skip-fetch
"$SCRIPT_DIR/release-jar-manifest.sh" validate
"$SCRIPT_DIR/release-web-manifest.sh" validate

logs_dir=$(mktemp -d "${TMPDIR:-/tmp}/cretas-parallel-deploy.XXXXXX")
cleanup() { rm -rf "$logs_dir"; }
trap cleanup EXIT
started=$(date +%s)

# Job control gives each child its own process group, so cancelling one reaches
# its ssh/scp/rsync/Maven grandchildren instead of only the wrapper subshell. A
# child that survived cancellation would keep mutating production unattended.
set -m
( child_started=$(date +%s)
  set +e
  "$SCRIPT_DIR/deploy-backend.sh" --env prod >"$logs_dir/java.log" 2>&1
  child_rc=$?
  printf '%s\n' "$(( $(date +%s) - child_started ))" >"$logs_dir/java.seconds"
  printf '%s\n' "$child_rc" >"$logs_dir/java.rc.tmp" && mv -f "$logs_dir/java.rc.tmp" "$logs_dir/java.rc"
  exit "$child_rc"
) &
java_pid=$!
( child_started=$(date +%s)
  set +e
  "$SCRIPT_DIR/deploy-web-admin.sh" --env prod --confirm-prod YES-PROD >"$logs_dir/web.log" 2>&1
  child_rc=$?
  printf '%s\n' "$(( $(date +%s) - child_started ))" >"$logs_dir/web.seconds"
  printf '%s\n' "$child_rc" >"$logs_dir/web.rc.tmp" && mv -f "$logs_dir/web.rc.tmp" "$logs_dir/web.rc"
  exit "$child_rc"
) &
web_pid=$!
set +m

# ------------------------------------------------------------- 不可逆点 (PONR)
# Previously the parent learned about a 30s Java failure immediately but stayed
# blocked on `wait "$web_pid"`, watching Web finish a full production rollout —
# shipping the "old Java / new Web" half-release the operator never asked for.
#
# Unlike release-cretas-artifacts.sh (which cancels *builds*, where killing
# Maven/Vite has no side effects), this wrapper cancels *in-flight production
# deployments*. Blind cancellation can leave a worse intermediate state than the
# half-release it prevents, so each side is cancelled only while it is provably
# still reversible; past that line we alert instead of killing.
#
# Java — deploy-backend.sh --env prod (bluegreen):
#   Reversible: Flyway precheck, Maven build, upload to 47:/tmp, and the remote
#   install itself — the install is `mv /tmp/<jar> aims-0.0.1-SNAPSHOT.jar`, an
#   atomic same-filesystem rename, and the live JVM keeps its old inode. Killing
#   there leaves at most a .bak plus a /tmp leftover; served traffic is untouched.
#   Irreversible once `[3b] Blue-Green 切换` is printed: that block does
#   `systemctl restart <idle>` → rewrites the 139 nginx upstream → five 6s
#   observation rounds → stops the old active, and carries its own auto-rollback.
#   Killing mid-block leaves the new idle JVM up while the old active is still
#   running (two production JVMs racing @Scheduled — the 2026-04-15
#   uk_factory_tool_date primary-key incident), or leaves the upstream already
#   switched with the auto-rollback killed alongside it. Both are worse than a
#   half-release. The in-place fallback (`重启服务 (环境:`) is treated the same.
#
# Web — deploy-web-admin.sh --env prod:
#   Reversible: dist reuse/build, tarball, and scp into 139:/tmp.
#   Irreversible once `[4/4] 原子交换` is printed: the remote heredoc runs
#   `mv $CURRENT $BACKUP` immediately followed by `mv $STAGING $CURRENT`. Dropping
#   the ssh connection between those two lines deletes the production web root
#   outright — every page 404s until someone manually restores from
#   /www/wwwroot/web-admin-backups. Strictly worse than "old Java / new Web".
java_past_ponr() { grep -qE '\[3b\] Blue-Green|重启服务 \(环境:' "$logs_dir/java.log" 2>/dev/null; }
web_past_ponr() { grep -qE '\[4/4\] 原子交换|原子交换 prod' "$logs_dir/web.log" 2>/dev/null; }

last_log_line() { tail -n 1 "$1" 2>/dev/null | tr -d '\r'; }

# Returns 0 when the sibling was cancelled, 1 when it was left running on purpose.
cancel_or_alert() {
    local pid=$1 failed_label=$2 live_label=$3 ponr_fn=$4 live_log=$5 remaining_hint=$6
    if "$ponr_fn"; then
        {
            echo ""
            echo "=============================================================="
            echo "⚠️  半上线风险: $failed_label 部署失败, 但 $live_label 已越过不可逆点"
            echo "    $live_label 正在执行不可中断的生产切换步骤; 现在强杀会留下比"
            echo "    「$failed_label 旧版 / $live_label 新版」更糟的中间态, 因此**不取消**。"
            echo "    预计剩余: $remaining_hint"
            echo "    $live_label 最近一行: $(last_log_line "$live_log")"
            echo "    本脚本会继续等它自然结束, 然后打印两侧完整日志与 rc。"
            echo "    届时 prod 大概率是「$failed_label 旧 / $live_label 新」—— 请立即决定:"
            echo "      a) 修 $failed_label 后从 main 重新发布, 或"
            echo "      b) 回滚 $live_label (Java: deploy-backend.sh --rollback;"
            echo "         Web: 139 上 /www/wwwroot/web-admin-backups 里的最近备份)"
            echo "=============================================================="
            echo ""
        } >&2
        return 1
    fi
    echo "INFO: $failed_label deployment failed; cancelling the still-reversible $live_label deployment" >&2
    kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
    # The pre-check and the kill cannot be atomic. Re-read the marker so a lost
    # race is reported loudly instead of silently corrupting production.
    if "$ponr_fn"; then
        echo "WARN: $live_label reached its point of no return while being cancelled — verify production by hand before any redeploy" >&2
    fi
    return 0
}

set +e
java_rc=
web_rc=
cancelled=
alerted=
heartbeat=$(date +%s)
while :; do
    [ -n "$java_rc" ] || { [ -f "$logs_dir/java.rc" ] && java_rc=$(cat "$logs_dir/java.rc"); }
    [ -n "$web_rc" ] || { [ -f "$logs_dir/web.rc" ] && web_rc=$(cat "$logs_dir/web.rc"); }

    if [ -z "$cancelled" ] && [ -z "$alerted" ] && [ -n "$java_rc" ] && [ "$java_rc" -ne 0 ] && [ -z "$web_rc" ]; then
        if cancel_or_alert "$web_pid" Java Web web_past_ponr "$logs_dir/web.log" "原子交换 + 四方哈希校验通常 20-60s"; then
            cancelled=web
            web_rc=143
        else
            alerted=web
        fi
    fi
    if [ -z "$cancelled" ] && [ -z "$alerted" ] && [ -n "$web_rc" ] && [ "$web_rc" -ne 0 ] && [ -z "$java_rc" ]; then
        if cancel_or_alert "$java_pid" Web Java java_past_ponr "$logs_dir/java.log" "蓝绿切换 + 3 轮切流观察通常 2-4min"; then
            cancelled=java
            java_rc=143
        else
            alerted=java
        fi
    fi

    [ -n "$java_rc" ] && [ -n "$web_rc" ] && break

    if [ -n "$alerted" ] && [ $(( $(date +%s) - heartbeat )) -ge 30 ]; then
        heartbeat=$(date +%s)
        if [ "$alerted" = web ]; then
            echo "WARN: still waiting on the uncancellable Web deployment ($(( $(date +%s) - started ))s elapsed): $(last_log_line "$logs_dir/web.log")" >&2
        else
            echo "WARN: still waiting on the uncancellable Java deployment ($(( $(date +%s) - started ))s elapsed): $(last_log_line "$logs_dir/java.log")" >&2
        fi
    fi
    sleep 1
done
wait "$java_pid" 2>/dev/null
wait "$web_pid" 2>/dev/null
set -e

cat "$logs_dir/java.log"
cat "$logs_dir/web.log"
elapsed=$(( $(date +%s) - started ))
java_elapsed=$(cat "$logs_dir/java.seconds" 2>/dev/null || echo 0)
web_elapsed=$(cat "$logs_dir/web.seconds" 2>/dev/null || echo 0)
printf 'JAVA_DEPLOY_WALL_SECONDS=%s\n' "$java_elapsed"
printf 'WEB_DEPLOY_WALL_SECONDS=%s\n' "$web_elapsed"
printf 'JAVA_DEPLOY_RC=%s\n' "$java_rc"
printf 'WEB_DEPLOY_RC=%s\n' "$web_rc"
if [ "$java_rc" -ne 0 ] || [ "$web_rc" -ne 0 ]; then
    echo "ERROR: parallel production release failed (java=$java_rc web=$web_rc elapsed=${elapsed}s${cancelled:+ cancelled=$cancelled}${alerted:+ uncancellable=$alerted})" >&2
    if [ -n "$cancelled" ]; then
        echo "The $cancelled deployment was cancelled while still reversible, so it never reached production." >&2
    fi
    if [ -n "$alerted" ]; then
        echo "The $alerted deployment had already passed its point of no return and was allowed to finish; production is now mixed-version." >&2
    fi
    echo "Each successful child remains independently deployed; inspect the printed child log before any follow-up action." >&2
    exit 1
fi
printf 'Parallel production release completed (java=0 web=0 elapsed=%ss)\n' "$elapsed"
