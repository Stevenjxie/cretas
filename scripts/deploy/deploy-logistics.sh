#!/usr/bin/env bash
#
# 物流业务线独立服务的部署入口。
#
# 为什么是新脚本而不是给 deploy-backend.sh 加一个 --service 开关:
#   deploy-backend.sh 有 2000+ 行, 里面是蓝绿切换、三把锁、auto-rollback、
#   可信 JAR manifest 这些用事故换来的逻辑。把第二个服务穿进去, 改错一处影响的是
#   正在跑的生产单体。新服务用自己的脚本, 单体那条路径一个字节都不动 —— 这是本脚本
#   最重要的安全属性。
#
# 安全原语不重写, 全部复用 scripts/lib/deploy-common.sh:
#   acquire_deploy_lock / archive_backup / rollback_jar / wait_for_health_via_ssh
#   / check_disk_space / ssh_local_path
#
# 默认是 dry-run: 只打印将要执行的动作, 不碰服务器。真正部署必须显式给
# --confirm-deploy YES-LOGISTICS。
#
# 与单体的隔离面(逐条都不重叠):
#   systemd 单元   cretas-logistics        vs  cretas-backend
#   端口           10031                    vs  10010(prod) / 10011(test 保留) / 10020(green)
#   远程目录       /www/wwwroot/cretas-logistics  vs  /www/wwwroot/cretas
#   JAR            cretas-logistics-service-1.0.0.jar vs aims-0.0.1-SNAPSHOT.jar
#   部署锁         cretas-deploy-logistics  vs  cretas-deploy
#   数据库 schema  只跑 db/flyway-logistics 的 12 个迁移
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
# shellcheck source=../lib/deploy-common.sh
source "$PROJECT_ROOT/scripts/lib/deploy-common.sh"

SERVICE_NAME="cretas-logistics"
SERVICE_PORT=10031
HEALTH_PATH="/actuator/health"
REMOTE_DIR="/www/wwwroot/cretas-logistics"
REMOTE_JAR="cretas-logistics-service-1.0.0.jar"
LOCAL_JAR="$PROJECT_ROOT/backend/java/cretas-logistics-app/target/$REMOTE_JAR"
SERVER="${CRETAS_SERVER:-root@47.100.235.168}"
LOCK_NAME="cretas-deploy-logistics"

DRY_RUN=true
SKIP_BUILD=false

usage() {
    cat <<'EOF'
Usage:
  scripts/deploy/deploy-logistics.sh [--confirm-deploy YES-LOGISTICS] [--skip-build]

  不给 --confirm-deploy 就是 dry-run: 打印每一步将要执行的命令, 不连服务器。
  --skip-build  跳过 Maven 构建, 用 target/ 下已有的 JAR (dry-run 自测用)。
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --confirm-deploy)
            [ "${2:-}" = "YES-LOGISTICS" ] || { echo "ERROR: --confirm-deploy 需要 YES-LOGISTICS" >&2; exit 2; }
            DRY_RUN=false; shift 2 ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: 未知参数: $1" >&2; usage >&2; exit 2 ;;
    esac
done

# dry-run 下不执行, 只打印。真实部署下原样执行。
run() {
    if [ "$DRY_RUN" = true ]; then
        printf '  [dry-run] %s\n' "$*"
    else
        "$@"
    fi
}

run_remote() {
    if [ "$DRY_RUN" = true ]; then
        printf '  [dry-run] ssh %s %s\n' "$SERVER" "$1"
    else
        ssh -o ConnectTimeout=10 "$SERVER" "$1"
    fi
}

log "INFO" "物流独立服务部署 (${DRY_RUN:+dry-run模式})"
log "INFO" "  单元=$SERVICE_NAME 端口=$SERVICE_PORT 远程=$REMOTE_DIR"

# ── 1. 前置闸 ────────────────────────────────────────────────────────────────
# 生产部署必须来自 main(worktree-and-main-only-deploy 规则): 多个 session 从各自
# 分支往同一个固定路径部署 = last-write-wins 互相覆盖, 2026-05-30 已经踩过一次。
if [ "$DRY_RUN" = false ]; then
    current_branch=$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD)
    if [ "$current_branch" != "main" ]; then
        echo "ERROR: 生产部署只能从 main 分支执行, 当前在 $current_branch" >&2
        exit 1
    fi
    if [ -n "$(git -C "$PROJECT_ROOT" status --porcelain)" ]; then
        echo "ERROR: 工作区不干净, 拒绝部署" >&2
        exit 1
    fi
    acquire_deploy_lock "$LOCK_NAME"
fi

# ── 2. 构建 ──────────────────────────────────────────────────────────────────
if [ "$SKIP_BUILD" = false ]; then
    log "INFO" "构建 (reactor: -pl cretas-logistics-app -am)"
    if [ "$DRY_RUN" = true ]; then
        printf '  [dry-run] (cd backend/java/cretas-api && ./mvnw -f ../pom.xml clean package -pl cretas-logistics-app -am)\n'
    else
        (
            cd "$PROJECT_ROOT/backend/java/cretas-api"
            wrapper=./mvnw
            [[ "${OSTYPE:-}" == darwin* || "${OSTYPE:-}" == linux* ]] || wrapper=./mvnw.cmd
            "$wrapper" -f ../pom.xml clean package -pl cretas-logistics-app -am -Dmaven.test.skip=true
        )
    fi
fi

if [ "$SKIP_BUILD" = true ] || [ "$DRY_RUN" = false ]; then
    [ -f "$LOCAL_JAR" ] || { echo "ERROR: 找不到 JAR: $LOCAL_JAR" >&2; exit 1; }
    log "INFO" "本地 JAR: $(get_file_size_human "$LOCAL_JAR")"
fi

# ── 3. 传输 ──────────────────────────────────────────────────────────────────
# ssh_local_path: Windows 盘符路径给 rsync 会被当成主机名(D: -> host "D"), 必须转 /d/...
LOCAL_JAR_SSH=$(ssh_local_path "$LOCAL_JAR")
run_remote "mkdir -p $REMOTE_DIR"
log "INFO" "上传 JAR"
run rsync -avz --timeout=60 "$LOCAL_JAR_SSH" "$SERVER:$REMOTE_DIR/${REMOTE_JAR}.new"

# ── 4. 备份 + 就位 + 重启 ────────────────────────────────────────────────────
# 单体走蓝绿(两个端口来回切), 这个服务先用「备份→替换→重启」。它现在没有流量,
# 蓝绿的复杂度等它真的承载流量再加 —— 但备份与回滚从第一天就有。
log "INFO" "备份现有 JAR 并就位"
run_remote "cd $REMOTE_DIR && [ -f $REMOTE_JAR ] && cp $REMOTE_JAR ${REMOTE_JAR}.bak.\$(date +%Y%m%d_%H%M%S) || true"
run_remote "cd $REMOTE_DIR && mv ${REMOTE_JAR}.new $REMOTE_JAR"
log "INFO" "重启 $SERVICE_NAME"
run_remote "systemctl restart $SERVICE_NAME"

# ── 5. 健康检查, 失败即回滚 ──────────────────────────────────────────────────
if [ "$DRY_RUN" = true ]; then
    printf '  [dry-run] wait_for_health_via_ssh %s %s %s\n' "$SERVER" "$SERVICE_PORT" "$HEALTH_PATH"
    printf '  [dry-run] 失败则: 恢复最近备份 + systemctl restart %s\n' "$SERVICE_NAME"
    log "INFO" "dry-run 结束 —— 未连接服务器, 未改动任何东西"
    exit 0
fi

if wait_for_health_via_ssh "$SERVER" "$SERVICE_PORT" "$HEALTH_PATH" 60 2; then
    log "INFO" "部署成功: $SERVICE_NAME 健康"
    exit 0
fi

log "ERROR" "健康检查失败, 回滚"
ssh -o ConnectTimeout=10 "$SERVER" \
    "cd $REMOTE_DIR && latest=\$(ls -t ${REMOTE_JAR}.bak.* 2>/dev/null | head -1) && \
     [ -n \"\$latest\" ] && cp \"\$latest\" $REMOTE_JAR && systemctl restart $SERVICE_NAME"
log "ERROR" "已回滚到上一版本"
exit 1
