#!/bin/bash
# 部署脚本共享函数库 v1.0
# 用法: source scripts/lib/deploy-common.sh
#
# 提供: log(), wait_for_health(), archive_backup(), check_disk_space()

# ==================== 日志 ====================

# 日志级别: DEBUG=0, INFO=1, WARN=2, ERROR=3
_LOG_LEVEL="${LOG_LEVEL:-INFO}"
_LOG_FILE="${LOG_FILE:-}"

_log_level_num() {
    case "$1" in
        DEBUG) echo 0 ;;
        INFO)  echo 1 ;;
        WARN)  echo 2 ;;
        ERROR) echo 3 ;;
        *)     echo 1 ;;
    esac
}

# log <LEVEL> <message...>
# 结构化日志: [timestamp] [LEVEL] message
log() {
    local level="$1"
    shift
    local message="$*"
    local timestamp
    timestamp=$(date '+%Y-%m-%dT%H:%M:%S' 2>/dev/null || date '+%Y-%m-%d %H:%M:%S')

    local current_level_num
    current_level_num=$(_log_level_num "$_LOG_LEVEL")
    local msg_level_num
    msg_level_num=$(_log_level_num "$level")

    # 跳过低于当前级别的消息
    [ "$msg_level_num" -lt "$current_level_num" ] && return 0

    local log_entry="[$timestamp] [$level] $message"

    # 输出到 stdout
    echo "$log_entry"

    # 如果设置了日志文件，同时写入
    if [ -n "$_LOG_FILE" ]; then
        echo "$log_entry" >> "$_LOG_FILE"
    fi
}

# ==================== 健康检查 ====================

# wait_for_health <url> [retries] [interval_seconds]
# 返回: 0=成功, 1=超时
wait_for_health() {
    local url="$1"
    local retries="${2:-60}"      # Round 5 fix: bumped default 30→60 (Spring Boot with
                                   # BERT intent loading needs >60s on first start)
    local interval="${3:-2}"
    local total_wait=$((retries * interval))

    log "INFO" "健康检查: $url (最多等待 ${total_wait}s)"

    for i in $(seq 1 "$retries"); do
        local status
        # Round 5 fix: added --max-time/--connect-timeout so curl can't hang forever
        # when the TCP connect stalls (previously each failed probe could wait minutes).
        status=$(curl -s -o /dev/null --connect-timeout 2 --max-time 3 -w "%{http_code}" "$url" 2>/dev/null || echo "000")

        if [ "$status" = "200" ]; then
            log "INFO" "服务正常 (HTTP 200, 等待 $((i * interval))s)"
            return 0
        fi

        if [ $((i % 5)) -eq 0 ]; then
            log "INFO" "等待服务启动... ($((i * interval))/${total_wait}s, HTTP $status)"
        fi

        sleep "$interval"
    done

    log "ERROR" "健康检查超时 (${total_wait}s), 最后状态: HTTP $status"
    return 1
}

# wait_for_health_via_ssh <ssh_target> <port> [path] [retries] [interval]
# 返回: 0=成功, 1=超时
#
# 2026-05-18: SG Phase 3 收紧后 47:10010/10011/8083/8084 仅允许 nginx 139 source IP.
# 本地开发机 public-IP curl 拿 HTTP 000 (firewall reject), wait_for_health() 误判
# 服务挂掉. 这个 helper SSH 到 47 走 loopback (http://localhost:<port>) 绕过 SG.
# 测试服务 (10011, 8084) 没有 nginx upstream, **必须**走这个 helper.
# Prod 服务 (10010, 8083) 部署完用 nginx upstream 验证更标准; 但 in-place / rollback
# fallback 路径也可以用这个 helper.
wait_for_health_via_ssh() {
    local ssh_target="$1"
    local port="$2"
    local path="${3:-/api/mobile/health}"
    local retries="${4:-60}"
    local interval="${5:-2}"
    local total_wait=$((retries * interval))

    log "INFO" "健康检查 (SSH localhost): ${ssh_target} :${port}${path} (最多等待 ${total_wait}s)"

    local i status=""
    for i in $(seq 1 "$retries"); do
        # outer `|| echo "000"` 兜底 ssh 自身失败 (无网络/host down); inner `|| echo '000'`
        # 兜底 curl 失败 (服务还没起). 双层防 set -e 杀整个 deploy.
        status=$(ssh -o ConnectTimeout=3 "$ssh_target" \
            "curl -s -o /dev/null --connect-timeout 2 --max-time 3 -w '%{http_code}' http://localhost:${port}${path} 2>/dev/null || echo '000'" \
            2>/dev/null || echo "000")
        # 取最后一行 (有时 ssh banner / motd 会污染 stdout)
        status=$(echo "$status" | tail -1)

        if [ "$status" = "200" ]; then
            log "INFO" "服务正常 (HTTP 200, 等待 $((i * interval))s)"
            return 0
        fi

        if [ $((i % 5)) -eq 0 ]; then
            log "INFO" "等待服务启动... ($((i * interval))/${total_wait}s, HTTP $status)"
        fi

        sleep "$interval"
    done

    log "ERROR" "健康检查超时 (${total_wait}s), 最后状态: HTTP $status"
    return 1
}

# ==================== Git 同步检查 ====================

# check_git_sync <project_root> [step_label] [strict]
# 部署前确认本地 HEAD 与 origin/main 一致 (防 stale-local-deploy, May 11 2026 bug fix;
# per HARD rule feedback_organizer_must_git_pull_before_deploy.md):
# - 在 main 分支且与 origin/main 不一致 → ERROR + exit 1 (除非 SKIP_GIT_CHECK=1)
# - 非 main 分支 → WARN 提示确认部署源
# - 工作树有未提交改动 → WARN (非致命)
#
# strict="1" (Jul 7 2026 事故修复: 非 main 分支 + 脏工作树从主目录部署 prod 覆盖了
# origin/main 的代码): prod 部署默认严格模式 —
# - HEAD != origin/main (commit 层面比较, 不看分支名 — detached HEAD 落在
#   origin/main 上是合法的干净 worktree 部署形态) → ABORT (除非 SKIP_GIT_CHECK=1)
# - 工作树有未提交改动 (含 staged) → ABORT (除非 ALLOW_DIRTY_DEPLOY=1)
# strict 为空/非 "1" 时行为与之前完全一致 (test 环境等仍只 WARN).
# 依赖调用方已 source 本库 (用 log). 注意: 函数内 cd 会改变调用方 cwd (与原内联行为一致).
check_git_sync() {
    local project_root="$1"
    local step_label="${2:-Git sync pre-check...}"
    local strict="${3:-}"
    cd "$project_root" || { log "ERROR" "check_git_sync: 无法 cd 到 $project_root"; exit 1; }
    log "INFO" "$step_label"
    git fetch origin main 2>/dev/null || log "WARN" "git fetch origin main failed (offline?), continue with caution"

    local local_sha origin_sha current_branch
    local_sha=$(git rev-parse HEAD 2>/dev/null || echo "unknown")
    origin_sha=$(git rev-parse origin/main 2>/dev/null || echo "unknown")
    current_branch=$(git branch --show-current 2>/dev/null || echo "unknown")

    if [ "$local_sha" != "$origin_sha" ] && [ "$local_sha" != "unknown" ] && [ "$origin_sha" != "unknown" ]; then
        if [ "$strict" = "1" ]; then
            log "ERROR" "⛔ Strict git gate (prod deploy): HEAD != origin/main — ABORT"
            log "ERROR" "  local HEAD : $local_sha (branch: $current_branch)"
            log "ERROR" "  origin/main: $origin_sha"
            log "ERROR" "  正确做法: 从干净 worktree 部署 —"
            log "ERROR" "    git worktree add ../cretas-deploy-clean origin/main && cd ../cretas-deploy-clean"
            log "ERROR" "  或本地 main 分支同步: git checkout main && git pull origin main"
            log "ERROR" "  逃生门 (确认知道风险才用): SKIP_GIT_CHECK=1 重新运行部署脚本"
            if [ "${SKIP_GIT_CHECK:-}" != "1" ]; then
                exit 1
            fi
            log "WARN" "⚠️  SKIP_GIT_CHECK=1 set, continuing STRICT prod deploy with HEAD != origin/main"
        elif [ "$current_branch" = "main" ]; then
            log "ERROR" "Local main HEAD != origin/main HEAD"
            log "ERROR" "  local : $local_sha"
            log "ERROR" "  origin: $origin_sha"
            log "ERROR" "Run: cd $project_root && git pull origin main"
            log "ERROR" "Override: SKIP_GIT_CHECK=1 重新运行部署脚本"
            if [ "${SKIP_GIT_CHECK:-}" != "1" ]; then
                exit 1
            fi
            log "WARN" "SKIP_GIT_CHECK=1 set, continuing deploy with stale local"
        else
            log "WARN" "Current branch is '$current_branch' (not main). Verify intended deploy source."
        fi
    else
        log "INFO" "  Git: local HEAD matches origin/main ✓"
    fi

    # Dirty tree check: strict=1 → ABORT, 否则 WARN (non-fatal, 与之前一致)
    if ! git diff --quiet 2>/dev/null || ! git diff --cached --quiet 2>/dev/null; then
        if [ "$strict" = "1" ]; then
            log "ERROR" "⛔ Strict git gate (prod deploy): working tree has uncommitted changes — ABORT"
            log "ERROR" "  查看改动: git -C $project_root status"
            log "ERROR" "  正确做法: 从干净 worktree 部署 —"
            log "ERROR" "    git worktree add ../cretas-deploy-clean origin/main && cd ../cretas-deploy-clean"
            log "ERROR" "  逃生门 (确认知道风险才用): ALLOW_DIRTY_DEPLOY=1 重新运行部署脚本"
            if [ "${ALLOW_DIRTY_DEPLOY:-}" != "1" ]; then
                exit 1
            fi
            log "WARN" "⚠️  ALLOW_DIRTY_DEPLOY=1 set, continuing STRICT prod deploy with dirty working tree"
        else
            log "WARN" "Working tree has uncommitted changes — deploy will use local working tree state"
        fi
    fi
}

# ==================== 备份管理 ====================

# archive_backup <file_path> [keep_count]
# 创建带时间戳的备份，保留最近 N 份
archive_backup() {
    local file="$1"
    local keep="${2:-3}"

    if [ ! -f "$file" ]; then
        log "WARN" "备份目标不存在: $file"
        return 0
    fi

    local backup_name="${file}.bak.$(date +%Y%m%d_%H%M%S)"
    cp "$file" "$backup_name"
    log "INFO" "备份: $backup_name"

    # 清理旧备份，保留最近 $keep 份
    local old_backups
    old_backups=$(ls -t "${file}.bak."* 2>/dev/null | tail -n +$((keep + 1)))
    if [ -n "$old_backups" ]; then
        echo "$old_backups" | xargs rm -f 2>/dev/null || true
        local cleaned
        cleaned=$(echo "$old_backups" | wc -l)
        log "DEBUG" "清理 $cleaned 份旧备份"
    fi
}

# ==================== 磁盘检查 ====================

# check_disk_space <path> <required_mb>
# 检查指定路径的可用磁盘空间
# 返回: 0=足够, 1=不足
check_disk_space() {
    local path="$1"
    local required_mb="$2"

    local available_kb
    available_kb=$(df "$path" 2>/dev/null | awk 'NR==2 {print $4}')

    if [ -z "$available_kb" ]; then
        log "WARN" "无法检查磁盘空间: $path"
        return 0  # 检查失败不阻塞部署
    fi

    local available_mb=$((available_kb / 1024))

    if [ "$available_mb" -lt "$required_mb" ]; then
        log "ERROR" "磁盘空间不足: ${available_mb}MB 可用, 需要 ${required_mb}MB ($path)"
        return 1
    fi

    log "DEBUG" "磁盘空间充足: ${available_mb}MB 可用 ($path)"
    return 0
}

# ==================== 文件大小 ====================

# get_file_size_bytes <file_path>
# 跨平台获取文件大小 (MSYS/Linux/macOS)
get_file_size_bytes() {
    local file="$1"
    wc -c < "$file" 2>/dev/null | tr -d ' '
}

# get_file_size_human <file_path>
# 人类可读的文件大小
get_file_size_human() {
    local file="$1"
    du -h "$file" 2>/dev/null | cut -f1
}

# ==================== 进程管理 ====================

# graceful_kill <pid...>
# 先 SIGTERM，等待 10s，再 SIGKILL
graceful_kill() {
    local pids=("$@")

    for pid in "${pids[@]}"; do
        kill -TERM "$pid" 2>/dev/null || true
    done

    # 等待优雅退出
    local elapsed=0
    while [ $elapsed -lt 10 ]; do
        local alive=0
        for pid in "${pids[@]}"; do
            kill -0 "$pid" 2>/dev/null && alive=1
        done
        [ "$alive" -eq 0 ] && return 0
        sleep 1
        elapsed=$((elapsed + 1))
    done

    # 强制终止
    for pid in "${pids[@]}"; do
        kill -9 "$pid" 2>/dev/null || true
    done
    log "WARN" "强制终止 ${#pids[@]} 个进程"
}

# ==================== 并发锁 (防多 chat 同时 deploy 覆盖 jar) ====================

# acquire_deploy_lock <lock_name>
# 获取 deploy 互斥锁. flock 可用则用 flock, 否则退化到 PID 文件.
# 在 deploy 脚本开头调用. 锁会在进程退出时自动释放.
#
# 示例:
#   acquire_deploy_lock "cretas-backend" || exit 1
acquire_deploy_lock() {
    local lock_name="${1:-cretas-deploy}"
    local lock_file="/tmp/${lock_name}.lock"

    # Stale-PID precheck (covers both branches below).
    # SIGKILL / Ctrl-C race / harness termination skips the EXIT trap;
    # flock fd auto-release is also brittle on Git Bash for Windows.
    # If the file contains a dead PID, treat as stale and remove.
    if [ -f "$lock_file" ]; then
        local stale_pid
        stale_pid=$(cat "$lock_file" 2>/dev/null)
        if [ -n "$stale_pid" ] && ! kill -0 "$stale_pid" 2>/dev/null; then
            log "WARN" "发现残留 lock 文件 (PID $stale_pid 不存在), 自动清理后继续."
            rm -f "$lock_file"
        fi
    fi

    if command -v flock >/dev/null 2>&1; then
        # flock 模式 (POSIX advisory lock, Linux/Mac/Git Bash)
        exec 200>"$lock_file"
        if ! flock -n 200; then
            log "ERROR" "另一个 deploy 进程持有锁 ($lock_file). 等它完成后重试."
            log "ERROR" "  或 rm $lock_file 如果确认无其他进程."
            # 尝试显示持有者
            if command -v lsof >/dev/null 2>&1; then
                lsof "$lock_file" 2>/dev/null | tail -5
            fi
            return 1
        fi
        # 写 PID 进 lock 文件, 让下次 precheck 能验证 (flock fd 在 SIGKILL 时不一定释放).
        echo $$ >&200
        # fd 200 保持打开, 进程退出时 flock 自动释放
        log "DEBUG" "获取 flock: $lock_file (PID $$)"
    else
        # PID 文件模式 (fallback)
        if [ -f "$lock_file" ]; then
            local old_pid
            old_pid=$(cat "$lock_file" 2>/dev/null)
            if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
                log "ERROR" "另一个 deploy 进程正在跑 (PID $old_pid, $lock_file). 退出."
                return 1
            fi
            log "WARN" "发现残留 lock 文件 (PID $old_pid 不存在), 清理后继续."
            rm -f "$lock_file"
        fi
        echo $$ > "$lock_file"
        # trap 在 exit 时清理 (调用方 script 的 EXIT trap 会保留我们的)
        trap 'rm -f "'"$lock_file"'" 2>/dev/null; true' EXIT
        log "DEBUG" "获取 PID 锁: $lock_file (PID $$)"
    fi

    return 0
}

# ==================== 回滚 ====================

# rollback_jar <jar_path>
# 恢复最新备份
rollback_jar() {
    local jar_path="$1"
    local latest_backup
    latest_backup=$(ls -t "${jar_path}.bak."* 2>/dev/null | head -1)

    if [ -z "$latest_backup" ]; then
        log "ERROR" "无可用备份: ${jar_path}.bak.*"
        return 1
    fi

    log "INFO" "回滚到: $latest_backup"
    cp "$latest_backup" "$jar_path"
    log "INFO" "回滚完成"
    return 0
}

# ============================================================================
# ssh_local_path <path>
# ----------------------------------------------------------------------------
# 把 Windows 盘符路径归一化成 MSYS 形式, 供 rsync / scp 使用。
#
# rsync 和 scp 都按 "host:path" 语法解析参数, 所以 "C:/Users/x.jar" 里的 "C" 会被
# 当成【主机名】—— rsync 报 remote→remote 不支持, scp 去连一台叫 "C" 的机器。传输
# 直接失败。
#
# Git Bash 下 $HOME 和 $(cd .. && pwd) 天然给 MSYS 形式 (/c/...), 所以默认路径不会
# 踩到。但只要有环境变量 (CRETAS_JAR_CACHE_DIR 等) 或外部调用方传进 Windows 风格
# 路径就会中招 —— 而且过去是【静默】的: 上传竞速里另一条通道会赢, 操作者只看到
# "胜出: scp" 和一次变慢的部署, 没有任何迹象表明主通道其实死了。
#
# 实现注意 (两个坑都实测踩过):
#   1. 用两个独立模式而不是 [/\\] 字符组 —— bash glob 的 bracket 表达式对反斜杠的
#      处理有歧义, 实测 [A-Za-z]:[/\\]* 连 "C:/..." 都匹配不到, 而 [A-Za-z]:/* 可以。
#   2. 用 [[:alpha:]] / [:upper:] 而不是 [A-Za-z] / A-Z 范围 —— 范围表达式按 locale
#      collation 展开, en_US.UTF-8 下会把小写字母也覆盖进去 (本仓库 release
#      preflight 曾因此假报 "project import cannot be resolved")。
ssh_local_path() {
    local p=$1 drive rest
    case "$p" in
        [[:alpha:]]:/*|[[:alpha:]]:\\*)
            drive=$(printf '%s' "${p%%:*}" | tr '[:upper:]' '[:lower:]')
            rest=${p#*:}
            rest=${rest#/}
            rest=${rest#\\}
            rest=$(printf '%s' "$rest" | tr '\\' '/')
            printf '/%s/%s\n' "$drive" "$rest"
            ;;
        *)
            printf '%s\n' "$p"
            ;;
    esac
}
