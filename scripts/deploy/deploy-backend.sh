#!/bin/bash
# 后端部署脚本 v5.0 — Blue-Green 部署 + OSS/R2 上传 + private repo 兼容
#
# 核心特性:
#   - Blue-Green 生产部署 (零中断, 默认): 启动 idle 实例 → nginx upstream 切换 → 停旧 active
#   - in-place 部署 (test 环境或 --mode=inplace): 替换 jar + systemctl restart
#   - SSH 直传策略: rsync (主, 更长久更快) + rsync+compress + scp (兜底)
#   - ~/.bashrc 去掉 SKIP_RSYNC=1 启用 rsync; scp 任何环境都能跑 (实测 10.85 MB/s)
#   - R2/OSS/GitHub 默认禁用 (Steve 2026-05-28); ENABLE_R2=1 紧急 opt-in
#   - 部署后通过 nginx upstream 验证 (不直接打端口, BG 兼容)
#
# 常用命令:
#   ./deploy-backend.sh                     # 生产 Blue-Green (默认)
#   ./deploy-backend.sh --env test          # test in-place
#   ./deploy-backend.sh --env all           # prod Blue-Green + test in-place
#   ./deploy-backend.sh --mode inplace      # 强制生产 in-place (紧急回退)
#   ./deploy-backend.sh --git               # Git 部署 (服务器端编译)
#   ./deploy-backend.sh --rollback          # 回滚到上一备份

set -e

# 自动加载用户的 ~/.bashrc 环境变量 (R2_*/SKIP_RSYNC 等)
# 非交互式 shell 默认不 source .bashrc，所以这里显式加载
# 用 || true 确保任何错误都不中断 deploy
[ -f "$HOME/.bashrc" ] && source "$HOME/.bashrc" 2>/dev/null || true

# 加载共享函数库
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
if [ -f "$PROJECT_ROOT/scripts/lib/deploy-common.sh" ]; then
    source "$PROJECT_ROOT/scripts/lib/deploy-common.sh"
else
    echo "提示: 使用内联函数"
    log() { echo "[$(date '+%Y-%m-%dT%H:%M:%S')] [$1] ${*:2}"; }
    get_file_size_human() {
        local size
        size=$(stat -c%s "$1" 2>/dev/null || stat -f%z "$1" 2>/dev/null || wc -c < "$1" 2>/dev/null)
        if [ "${size:-0}" -gt 1048576 ] 2>/dev/null; then
            echo "$((size / 1048576))MB"
        elif [ "${size:-0}" -gt 1024 ] 2>/dev/null; then
            echo "$((size / 1024))KB"
        else
            echo "${size:-0}B"
        fi
    }
    # 无 deploy-common.sh 时不加锁
    acquire_deploy_lock() { return 0; }
fi

# 防止多个 chat/terminal 同时跑 deploy 覆盖 jar
# 锁定到进程退出自动释放, 支持 flock (首选) 或 PID 文件 (fallback)
if command -v acquire_deploy_lock >/dev/null 2>&1 || declare -F acquire_deploy_lock >/dev/null 2>&1; then
    acquire_deploy_lock "cretas-backend-deploy" || exit 1
fi

# ==================== 配置 ====================
REPO="Stevenjxie/cretas"
JAR_NAME="cretas-backend-system-1.0.0.jar"
SERVER="root@47.100.235.168"
GATEWAY="root@139.196.165.140"         # Nginx 网关 (Blue-Green upstream 切换)
REMOTE_JAR_DIR="/www/wwwroot/cretas"
REMOTE_TMP="/tmp"

# Blue-Green 部署配置
NGINX_UPSTREAM_FILE="/www/server/panel/vhost/nginx/_upstream_cretas.conf"
BLUE_PORT=10010
BLUE_SERVICE="cretas-backend"
GREEN_PORT=10020
GREEN_SERVICE="cretas-backend-green"

# GitHub 镜像列表
GITHUB_MIRRORS=(
    "ghproxy.cc"
    "mirror.ghproxy.com"
    "ghfast.top"
    "gh-proxy.com"
    "cf.ghproxy.cc"
)

# 阿里云 OSS 配置
OSS_BUCKET="cretas-media"
OSS_ENDPOINT="oss-cn-shanghai.aliyuncs.com"
OSS_ACCELERATE_ENDPOINT="oss-accelerate.aliyuncs.com"  # 全球加速
OSS_INTERNAL_ENDPOINT="oss-cn-shanghai-internal.aliyuncs.com"
OSS_DEPLOY_PATH="deploy/backend/"

# Cloudflare R2 配置 (从环境变量读取，不要硬编码凭证)
R2_BUCKET="cretas"
# R2_ACCOUNT_ID 是公开标识符 (非凭证)，凭证为 R2_ACCESS_KEY_ID + R2_SECRET_ACCESS_KEY
R2_ACCOUNT_ID="${R2_ACCOUNT_ID:-b1251333e5f1465deb7cd31296edeaba}"
R2_ACCESS_KEY_ID="${R2_ACCESS_KEY_ID:-}"
R2_SECRET_ACCESS_KEY="${R2_SECRET_ACCESS_KEY:-}"
R2_PUBLIC_URL="${R2_PUBLIC_URL:-https://pub-4913880cb5fa48a0abb5cdf9260f9a61.r2.dev}"

# ==================== 参数解析 ====================
MODE="jar"
ARG=""
DEPLOY_ENV="prod"        # prod | test | all
DEPLOY_MODE="bluegreen"  # bluegreen (默认, 生产零中断) | inplace (传统, 紧急回退)

# Parse all arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --git)
            MODE="git"
            if [ -n "$2" ] && [[ ! "$2" =~ ^- ]]; then
                ARG="$2"
                shift 2
            else
                ARG="steven"
                shift
            fi
            ;;
        --jar)
            MODE="jar"
            # --jar 可选 version 参数, 若下一个是 flag (-开头) 或无则跳过
            if [ -n "$2" ] && [[ ! "$2" =~ ^- ]]; then
                ARG="$2"
                shift 2
            else
                shift
            fi
            ;;
        --dry-run)
            MODE="dry-run"
            shift
            ;;
        --rollback)
            MODE="rollback"
            shift
            ;;
        --env)
            DEPLOY_ENV="$2"
            if [[ ! "$DEPLOY_ENV" =~ ^(prod|test|all)$ ]]; then
                echo "错误: --env 参数必须是 prod, test, 或 all"
                exit 1
            fi
            shift 2
            ;;
        --mode)
            DEPLOY_MODE="$2"
            if [[ ! "$DEPLOY_MODE" =~ ^(bluegreen|inplace)$ ]]; then
                echo "错误: --mode 参数必须是 bluegreen 或 inplace"
                exit 1
            fi
            shift 2
            ;;
        -h|--help)
            echo "用法: ./deploy-backend.sh [选项] [参数]"
            echo ""
            echo "选项:"
            echo "  --jar [version]   JAR 部署模式 (默认)"
            echo "  --git [branch]    Git 部署模式"
            echo "  --env ENV         部署环境: prod (默认), test, all"
            echo "  --mode MODE       部署策略: bluegreen (默认, 零中断) 或 inplace (传统, 60s 中断)"
            echo "  --dry-run         仅构建和验证，不上传/部署"
            echo "  --rollback        回滚到上一个备份版本"
            echo "  -h, --help        显示帮助"
            echo ""
            echo "环境说明:"
            echo "  prod   生产环境 (端口 10010 blue / 10020 green, 数据库 cretas_prod_db)"
            echo "  test   测试环境 (端口 10011+8084, 数据库 cretas_db)"
            echo "  all    部署 prod (Blue-Green) + 重启 test"
            echo ""
            echo "部署策略说明:"
            echo "  bluegreen  启动 idle 实例 → 等健康 → nginx upstream 切换 → 停旧 active (零中断)"
            echo "             需要 139 Nginx upstream cretas_backend 已配置, 47 有 cretas-backend-green.service"
            echo "  inplace    替换 jar + systemctl restart cretas-backend (60s 中断, 紧急回退用)"
            echo "  注意: test 环境始终 in-place (test 没有 Green 实例)"
            echo ""
            echo "上传策略 (Steve 2026-05-28 SSH 直传, 全部 SSH-based 谁快谁赢):"
            echo "  [主通道] rsync + rsync+compress (~/.bashrc 去 SKIP_RSYNC=1 启用)"
            echo "           — 更长久更快, 增量同步对常规 deploy 有优势"
            echo "  [兜底]   scp 直传 (单 SSH stream, 实测 10.85 MB/s, 任何环境都可用)"
            echo "  [禁用]   R2 / OSS / GitHub (代码保留, ENABLE_R2=1 可紧急 opt-in)"
            echo "          超时: 15 分钟 (163MB @ ~10 MB/s ≈ 16s scp, rsync 通常更快)"
            echo ""
            echo "环境变量:"
            echo "  SKIP_RSYNC=1              临时禁用 rsync 走 scp 兜底 (~/.bashrc 已不再默认设置)"
            echo "  SKIP_BUILD=1              跳过本地 Maven 打包 (使用 target/ 已有 jar)"
            echo "  ENABLE_R2=1               紧急 rollback: 启用 R2 通道 (scp 全失败时)"
            echo "  R2_ACCESS_KEY_ID/SECRET   R2 凭证 (配合 ENABLE_R2=1 使用)"
            echo ""
            echo "示例:"
            echo "  ./deploy-backend.sh              # JAR 部署到生产"
            echo "  ./deploy-backend.sh --env test   # JAR 部署到测试"
            echo "  ./deploy-backend.sh --env all    # JAR 部署后重启两套"
            echo "  ./deploy-backend.sh --jar v1.2   # 指定版本"
            echo "  ./deploy-backend.sh --git        # Git 部署"
            echo "  ./deploy-backend.sh --dry-run    # 仅构建验证"
            echo "  ./deploy-backend.sh --rollback   # 回滚上一版本"
            exit 0
            ;;
        *)
            if [ -n "$1" ]; then
                ARG="$1"
            fi
            shift
            ;;
    esac
done

# ==================== Git Sync Pre-check ====================
# May 11 2026 stale-local-deploy bug fix: deploy builds jar via mvn package from
# local source. If local is behind origin/main (e.g. organizer admin-merged via
# gh CLI without `git pull`), deploy ships stale code. Health checks PASS (code
# compiles) but functional behavior is OLD.
# Per HARD rule feedback_organizer_must_git_pull_before_deploy.md.
#
# Jul 7 2026 事故修复: --env 含 prod (prod/all) 时启用 strict 模式 — 非
# origin/main HEAD 或脏工作树直接 ABORT (之前只 WARN, 主目录脏树+非 main
# 分支的部署把 prod 覆盖成了旧码). 必须放在 --env 解析之后才能拿到 DEPLOY_ENV.
GIT_SYNC_STRICT=""
if [[ "$DEPLOY_ENV" =~ ^(prod|all)$ ]]; then
    GIT_SYNC_STRICT="1"
fi
check_git_sync "$PROJECT_ROOT" "[0/4] Git sync pre-check..." "$GIT_SYNC_STRICT"

# ==================== 环境准备 ====================
# Windows 环境设置 PATH
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" || "$OSTYPE" == "win32" ]]; then
    export PATH="$PATH:/c/Program Files/GitHub CLI:/c/Program Files/Amazon/AWSCLIV2:/c/tools:/c/tools/apache-maven-3.9.6/bin"
fi

# 检查工具可用性
HAS_GH=false
HAS_OSS=false
HAS_R2=false

if command -v gh &> /dev/null && gh auth status &> /dev/null 2>&1; then
    HAS_GH=true
fi

if command -v ossutil &> /dev/null || command -v ossutil64 &> /dev/null; then
    HAS_OSS=true
    OSSUTIL_CMD=$(command -v ossutil || command -v ossutil64)
fi

if command -v aws &> /dev/null; then
    HAS_R2=true
fi

# rsync 健康检查 (Windows 上 rsync 二进制经常缺 DLL，能 which 但执行 exit 127)
# 也支持 SKIP_RSYNC=1 短路 (仅在 rsync over SSH 不稳定的网络环境下临时使用; Steve 国外跨境时曾需要, 回国后默认走 rsync)
HAS_RSYNC=false
RSYNC_FAIL_REASON=""
if [ "${SKIP_RSYNC:-0}" = "1" ]; then
    RSYNC_FAIL_REASON="SKIP_RSYNC=1 (用户主动禁用，建议本地 SSH 链路不稳定时使用)"
elif command -v rsync &> /dev/null; then
    if rsync --version &> /dev/null 2>&1; then
        HAS_RSYNC=true
    else
        RSYNC_FAIL_REASON="rsync 二进制不可执行 (可能缺 DLL/依赖)"
    fi
fi

# GitHub repo 可见性检测 (private repo 公共镜像无法下载 release asset)
IS_PRIVATE_REPO=false
if [ "$HAS_GH" = "true" ]; then
    REPO_VISIBILITY=$(gh api "repos/$REPO" --jq '.private' 2>/dev/null || echo "unknown")
    [ "$REPO_VISIBILITY" = "true" ] && IS_PRIVATE_REPO=true
fi

# 临时目录
UPLOAD_STATUS_DIR="/tmp/jar-upload-$$"
mkdir -p "$UPLOAD_STATUS_DIR"

cleanup() {
    rm -rf "$UPLOAD_STATUS_DIR"
    jobs -p | xargs -r kill 2>/dev/null || true
    # R43 fix: 也清 deploy lock — 否则 trap cleanup 会覆盖 acquire_deploy_lock
    # 注册的 lock cleanup trap, 导致 stale lock leak. 反复出现"另一deploy进程在跑".
    rm -f /tmp/cretas-backend-deploy.lock 2>/dev/null || true
}
trap cleanup EXIT

# ==================== Git 部署模式 ====================
deploy_git() {
    local BRANCH="${1:-steven}"
    echo "=========================================="
    echo "  Git 部署模式 - 分支: $BRANCH"
    echo "=========================================="
    echo ""
    echo "📤 推送代码到 GitHub..."
    git push origin "$BRANCH"
    echo ""
    echo "🔧 触发服务器部署..."
    ssh $SERVER "cd /www/wwwroot/cretas && ./deploy.sh $BRANCH"
    echo ""
    echo "✅ Git 部署完成!"
}

# ==================== JAR 部署模式 ====================
deploy_jar() {
    local VERSION="${1:-v$(date +%Y%m%d_%H%M%S)}"

    # 统计可用方式
    local METHODS=()
    # 上传策略 (Steve 2026-05-28): rsync 优先 (更长久更快) → scp 兜底
    # 三个通道全部 SSH-based, 不依赖云存储. 谁先 MD5 verify 谁赢.
    [ "$HAS_RSYNC" = "true" ] && METHODS+=("rsync(主)" "rsync+compress")
    METHODS+=("scp(兜底)")
    if [ "$HAS_GH" = "true" ] && [ "$IS_PRIVATE_REPO" != "true" ]; then
        METHODS+=("GitHub+镜像" "GitHub直连")
    fi
    # R2 / OSS 默认禁用, 仅诊断显示
    [ "$HAS_R2" = "true" ] && METHODS+=("R2(禁用-opt-in ENABLE_R2=1)")
    [ "$HAS_OSS" = "true" ] && METHODS+=("OSS(禁用-PUT收费)")

    echo "=========================================="
    echo "  JAR 部署 v5.0 - 版本: $VERSION"
    echo "  部署环境: $DEPLOY_ENV   策略: $DEPLOY_MODE"
    echo "  可用方式: ${METHODS[*]:-(无!)}"
    echo "=========================================="

    # 预检警告
    [ "$HAS_RSYNC" != "true" ] && [ -n "$RSYNC_FAIL_REASON" ] && \
        echo "  ⚠️  rsync 不可用: $RSYNC_FAIL_REASON"
    [ "$IS_PRIVATE_REPO" = "true" ] && \
        echo "  ⚠️  $REPO 是 private — 跳过 GitHub 阶段 (镜像无 token 无法下载 release asset)"

    if [ "${#METHODS[@]}" -eq 0 ]; then
        echo ""
        echo "❌ 没有可用的上传方式!"
        echo "   请确保至少一项可用:"
        echo "   - rsync (健康可执行)"
        echo "   - gh + GitHub auth + public repo"
        echo "   - ossutil + 有效的 ~/.ossutilconfig"
        echo "   - aws CLI + R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY"
        exit 1
    fi

    # ----- 0. Windows-only: clean up stale Java zombies before mvn -----
    # R32 (Apr 24 2026): Windows git-bash + cygwin accumulates orphan java.exe
    # processes from old IDE/test runs. Once 10+ stale JVMs pile up, cygheap
    # memory exhausts and mvnw.cmd hits `cygheap read copy failed` → Maven fork
    # fails and script crashes with "❌ Maven 打包失败". Session of Round 9 W-07
    # wasted ~15 min debugging this. Kill stale (>1 day old) java.exe first.
    if [[ "$OSTYPE" != "darwin"* ]] && [[ "$OSTYPE" != "linux"* ]] && command -v powershell >/dev/null 2>&1; then
        STALE_COUNT=$(powershell -NoProfile -Command "(Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object { \$_.StartTime -lt (Get-Date).AddDays(-1) } | Measure-Object).Count" 2>/dev/null | tr -d '[:space:]')
        if [ -n "$STALE_COUNT" ] && [ "$STALE_COUNT" -gt 0 ]; then
            echo "🧹 清理 $STALE_COUNT 个 >1 天老 java.exe zombie (Windows cygwin 资源防护)..."
            powershell -NoProfile -Command "Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object { \$_.StartTime -lt (Get-Date).AddDays(-1) } | ForEach-Object { try { Stop-Process -Id \$_.Id -Force -ErrorAction SilentlyContinue } catch {} }" 2>/dev/null || true
        fi
    fi

    # ----- 0.5. Flyway pre-flight gates -----
    # 2026-05-20 incident: 8-strike Flyway marathon (3 version dups + uncommitted
    # WIP from sister chats + target/ orphan files). Each strike: ~4 min mvn
    # package → ~3 min upload → ~4 min deploy → Spring Flyway boot fail.
    # Cumulative ~88 min wasted. These gates fail FAST (<2s) before mvn.
    # Per feedback_flyway_collision_marathon_2026_05_20.md HARD.
    echo ""
    echo "🔍 [0.5/4] Flyway pre-flight 审计 (version dups / 未提交 WIP / target 残留)..."
    FLYWAY_SRC_DIR="backend/java/cretas-api/src/main/resources/db/flyway"
    FLYWAY_TGT_DIR="backend/java/cretas-api/target/classes/db/flyway"

    if [ ! -d "$FLYWAY_SRC_DIR" ]; then
        echo "   ⚠️  $FLYWAY_SRC_DIR 不存在 — 跳过 Flyway 预检 (非典型 build context)"
    else
        # Gate 1: Flyway version duplicate detection (committed + uncommitted on disk)
        # `find ... | sort | uniq -d` lists versions that appear ≥2 times.
        # mvn package will copy ALL of them into JAR → Spring Flyway boot fails with
        # "Found more than one migration with version X".
        DUPS=$(find "$FLYWAY_SRC_DIR" -name 'V*.sql' -exec basename {} \; 2>/dev/null \
            | awk -F'__' '{print $1}' | sort | uniq -d)
        if [ -n "$DUPS" ]; then
            echo "   ❌ FATAL: Flyway version collision detected:"
            while IFS= read -r v; do
                echo "      Version $v duplicated in:"
                find "$FLYWAY_SRC_DIR" -name "${v}__*.sql" -exec echo "        {}" \;
            done <<< "$DUPS"
            echo ""
            echo "   Resolution: rename later-merged file to next free version"
            echo "   Reference: PR #79 / PR #82 (2026-05-20 incident rename pattern)"
            echo "   Override: FORCE_DEPLOY=1 $0 ... (⚠️ deploy WILL crash on Spring Flyway boot)"
            if [ "${FORCE_DEPLOY:-}" != "1" ]; then
                exit 1
            fi
            echo "   ⚠️  FORCE_DEPLOY=1 set, continuing despite collision"
        fi

        # Gate 2: Uncommitted Flyway WIP detection (untracked + newly-added on disk)
        # mvn packages whatever is on disk regardless of git status. Sister-chat
        # WIP files that aren't committed yet WILL be baked into the JAR and run
        # in prod. This was strike #2 in the 2026-05-20 marathon.
        # Format: `git status --short` emits `?? path` for untracked, `A  path` for added.
        UNTRACKED_FLY=$(git status --short "$FLYWAY_SRC_DIR/" 2>/dev/null \
            | awk '/^\?\? / || /^A  / || /^AM / {print $2}')
        if [ -n "$UNTRACKED_FLY" ]; then
            echo "   ❌ FATAL: 未提交的 Flyway 文件在工作目录 (mvn 会打进 JAR):"
            while IFS= read -r f; do
                echo "      $f"
            done <<< "$UNTRACKED_FLY"
            echo ""
            echo "   Resolution:"
            echo "     - 提交到分支: git add <file> && git commit"
            echo "     - 暂存: git stash --include-untracked"
            echo "     - 删除: git rm / rm <file>"
            echo "   Override: FORCE_DEPLOY=1 $0 ... (⚠️ JAR 含未审查的 schema 变更)"
            if [ "${FORCE_DEPLOY:-}" != "1" ]; then
                exit 1
            fi
            echo "   ⚠️  FORCE_DEPLOY=1 set, continuing with untracked Flyway files"
        fi

        # Gate 3: target/classes/db/flyway orphan detection
        # `mvn package` (without `clean`) keeps stale resources from prior builds.
        # After `git mv V_05 V_08`, target/ still has V_05 → JAR contains BOTH →
        # Spring Flyway boot fails. Strike #4 in 2026-05-20 marathon.
        # Auto-fix: rm -rf the target Flyway dir → mvn re-copies fresh on next package.
        if [ -d "$FLYWAY_TGT_DIR" ]; then
            SRC_FLY_SORTED=$(find "$FLYWAY_SRC_DIR" -name 'V*.sql' -exec basename {} \; 2>/dev/null | sort)
            TGT_FLY_SORTED=$(find "$FLYWAY_TGT_DIR" -name 'V*.sql' -exec basename {} \; 2>/dev/null | sort)
            if [ -n "$TGT_FLY_SORTED" ] && [ "$SRC_FLY_SORTED" != "$TGT_FLY_SORTED" ]; then
                echo "   ⚠️  target/classes/db/flyway 与 src/ 不一致 — 存在 orphan 残留"
                echo "   Auto-fix: rm -rf $FLYWAY_TGT_DIR (mvn package will re-populate)"
                rm -rf "$FLYWAY_TGT_DIR" 2>/dev/null || {
                    echo "   ❌ 无法删除 $FLYWAY_TGT_DIR (target/ 被锁?)"
                    echo "   手工修复: rm -rf backend/java/cretas-api/target && retry"
                    exit 1
                }
                echo "   ✓ orphan target/ Flyway 已清理"
            fi
        fi

        echo "   ✓ Flyway 预检通过 (无 version dup / 无未提交 WIP / target/ 干净)"
    fi

    # ----- 1. 本地 Maven 打包 -----
    # R25: 默认 `clean package` 强制全量重编, 防 incremental cache 漏新 Controller/DTO 签名 (R24 事故教训)
    # 如需保留 incremental build (快, 但不安全), 传 SKIP_CLEAN=1
    echo ""
    if [ -n "$SKIP_BUILD" ] && [ -f "backend/java/cretas-api/target/$JAR_NAME" ]; then
        echo "📦 [1/4] 跳过 Maven 打包 (SKIP_BUILD=1, 使用已有 JAR)"
    else
        MVN_GOALS="clean package"
        if [ -n "$SKIP_CLEAN" ]; then
            MVN_GOALS="package"
            echo "📦 [1/4] 本地 Maven 打包 (SKIP_CLEAN=1, 增量模式 — 如遇奇怪 bug 去掉 SKIP_CLEAN 重试)..."
        else
            echo "📦 [1/4] 本地 Maven 打包 (clean + package, ~90s)..."
        fi
        cd backend/java/cretas-api
        # R29: maven clean 遇 target/ 被锁时 (Windows 并发 build), 自动 rm -rf 后重试 package
        # 常见原因: 另一 session 刚跑过 mvn, JVM 未退出就锁住 protoc-dependencies
        run_mvn() {
            if [[ "$OSTYPE" == "darwin"* ]] || [[ "$OSTYPE" == "linux"* ]]; then
                chmod +x mvnw 2>/dev/null
                ./mvnw "$@" -Dmaven.test.skip=true -q
            else
                # R4 (Apr 16 2026): project requires Java 21 per pom.xml <maven.compiler.release>21</...>
                # Use existing JAVA_HOME (Zulu 21 typical install) instead of hardcoding jdk-17
                if [ -z "$JAVA_HOME" ]; then
                    for J in "C:/Program Files/Zulu/zulu-21" "C:/Program Files/Java/jdk-21" "C:/Program Files/Java/jdk-17"; do
                        [ -x "$J/bin/java.exe" ] && export JAVA_HOME="$J" && break
                    done
                fi
                ./mvnw.cmd "$@" -Dmaven.test.skip=true -q
            fi
        }
        if ! run_mvn $MVN_GOALS; then
            if [ -z "$SKIP_CLEAN" ]; then
                echo "   ⚠️  Maven clean 失败 (可能 target/ 被其他进程锁定), 强制 rm + retry package..."
                rm -rf target 2>/dev/null || true
                # 二次尝试: 直接 package (clean 已无意义因 target 已 rm)
                run_mvn package || { echo "❌ Maven 打包失败 (已 retry)"; cd ../../..; exit 1; }
                echo "   ✓ retry 打包成功"
            else
                echo "❌ Maven 打包失败"; cd ../../..; exit 1
            fi
        fi
        cd ../../..
    fi

    JAR_PATH="backend/java/cretas-api/target/$JAR_NAME"
    if [ ! -f "$JAR_PATH" ]; then
        echo "❌ JAR 文件不存在: $JAR_PATH"
        exit 1
    fi

    JAR_SIZE=$(get_file_size_human "$JAR_PATH")
    JAR_SIZE_BYTES=$(get_file_size_bytes "$JAR_PATH")
    log "INFO" "打包完成: $JAR_NAME ($JAR_SIZE, ${JAR_SIZE_BYTES} bytes)"

    # 计算本地 MD5 checksum
    LOCAL_MD5=$(md5sum "$JAR_PATH" | cut -d' ' -f1)
    echo "   ✓ MD5: $LOCAL_MD5"

    # ----- 1b. Jar 完整性预检 (防 corrupt jar 上线) -----
    # 历史事故 2026-04-24: maven 增量编译偶发产生 corrupt fat jar — 缺
    # ch.qos.logback.classic.spi.ThrowableProxy class. Spring Boot 启动后
    # 任何 exception 触发 logback rendering 都 cascade ClassNotFound, 服务
    # crashloop 但 nginx 健康检查可能仍 200 (短暂窗口). 本地预检挡在最早,
    # 早于上传 152M jar 到 R2 + 服务器部署.
    INTEGRITY_OK=true
    LOGBACK_NESTED=$(unzip -l "$JAR_PATH" 2>/dev/null | grep -oE 'BOOT-INF/lib/logback-classic-[0-9.]+\.jar' | head -1)
    if [ -z "$LOGBACK_NESTED" ]; then
        echo "❌ Jar 完整性预检失败: 缺 logback-classic-*.jar"
        INTEGRITY_OK=false
    else
        # 解 nested jar 验证 ThrowableProxy.class 存在
        TMPDIR_INT=$(mktemp -d)
        if unzip -j -q -o "$JAR_PATH" "$LOGBACK_NESTED" -d "$TMPDIR_INT" 2>/dev/null; then
            NESTED_BASENAME=$(basename "$LOGBACK_NESTED")
            if ! unzip -l "$TMPDIR_INT/$NESTED_BASENAME" 2>/dev/null | grep -q 'ch/qos/logback/classic/spi/ThrowableProxy.class'; then
                echo "❌ Jar 完整性预检失败: logback nested jar 缺 ThrowableProxy.class"
                INTEGRITY_OK=false
            fi
        else
            echo "❌ Jar 完整性预检失败: 无法解 logback nested jar"
            INTEGRITY_OK=false
        fi
        rm -rf "$TMPDIR_INT"
    fi
    if [ "$INTEGRITY_OK" = "true" ]; then
        echo "   ✓ Jar 完整性预检通过 ($LOGBACK_NESTED 含 ThrowableProxy)"
    else
        echo "   建议: cd backend/java/cretas-api && mvn clean package, 或 mvn dependency:purge-local-repository -DreResolve=false"
        cd ../../..
        exit 1
    fi

    # ----- 2. 并行上传 -----
    echo ""
    echo "📤 [2/4] 启动并行上传..."

    # 检查是否已有胜者
    check_winner() {
        [ -f "$UPLOAD_STATUS_DIR/winner" ]
    }

    # 远程 MD5 验证 + rename 为标准名
    # 参数: $1=远程临时文件名 (不含目录), $2=方法名
    verify_and_claim() {
        local TMP_FILE="$1"
        local METHOD_NAME="$2"
        if ssh -o ConnectTimeout=5 $SERVER "
            REMOTE_MD5=\$(md5sum $REMOTE_TMP/$TMP_FILE | cut -d' ' -f1)
            if [ \"\$REMOTE_MD5\" = \"$LOCAL_MD5\" ]; then
                mv -f $REMOTE_TMP/$TMP_FILE $REMOTE_TMP/$JAR_NAME
                exit 0
            else
                echo \"MD5 mismatch: \$REMOTE_MD5 vs $LOCAL_MD5\"
                rm -f $REMOTE_TMP/$TMP_FILE
                exit 1
            fi
        " 2>/dev/null; then
            if ! check_winner; then
                echo "$METHOD_NAME" > "$UPLOAD_STATUS_DIR/winner"
                echo "   [$METHOD_NAME] ✓ 完成! (MD5 verified)"
            fi
        else
            check_winner || echo "   [$METHOD_NAME] ✗ MD5 验证失败"
        fi
    }

    # === Fallback 方法1: rsync 增量传输 ===
    upload_rsync() {
        [ "$HAS_RSYNC" != "true" ] && return 1
        check_winner && return 0
        local TMP_FILE="${JAR_NAME}.rsync"
        local ERR_LOG="$UPLOAD_STATUS_DIR/rsync.err"
        echo "   [rsync] 开始上传..."
        if rsync -az --timeout=60 "$JAR_PATH" "$SERVER:$REMOTE_TMP/$TMP_FILE" 2> "$ERR_LOG"; then
            if ! check_winner; then
                verify_and_claim "$TMP_FILE" "rsync"
            fi
        else
            if ! check_winner; then
                local ERR
                ERR=$(head -2 "$ERR_LOG" 2>/dev/null | tr '\n' ' ' | sed 's/  */ /g')
                echo "   [rsync] ✗ 失败${ERR:+: $ERR}"
            fi
        fi
    }

    # === Fallback 方法2: rsync 高压缩传输 ===
    upload_rsync_compress() {
        [ "$HAS_RSYNC" != "true" ] && return 1
        check_winner && return 0
        local TMP_FILE="${JAR_NAME}.rsync_z"
        local ERR_LOG="$UPLOAD_STATUS_DIR/rsync_z.err"
        echo "   [rsync+compress] 开始压缩上传..."
        if rsync -az --compress-level=9 --timeout=60 "$JAR_PATH" "$SERVER:$REMOTE_TMP/$TMP_FILE" 2> "$ERR_LOG"; then
            if ! check_winner; then
                verify_and_claim "$TMP_FILE" "rsync+compress"
            fi
        else
            if ! check_winner; then
                local ERR
                ERR=$(head -2 "$ERR_LOG" 2>/dev/null | tr '\n' ' ' | sed 's/  */ /g')
                echo "   [rsync+compress] ✗ 失败${ERR:+: $ERR}"
            fi
        fi
    }

    # === Fallback 方法3: SCP 兜底 (Steve 2026-05-28) ===
    # 单 SSH stream 直传 — rsync 不可用时的最低门槛兜底.
    # 不依赖云存储 / rsync 二进制. 上传完 verify_and_claim 做 MD5 + rename.
    # 实测 10.85 MB/s (163M 文件 15s 完成).
    upload_scp() {
        check_winner && return 0
        local TMP_FILE="${JAR_NAME}.scp"
        local ERR_LOG="$UPLOAD_STATUS_DIR/scp.err"
        echo "   [scp] 开始 SSH 直传..."
        if scp -o ConnectTimeout=10 -o ServerAliveInterval=30 "$JAR_PATH" "$SERVER:$REMOTE_TMP/$TMP_FILE" 2> "$ERR_LOG"; then
            if ! check_winner; then
                verify_and_claim "$TMP_FILE" "scp"
            fi
        else
            if ! check_winner; then
                local ERR
                ERR=$(head -2 "$ERR_LOG" 2>/dev/null | tr '\n' ' ' | sed 's/  */ /g')
                echo "   [scp] ✗ 失败${ERR:+: $ERR}"
            fi
        fi
    }

    # === Fallback 方法4: OSS 全球加速 ===
    upload_oss_accelerate() {
        [ "$HAS_OSS" != "true" ] && return 1
        check_winner && return 0

        local TMP_FILE="${JAR_NAME}.oss"
        local OSS_LOG="$UPLOAD_STATUS_DIR/oss.log"
        echo "   [OSS加速] 使用全球加速上传..."
        local OSS_PATH="oss://${OSS_BUCKET}/${OSS_DEPLOY_PATH}${JAR_NAME}"

        if $OSSUTIL_CMD cp "$JAR_PATH" "$OSS_PATH" -f -e "$OSS_ACCELERATE_ENDPOINT" > "$OSS_LOG" 2>&1; then
            check_winner && return 0
            echo "   [OSS加速] ✓ 上传成功，服务器内网下载..."

            local INTERNAL_URL="https://${OSS_BUCKET}.${OSS_INTERNAL_ENDPOINT}/${OSS_DEPLOY_PATH}${JAR_NAME}"

            if ssh -o ConnectTimeout=5 $SERVER "
                cd $REMOTE_TMP && \
                curl -sL --connect-timeout 10 --max-time 300 -o $TMP_FILE '$INTERNAL_URL'
            " 2>/dev/null; then
                if ! check_winner; then
                    verify_and_claim "$TMP_FILE" "OSS加速"
                fi
            else
                check_winner || echo "   [OSS加速] ✗ 服务器下载失败"
            fi
        else
            if ! check_winner; then
                if grep -qE "InvalidAccessKeyId|SignatureDoesNotMatch|AccessDenied" "$OSS_LOG" 2>/dev/null; then
                    echo "   [OSS加速] ✗ AccessKey 失效或权限不足 — 请更新 ~/.ossutilconfig"
                else
                    local ERR
                    ERR=$(grep -E "Error|ErrorCode" "$OSS_LOG" 2>/dev/null | head -1 | tr -d '\n')
                    echo "   [OSS加速] ✗ 上传失败${ERR:+: $ERR}"
                fi
            fi
        fi
    }

    # === Fallback 方法6: Cloudflare R2 (默认禁用 — Steve 2026-05-28 改 scp 直传) ===
    # 保留代码 + 用 ENABLE_R2=1 显式 opt-in. 紧急 rollback 用 (scp + rsync 都失败时).
    upload_r2() {
        [ "$HAS_R2" != "true" ] && return 1
        check_winner && return 0

        local TMP_FILE="${JAR_NAME}.r2"
        echo "   [R2] 上传到 Cloudflare R2..."

        export AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID"
        export AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY"

        local R2_ENDPOINT="https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com"
        local R2_PATH="s3://${R2_BUCKET}/deploy/${JAR_NAME}"

        if aws s3 cp "$JAR_PATH" "$R2_PATH" --endpoint-url "$R2_ENDPOINT" 2>/dev/null; then
            check_winner && return 0
            echo "   [R2] ✓ 上传成功，服务器下载..."

            local R2_URL="${R2_PUBLIC_URL}/deploy/${JAR_NAME}"

            if ssh -o ConnectTimeout=5 $SERVER "
                cd $REMOTE_TMP && \
                curl -sL --connect-timeout 10 --max-time 300 -o $TMP_FILE '$R2_URL'
            " 2>/dev/null; then
                if ! check_winner; then
                    verify_and_claim "$TMP_FILE" "R2"
                fi
            else
                check_winner || echo "   [R2] ✗ 服务器下载失败"
            fi
        else
            check_winner || echo "   [R2] ✗ 上传失败"
        fi
    }

    # 记录开始时间
    UPLOAD_START_TIME=$(date +%s)

    # 存储所有后台进程的 PID
    UPLOAD_PIDS=()

    # ===== 阶段1: GitHub 并行竞争 (直连 + 5镜像) =====
    # private repo 跳过整个 GitHub 阶段:
    # - GitHub release asset 对未授权请求返回 9 字节 "Not Found" 文本
    # - 所有公共镜像 (ghproxy.cc/ghfast.top/...) 都不持有用户 token
    # - 直连下载 curl 也不带 token，结果一样
    # - 走 fallback (rsync/OSS/R2) 更稳
    #
    # 环境变量 SKIP_GITHUB=1 (默认 true, R4 2026-04-16) 直接走 R2 fallback —
    # GitHub Release 阶段在国内网络极不稳定, 60s 超时浪费时间, R2 更快.
    # 如需恢复 GitHub: export SKIP_GITHUB=0
    SKIP_GITHUB="${SKIP_GITHUB:-1}"
    if [ "$HAS_GH" = "true" ] && [ "$IS_PRIVATE_REPO" != "true" ] && [ "$SKIP_GITHUB" != "1" ]; then
        echo "   [阶段1] GitHub 并行上传 (直连 + ${#GITHUB_MIRRORS[@]}镜像)..."

        # 先创建 Release (stderr 写日志，不要吞)
        echo "   创建 GitHub Release..."
        gh release delete "$VERSION" --repo "$REPO" -y > "$UPLOAD_STATUS_DIR/gh-delete.log" 2>&1 || true

        GH_LOG="$UPLOAD_STATUS_DIR/gh-release.log"
        if gh release create "$VERSION" "$JAR_PATH" \
            --repo "$REPO" \
            --title "Release $VERSION" \
            --notes "Auto release $(date '+%Y-%m-%d %H:%M:%S')" > "$GH_LOG" 2>&1; then
            echo "   ✓ Release 创建成功"

            # GitHub 直连下载
            (
                sleep 1  # 等待 Release 生效
                [ -f "$UPLOAD_STATUS_DIR/winner" ] && exit 0
                local URL="https://github.com/$REPO/releases/download/$VERSION/$JAR_NAME"
                local TMP_FILE="${JAR_NAME}.github_direct"
                echo "   [GitHub直连] 开始下载..."
                if ssh -o ConnectTimeout=5 $SERVER "
                    cd $REMOTE_TMP && \
                    curl -sL --connect-timeout 15 --max-time 300 -o $TMP_FILE '$URL'
                " 2>/dev/null; then
                    if [ ! -f "$UPLOAD_STATUS_DIR/winner" ]; then
                        verify_and_claim "$TMP_FILE" "GitHub直连"
                    fi
                fi
            ) &
            UPLOAD_PIDS+=($!)

            # 所有镜像并行
            for mirror in "${GITHUB_MIRRORS[@]}"; do
                (
                    sleep 1
                    [ -f "$UPLOAD_STATUS_DIR/winner" ] && exit 0
                    local URL="https://${mirror}/https://github.com/$REPO/releases/download/$VERSION/$JAR_NAME"
                    local SAFE_MIRROR=$(echo "$mirror" | tr '.' '_')
                    local TMP_FILE="${JAR_NAME}.gh_${SAFE_MIRROR}"
                    if ssh -o ConnectTimeout=5 $SERVER "
                        cd $REMOTE_TMP && \
                        curl -sL --connect-timeout 10 --max-time 300 -o $TMP_FILE '$URL'
                    " 2>/dev/null; then
                        if [ ! -f "$UPLOAD_STATUS_DIR/winner" ]; then
                            verify_and_claim "$TMP_FILE" "GitHub/$mirror"
                        fi
                    fi
                ) &
                UPLOAD_PIDS+=($!)
            done
        else
            echo "   ✗ Release 创建失败:"
            sed 's/^/        /' "$GH_LOG" 2>/dev/null | head -10
            echo "   跳过 GitHub 方式"
        fi
    elif [ "$IS_PRIVATE_REPO" = "true" ]; then
        echo "   [阶段1] 跳过 GitHub (private repo — 见预检警告)"
    elif [ "$SKIP_GITHUB" = "1" ]; then
        echo "   [阶段1] 跳过 GitHub (SKIP_GITHUB=1, 走 SSH 直传 rsync/scp — 默认)"
    fi

    # 等待 GitHub 方式完成 (最多60秒)
    # private repo / 无 gh / SKIP_GITHUB=1 时，GitHub 阶段从没启动过，直接跳到 fallback
    WINNER=""
    if [ "$HAS_GH" = "true" ] && [ "$IS_PRIVATE_REPO" != "true" ] && [ "$SKIP_GITHUB" != "1" ] && [ "${#UPLOAD_PIDS[@]}" -gt 0 ]; then
        echo ""
        echo "   等待 GitHub 下载完成 (超时: 60秒)..."
        GITHUB_TIMEOUT=60
        ELAPSED=0

        while [ -z "$WINNER" ] && [ $ELAPSED -lt $GITHUB_TIMEOUT ]; do
            if [ -f "$UPLOAD_STATUS_DIR/winner" ]; then
                WINNER=$(cat "$UPLOAD_STATUS_DIR/winner")
                break
            fi
            sleep 2
            ELAPSED=$((ELAPSED + 2))

            if [ $((ELAPSED % 20)) -eq 0 ]; then
                echo "   ... 已等待 ${ELAPSED}s"
            fi
        done
    fi

    # ===== 阶段2: Fallback (GitHub 超时或失败) =====
    if [ -z "$WINNER" ]; then
        echo ""
        echo "   [阶段2] GitHub 超时，启动 Fallback 方式..."

        # 终止 GitHub 相关进程
        for pid in "${UPLOAD_PIDS[@]}"; do
            kill -9 "$pid" 2>/dev/null || true
            pkill -9 -P "$pid" 2>/dev/null || true
        done
        UPLOAD_PIDS=()

        # 杀掉服务器上残留的 GitHub 下载 curl 进程
        ssh -o ConnectTimeout=5 $SERVER "pkill -f 'curl.*$JAR_NAME' 2>/dev/null; true" 2>/dev/null || true

        # 启动 Fallback 方式 (按工具可用性决定) — Steve 2026-05-28 SSH 直传策略
        # 通道顺序 (并行 race, 谁先 MD5 verify 谁赢):
        #   1. rsync (主)        — ~/.bashrc 去 SKIP_RSYNC=1 即启用, 更长久更快
        #   2. rsync+compress    — 同上, 高压缩对 jar 收益小但偶尔最快
        #   3. scp (兜底)        — 单 stream SSH, 任何环境都可用 (实测 10.85 MB/s)
        # R2 / OSS / GitHub 默认禁用, 代码保留供紧急 opt-in (ENABLE_R2=1)
        [ "$HAS_RSYNC" = "true" ] && { upload_rsync & UPLOAD_PIDS+=($!); }
        [ "$HAS_RSYNC" = "true" ] && { upload_rsync_compress & UPLOAD_PIDS+=($!); }
        { upload_scp & UPLOAD_PIDS+=($!); }
        # 紧急 rollback 通道 (默认禁用 — Steve 2026-05-28):
        # [ "$HAS_R2" = "true" ] && { upload_r2 & UPLOAD_PIDS+=($!); }                  # 禁用: 改 scp 直传
        # [ "$HAS_OSS" = "true" ] && { upload_oss_accelerate & UPLOAD_PIDS+=($!); }     # 禁用: OSS PUT 收费
        [ "${ENABLE_R2:-0}" = "1" ] && [ "$HAS_R2" = "true" ] && { upload_r2 & UPLOAD_PIDS+=($!); echo "   [R2] opt-in 紧急通道启用 (ENABLE_R2=1)"; }

        if [ "${#UPLOAD_PIDS[@]}" -eq 0 ]; then
            echo "   ❌ 没有可用的 Fallback 方式 (scp/rsync/r2 均不可用)"
        fi

        # 等待 Fallback 完成. scp 主通道实测 15s (163MB @ 10.85 MB/s) → 默认 300s 足够;
        # 慢 ISP / rsync 单线程 可用 FALLBACK_TIMEOUT 环境变量放宽 (如 FALLBACK_TIMEOUT=900).
        FALLBACK_TIMEOUT="${FALLBACK_TIMEOUT:-300}"
        echo "   等待 Fallback 完成 (超时: ${FALLBACK_TIMEOUT}s)..."
        ELAPSED=0

        while [ -z "$WINNER" ] && [ $ELAPSED -lt $FALLBACK_TIMEOUT ]; do
            if [ -f "$UPLOAD_STATUS_DIR/winner" ]; then
                WINNER=$(cat "$UPLOAD_STATUS_DIR/winner")
                break
            fi
            sleep 2
            ELAPSED=$((ELAPSED + 2))

            if [ $((ELAPSED % 30)) -eq 0 ]; then
                echo "   ... Fallback 已等待 ${ELAPSED}s"
            fi
        done
    fi

    # 计算上传耗时
    UPLOAD_END_TIME=$(date +%s)
    UPLOAD_DURATION=$((UPLOAD_END_TIME - UPLOAD_START_TIME))

    # 强制终止所有后台进程及其子进程
    echo "   终止其他上传任务..."

    # 方法1: 终止记录的 PID 及其所有子进程
    for pid in "${UPLOAD_PIDS[@]}"; do
        if kill -0 "$pid" 2>/dev/null; then
            # 获取该进程的所有子进程并终止
            pkill -9 -P "$pid" 2>/dev/null || true
            kill -9 "$pid" 2>/dev/null || true
        fi
    done

    # 方法2: 终止可能残留的 ossutil/aws 进程
    pkill -9 -f "ossutil.*$JAR_NAME" 2>/dev/null || true
    pkill -9 -f "aws.*$JAR_NAME" 2>/dev/null || true
    pkill -9 -f "aws s3 cp.*cretas" 2>/dev/null || true

    # 方法3: 终止当前脚本的所有后台任务
    jobs -p 2>/dev/null | xargs -r kill -9 2>/dev/null || true

    # 方法4: 杀掉服务器上残留的 curl/wget (防止 orphan 覆盖 winner 文件)
    ssh -o ConnectTimeout=5 $SERVER "pkill -f 'curl.*$JAR_NAME' 2>/dev/null; true" 2>/dev/null || true

    sleep 1
    wait 2>/dev/null || true

    # 重新读取 winner 文件 (subshell-scope fix):
    # upload_r2 / upload_rsync 等在后台 subshell 里 echo "$METHOD" > winner,
    # 但父 shell 的 $WINNER 变量只在 polling loop 里更新. 如果一个 uploader
    # 在 polling timeout 之后 / kill -9 之前 (line 762-768) 刚好 flush 了 winner 文件,
    # $WINNER 会是空字符串但磁盘上 winner 文件存在 → 误报"所有上传方式都失败".
    # 在 wait 后重读 winner 文件可消除这个 race.
    if [ -z "$WINNER" ] && [ -f "$UPLOAD_STATUS_DIR/winner" ]; then
        WINNER=$(cat "$UPLOAD_STATUS_DIR/winner" 2>/dev/null)
        [ -n "$WINNER" ] && echo "   ℹ️  上传胜出方 (post-wait race-fix 检测): $WINNER"
    fi

    # 清理服务器上的临时文件 (保留 winner 的 $JAR_NAME)
    ssh -o ConnectTimeout=5 $SERVER "rm -f $REMOTE_TMP/${JAR_NAME}.scp $REMOTE_TMP/${JAR_NAME}.rsync $REMOTE_TMP/${JAR_NAME}.rsync_z $REMOTE_TMP/${JAR_NAME}.oss $REMOTE_TMP/${JAR_NAME}.r2 $REMOTE_TMP/${JAR_NAME}.github_direct $REMOTE_TMP/${JAR_NAME}.gh_* 2>/dev/null; true" 2>/dev/null || true

    if [ -z "$WINNER" ]; then
        # 最后的兜底: 即使本地 winner flag 缺失, 远程 jar 若存在且 MD5 匹配, 视为上传成功
        # (防御 winner 文件被 kill -9 之前 race 掉但 server-side jar 已落地的极端 case)
        REMOTE_MD5_CHECK=$(ssh -o ConnectTimeout=5 $SERVER "[ -f $REMOTE_TMP/$JAR_NAME ] && md5sum $REMOTE_TMP/$JAR_NAME | cut -d' ' -f1" 2>/dev/null)
        if [ -n "$REMOTE_MD5_CHECK" ] && [ "$REMOTE_MD5_CHECK" = "$LOCAL_MD5" ]; then
            WINNER="unknown (post-wait MD5 verified)"
            echo "   ℹ️  上传方式未记录 winner 但服务器 jar MD5 匹配,视为成功"
        else
            echo ""
            echo "❌ 所有上传方式都失败或超时"
            exit 1
        fi
    fi

    # 计算速度 (兼容不同系统)
    if command -v bc &> /dev/null && [ -n "$JAR_SIZE_BYTES" ] && [ "$UPLOAD_DURATION" -gt 0 ]; then
        SPEED_MBPS=$(echo "scale=2; $JAR_SIZE_BYTES / 1024 / 1024 / $UPLOAD_DURATION" | bc 2>/dev/null)
    else
        # Fallback: 使用 awk 计算
        SPEED_MBPS=$(awk "BEGIN {printf \"%.2f\", $JAR_SIZE_BYTES / 1024 / 1024 / $UPLOAD_DURATION}" 2>/dev/null || echo "N/A")
    fi

    echo ""
    echo "   🏆 胜出: $WINNER"
    echo "   ⏱️  耗时: ${UPLOAD_DURATION}s"
    echo "   📊 速度: ${SPEED_MBPS} MB/s (${JAR_SIZE} 文件)"

    # ----- 3. 服务器部署 -----
    echo ""
    echo "🚀 [3/4] 服务器部署..."
    # MD5 验证 + 部署 JAR (不含重启)
    DEPLOY_OK=false
    if ssh $SERVER "
        cd $REMOTE_JAR_DIR

        # 备份当前 JAR
        if [ -f aims-0.0.1-SNAPSHOT.jar ]; then
            BACKUP_NAME=\"aims-0.0.1-SNAPSHOT.jar.bak.\$(date +%Y%m%d_%H%M%S)\"
            cp aims-0.0.1-SNAPSHOT.jar \"\$BACKUP_NAME\"
            echo \"   备份: \$BACKUP_NAME\"
            ls -t aims-0.0.1-SNAPSHOT.jar.bak.* 2>/dev/null | tail -n +4 | xargs rm -f 2>/dev/null || true
        fi

        # 最终 MD5 验证 (部署前)
        REMOTE_MD5=\$(md5sum $REMOTE_TMP/$JAR_NAME | cut -d' ' -f1)
        echo \"   MD5 验证: \$REMOTE_MD5 (预期: $LOCAL_MD5)\"
        if [ \"\$REMOTE_MD5\" != \"$LOCAL_MD5\" ]; then
            echo '   ❌ JAR 文件 MD5 不匹配，中止部署!'
            exit 1
        fi

        # 部署 JAR
        mv $REMOTE_TMP/$JAR_NAME aims-0.0.1-SNAPSHOT.jar

        # 验证部署后文件完整性
        DEPLOYED_MD5=\$(md5sum aims-0.0.1-SNAPSHOT.jar | cut -d' ' -f1)
        if [ \"\$DEPLOYED_MD5\" != \"$LOCAL_MD5\" ]; then
            echo '   ❌ 部署后 checksum 不匹配! 恢复备份...'
            if [ -n \"\$BACKUP_NAME\" ] && [ -f \"\$BACKUP_NAME\" ]; then
                cp \"\$BACKUP_NAME\" aims-0.0.1-SNAPSHOT.jar
                echo '   ✓ 已恢复备份'
            fi
            exit 1
        fi
        echo '   ✓ MD5 验证通过'
    "; then
        DEPLOY_OK=true
    fi

    if [ "$DEPLOY_OK" != "true" ]; then
        echo "   ❌ 部署失败 (MD5 不匹配或文件损坏)"
        exit 1
    fi

    # 清理 .jar.new (防止 restart.sh 的 auto-swap 覆盖刚部署的 JAR)
    ssh -o ConnectTimeout=5 $SERVER "rm -f $REMOTE_JAR_DIR/aims-0.0.1-SNAPSHOT.jar.new 2>/dev/null" 2>/dev/null || true

    # 重启服务: 根据 --env + --mode 选择策略
    # - prod + bluegreen  → 启动 idle → 切 upstream → 停旧 active (零中断)
    # - prod + inplace    → systemctl restart cretas-backend (60s 中断, 紧急回退)
    # - test              → restart.sh test (test 没有 Green 实例, 始终 in-place)
    # - all               → prod 走 bluegreen/inplace, test 走 in-place

    if [[ "$DEPLOY_ENV" == "prod" || "$DEPLOY_ENV" == "all" ]] && [ "$DEPLOY_MODE" = "bluegreen" ]; then
        echo ""
        echo "🔄 [3b] Blue-Green 切换..."

        # 检测当前 active port (从 139 nginx upstream 读)
        # 用 grep -oP 而不是 awk, 避免 shell $3 扩展问题
        ACTIVE_PORT=$(ssh $GATEWAY "grep -oP 'server 47\\.100\\.235\\.168:\\K[0-9]+' $NGINX_UPSTREAM_FILE | head -1" 2>/dev/null)

        if [ -z "$ACTIVE_PORT" ] || [[ ! "$ACTIVE_PORT" =~ ^(10010|10020)$ ]]; then
            echo "   ❌ 无法检测 nginx upstream active port (got: '$ACTIVE_PORT')"
            echo "   请确认 $NGINX_UPSTREAM_FILE 在 139 存在且含 'server 47.100.235.168:10010;' 这样的行"
            echo "   回退到 in-place 部署"
            DEPLOY_MODE="inplace"
        else
            if [ "$ACTIVE_PORT" = "$BLUE_PORT" ]; then
                IDLE_COLOR="green"; IDLE_SERVICE="$GREEN_SERVICE"; IDLE_PORT="$GREEN_PORT"
                ACTIVE_COLOR="blue"; ACTIVE_SERVICE="$BLUE_SERVICE"
            else
                IDLE_COLOR="blue"; IDLE_SERVICE="$BLUE_SERVICE"; IDLE_PORT="$BLUE_PORT"
                ACTIVE_COLOR="green"; ACTIVE_SERVICE="$GREEN_SERVICE"
            fi

            echo "   当前 active: $ACTIVE_COLOR ($ACTIVE_PORT) → 切换到: $IDLE_COLOR ($IDLE_PORT)"

            # [BG 0/4] 内存预检 (2026-06-25): Blue-Green 峰值同时跑新旧两个 prod 实例 (~2×2.5GB)。
            # 47 是 14GB 共享机, 若 test 实例 (Xmx1500m) 同时在跑, idle 实例启动可能 OOM 崩溃 (实测).
            # 可用内存不足时临时停 test 腾内存, [BG 4/4] 停旧 active 后自动恢复。
            TEST_STOPPED_FOR_MEM=0
            AVAIL_MB=$(ssh $SERVER "free -m | awk '/^Mem:/{print \$7}'" 2>/dev/null)
            if [ -n "$AVAIL_MB" ] && [ "$AVAIL_MB" -lt 3500 ]; then
                echo "   [BG 0/4] ⚠️  可用内存 ${AVAIL_MB}MB < 3500MB → 临时停 cretas-backend-test 腾内存"
                if ssh $SERVER "systemctl is-active cretas-backend-test 2>/dev/null" | grep -q '^active'; then
                    ssh $SERVER "systemctl stop cretas-backend-test" 2>/dev/null && TEST_STOPPED_FOR_MEM=1 \
                        && echo "   [BG 0/4] ✓ 已停 test (部署完自动恢复)"
                fi
            else
                echo "   [BG 0/4] 内存预检 OK (可用 ${AVAIL_MB:-?}MB)"
            fi

            # [BG 1/4] 启动 idle service (它会读取刚部署的新 jar)
            echo "   [BG 1/4] 启动 $IDLE_COLOR ($IDLE_SERVICE)..."
            if ! ssh $SERVER "systemctl restart $IDLE_SERVICE"; then
                echo "   ❌ 无法启动 $IDLE_SERVICE, 中止切换"
                exit 1
            fi

            # [BG 2/4] 等 idle 健康 — 单次 ssh + 远端 loop
            # 避免 client-side ssh roundtrip 每轮建立连接导致的卡死
            echo "   [BG 2/4] 等待 $IDLE_COLOR 健康 (远端 loop, 最多 150s)..."
            BG_T0=$(date +%s)
            IDLE_HEALTHY=false
            BG_RESULT=$(ssh -o ConnectTimeout=10 -o ServerAliveInterval=15 $SERVER "
                for i in \$(seq 1 150); do
                    STATUS=\$(curl -s -o /dev/null --max-time 2 -w '%{http_code}' http://localhost:$IDLE_PORT/api/mobile/health 2>/dev/null)
                    if [ \"\$STATUS\" = '200' ]; then
                        echo \"UP:\${i}\"
                        exit 0
                    fi
                    sleep 1
                done
                echo 'TIMEOUT'
                exit 1
            " 2>/dev/null)
            BG_EXIT=$?
            BG_ELAPSED=$(( $(date +%s) - BG_T0 ))

            if [ "$BG_EXIT" = "0" ] && [[ "$BG_RESULT" == UP:* ]]; then
                echo "   ✓ $IDLE_COLOR 健康 (${BG_ELAPSED}s, 远端计数: ${BG_RESULT#UP:}s)"
                IDLE_HEALTHY=true
            else
                echo "   ❌ $IDLE_COLOR 健康检查失败 (${BG_ELAPSED}s elapsed, result: '$BG_RESULT')"
                echo "   保持原 active 不切换, 停止 idle"
                ssh $SERVER "systemctl stop $IDLE_SERVICE" 2>/dev/null || true
                exit 1
            fi

            # [BG 3/4] 切换 139 nginx upstream
            # Issue #209: 同时重写 inline `# ACTIVE=<port>` 注释, 防止 comment-vs-config drift
            # (历史上 comment 永远不变, 操作员看不到当前 active port). sed 一次性匹配整行
            # `server 47.100.235.168:<active>;[<comment tail>]` 然后 emit 新 port + 新 comment.
            echo "   [BG 3/4] 切换 139 nginx upstream: $ACTIVE_PORT → $IDLE_PORT..."
            SWITCH_DATE=$(date +%Y-%m-%d)
            if ! ssh $GATEWAY "
                sed -i 's|server 47\\.100\\.235\\.168:$ACTIVE_PORT;.*\$|server 47.100.235.168:$IDLE_PORT;  # ACTIVE=$IDLE_PORT (switched $SWITCH_DATE) — auto-synced by deploy-backend.sh|' $NGINX_UPSTREAM_FILE &&
                nginx -t >/dev/null 2>&1 &&
                nginx -s reload
            "; then
                echo "   ❌ nginx upstream 切换失败, 回滚 upstream 并停 idle"
                ssh $GATEWAY "sed -i 's|server 47\\.100\\.235\\.168:$IDLE_PORT;.*\$|server 47.100.235.168:$ACTIVE_PORT;  # ACTIVE=$ACTIVE_PORT (rolled back $SWITCH_DATE) — auto-synced by deploy-backend.sh|' $NGINX_UPSTREAM_FILE && nginx -s reload" 2>/dev/null || true
                ssh $SERVER "systemctl stop $IDLE_SERVICE" 2>/dev/null || true
                exit 1
            fi
            echo "   ✓ upstream 切换完成 (含 ACTIVE 注释自动同步)"

            # 切换后验证 — v5.3: 多次健康 check + auto-rollback
            # 历史事故: 2026-04-24 by47kihv7 部署 corrupt jar (logback ClassNotFound),
            # 单次 sleep 1 + 1次 verify 不够, jar 可能在 1s 后才 crash. 现在 5 轮
            # 间隔 6s 持续监测, 任何一次 nginx 返非 2xx 就 auto-rollback (切回旧 upstream
            # + 重启旧 active service).
            POST_SWITCH_HEALTHY=true
            for ROUND in 1 2 3 4 5; do
                sleep 6
                VERIFY=$(ssh $GATEWAY "curl -sk -o /dev/null --max-time 5 -w '%{http_code}' -H 'Host: api.cretaceousfuture.com' https://127.0.0.1/api/mobile/health" 2>/dev/null)
                # 同时检查新 idle systemd 是否 still running (catch crashloop early)
                IDLE_RUNNING=$(ssh $SERVER "systemctl is-active $IDLE_SERVICE 2>&1" 2>/dev/null)
                if [ "$VERIFY" = "200" ] && [ "$IDLE_RUNNING" = "active" ]; then
                    echo "   ✓ 切换后健康轮次 $ROUND/5: HTTP=$VERIFY systemd=$IDLE_RUNNING"
                else
                    echo "   ❌ 切换后健康轮次 $ROUND/5 失败: HTTP=$VERIFY systemd=$IDLE_RUNNING"
                    POST_SWITCH_HEALTHY=false
                    break
                fi
            done

            if [ "$POST_SWITCH_HEALTHY" != "true" ]; then
                echo "   🔄 auto-rollback: 切回旧 upstream ($ACTIVE_COLOR $ACTIVE_PORT) + 重启旧 active"
                ssh $GATEWAY "
                    sed -i 's|server 47\\.100\\.235\\.168:$IDLE_PORT;.*\$|server 47.100.235.168:$ACTIVE_PORT;  # ACTIVE=$ACTIVE_PORT (rolled back $SWITCH_DATE) — auto-synced by deploy-backend.sh|' $NGINX_UPSTREAM_FILE &&
                    nginx -t >/dev/null 2>&1 &&
                    nginx -s reload
                " 2>/dev/null || echo "   ⚠️  rollback nginx 失败, 需手动: vi $NGINX_UPSTREAM_FILE && nginx -s reload"
                # 重启旧 active (jar 文件已被新 jar 覆盖, 但可从最近备份恢复)
                LAST_BAK=$(ssh $SERVER "ls -t /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar.bak.* 2>/dev/null | head -1")
                if [ -n "$LAST_BAK" ]; then
                    ssh $SERVER "
                        cp '$LAST_BAK' /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar &&
                        systemctl reset-failed $ACTIVE_SERVICE 2>/dev/null || true
                        systemctl restart $ACTIVE_SERVICE
                    " 2>/dev/null || echo "   ⚠️  rollback 重启 $ACTIVE_SERVICE 失败"
                    echo "   ↻ 已恢复 jar 到 $LAST_BAK + 重启 $ACTIVE_COLOR"
                else
                    echo "   ⚠️  无可回滚的备份 jar — 当前 jar 仍是 corrupt 版本, 需 git 重新部署"
                fi
                ssh $SERVER "systemctl stop $IDLE_SERVICE" 2>/dev/null || true
                exit 1
            fi
            echo "   ✓ 切换后验证全部通过 (5/5 轮 nginx 200 + idle systemd active)"

            # [BG 4/4] 停旧 active (5s 优雅等待让现有连接完成)
            echo "   [BG 4/4] 停旧 active ($ACTIVE_COLOR $ACTIVE_SERVICE), 5s 优雅等待..."
            sleep 5
            ssh $SERVER "systemctl stop $ACTIVE_SERVICE" || true

            # [BG 0/4 恢复] 旧 active 已停, 内存释放 → 恢复之前为腾内存停掉的 test 实例
            if [ "$TEST_STOPPED_FOR_MEM" = "1" ]; then
                ssh $SERVER "systemctl start cretas-backend-test" 2>/dev/null \
                    && echo "   [BG] ✓ 已恢复 cretas-backend-test (之前为腾内存临时停)" \
                    || echo "   [BG] ⚠️  恢复 cretas-backend-test 失败, 请手动 systemctl start cretas-backend-test"
            fi

            # [BG 5/5] Systemd 收尾 (v5.2):
            # - stop 后 SIGTERM 可能把旧 active 标记为 'failed' (status=143), 清理之
            # - 验证新 active service 状态 + 端口监听
            # - 幂等: 如新 active systemd 非 running, 尝试 restart 一次
            # - 🆕 v5.2: 反僵尸保护 — 如果旧 active 端口仍在 listen, systemd 失联了,
            #   强制 kill -9 残留进程. 历史事故 (2026-04-15):
            #   cretas-backend-green.service 超过 StartLimitBurst 后 systemd 放弃管理,
            #   但最后一次 spawn 的 java 进程继续存活, 两个 JVM 同时跑 @Scheduled →
            #   BehaviorCalibrationScheduler 撞 uk_factory_tool_date. 这种残留
            #   必须 kill, 否则 shedlock 之前的部署会复现同样的数据库 PK 冲突.
            echo "   [BG 5/5] Systemd 收尾检查..."
            ssh $SERVER "
                # 清理旧 active 的 failed 状态 (SIGTERM 导致的 exit 143 会被记为 failed)
                systemctl reset-failed $ACTIVE_SERVICE 2>/dev/null || true

                # 验证新 active 在 running
                if ! systemctl is-active --quiet $IDLE_SERVICE; then
                    echo '   ⚠️  新 active ($IDLE_SERVICE) systemd 非 running, 尝试 restart'
                    systemctl reset-failed $IDLE_SERVICE 2>/dev/null || true
                    systemctl restart $IDLE_SERVICE
                    sleep 10
                    systemctl is-active --quiet $IDLE_SERVICE || { echo '   ❌ 新 active systemd 仍非 running'; exit 1; }
                fi

                # 验证新 active 端口监听
                if ! ss -tln | grep -q ':$IDLE_PORT '; then
                    echo '   ❌ 新 active 端口 $IDLE_PORT 未监听'
                    exit 1
                fi

                # v5.2 反僵尸: 确认旧 active 端口已释放
                sleep 2
                ORPHAN_PIDS=\$(lsof -ti :$ACTIVE_PORT 2>/dev/null || true)
                if [ -n \"\$ORPHAN_PIDS\" ]; then
                    echo '   ⚠️  旧 active 端口 $ACTIVE_PORT 仍被占用 (PIDs:' \$ORPHAN_PIDS '), systemd 失联'
                    echo '   → 强制 kill -9 残留进程 (反僵尸 v5.2)'
                    kill -9 \$ORPHAN_PIDS 2>/dev/null || true
                    sleep 1
                    if lsof -ti :$ACTIVE_PORT >/dev/null 2>&1; then
                        echo '   ❌ 端口 $ACTIVE_PORT 仍未释放, 请手动排查'
                        exit 1
                    fi
                    echo '   ✓ 残留进程已清理, 端口 $ACTIVE_PORT 已释放'
                fi

                echo '   ✓ 新 active ($IDLE_SERVICE) systemd running + 端口 $IDLE_PORT 监听'
                echo '   ✓ 旧 active ($ACTIVE_SERVICE) failed 状态已清理'
                echo '   ✓ 端口 $ACTIVE_PORT 无残留 (反僵尸 OK)'
            " || echo "   ⚠️  systemd 收尾检查有警告, 请手动 verify"

            echo "   ✅ Blue-Green 切换完成: $ACTIVE_COLOR → $IDLE_COLOR"
        fi
    fi

    # test 环境 或 prod 回退到 in-place
    if [ "$DEPLOY_ENV" = "test" ] || [ "$DEPLOY_MODE" = "inplace" ] || [ "$DEPLOY_ENV" = "all" ]; then
        # all 模式下: prod 已经 bluegreen 切换完, 这里重启 test
        # test 模式下: 直接重启 test
        # inplace 模式下: 直接 restart.sh prod/test/all
        if [ "$DEPLOY_MODE" = "inplace" ]; then
            echo "   重启服务 (环境: $DEPLOY_ENV, in-place)..."
            ssh $SERVER "cd $REMOTE_JAR_DIR && bash restart.sh $DEPLOY_ENV" || true
        elif [ "$DEPLOY_ENV" = "all" ]; then
            echo ""
            echo "   重启 test 环境..."
            ssh $SERVER "cd $REMOTE_JAR_DIR && bash restart.sh test" || true
        else
            echo "   重启服务 (环境: $DEPLOY_ENV)..."
            ssh $SERVER "cd $REMOTE_JAR_DIR && bash restart.sh $DEPLOY_ENV" || true
        fi
    fi

    # 清理残留临时文件
    ssh -o ConnectTimeout=5 $SERVER "rm -f $REMOTE_TMP/${JAR_NAME}.* $REMOTE_TMP/aims-new.jar $REMOTE_TMP/deploy.jar.gz 2>/dev/null" 2>/dev/null || true

    # ----- 4. 验证部署 -----
    echo ""
    echo "🔍 [4/4] 验证部署..."
    SERVER_IP="${SERVER#*@}"

    # 生产验证:
    # - bluegreen 模式: 通过 139 nginx upstream 验证 (不直接打 10010/10020, 因为可能其中一个已停)
    # - inplace 模式: 直接打 10010
    if [[ "$DEPLOY_ENV" == "prod" || "$DEPLOY_ENV" == "all" ]]; then
        if [ "$DEPLOY_MODE" = "bluegreen" ]; then
            echo "   [生产] 通过 nginx upstream 验证 (Blue-Green)..."
            # `|| echo "000"` 防 set -e: ssh GATEWAY 失败 → 让 if-check 走异常分支, 不杀 deploy.
            # (Sweep follow-up to #556 — same bug class as the 'exit 28 cosmetic' fix.)
            PROD_STATUS=$(ssh -o ConnectTimeout=5 $GATEWAY "curl -sk -o /dev/null --max-time 5 -w '%{http_code}' -H 'Host: api.cretaceousfuture.com' https://127.0.0.1/api/mobile/health" 2>/dev/null || echo "000")
            if [ "$PROD_STATUS" = "200" ]; then
                echo "   ✓ 生产服务正常 (HTTP 200 via nginx)"
            else
                echo "   ⚠️  生产验证异常 (HTTP $PROD_STATUS via nginx), 请手动排查"
            fi
        else
            echo "   [生产] 检查 10010..."
            # 2026-05-18: SG 收紧后 public-IP curl 拿 HTTP 000, 改 SSH localhost.
            # Round 5 fix: bumped 30→90 same as test path (Spring Boot + BERT startup).
            if ! wait_for_health_via_ssh "$SERVER" 10010 /api/mobile/health 90 2; then
                echo "   请手动检查: ssh $SERVER 'tail -50 $REMOTE_JAR_DIR/cretas-prod.log'"
            fi
        fi
    fi

    if [[ "$DEPLOY_ENV" == "test" || "$DEPLOY_ENV" == "all" ]]; then
        echo "   [测试] 检查 10011..."
        # 2026-05-18: SG 收紧 (10011 仅放行 139/32) → public-IP curl 永远 HTTP 000.
        # 上一版 wait_for_health "http://${SERVER_IP}:10011/..." 在 --env all 模式下
        # 永远 240s timeout, 误判 test 挂掉 → 脚本不再继续 deploy prod → prod 整段
        # 时间 DOWN (今天踩过一次). 改 SSH localhost 绕过 SG.
        # 240s: Spring Boot startup + intent cache 13s + buffer (issue #255)
        if ! wait_for_health_via_ssh "$SERVER" 10011 /api/mobile/health 120 2; then
            echo "   请手动检查: ssh $SERVER 'tail -50 $REMOTE_JAR_DIR/cretas-test.log'"
        fi
    fi

    # 防御性检查: 部署 prod 时也 ping test (反之亦然), 发现另一环境挂了就警告
    if [[ "$DEPLOY_ENV" == "prod" ]]; then
        # SSH 到 47 走 loopback (10011 SG 仅放行 139/32, 本地 curl 永远 timeout → 旧版 exit 28).
        # `|| echo "000"` 双重保险: ssh/curl 任一失败都不让 set -e 杀整个 deploy.
        OTHER_STATUS=$(ssh -o ConnectTimeout=5 $SERVER \
            "curl -s -o /dev/null --max-time 5 -w '%{http_code}' http://127.0.0.1:10011/api/mobile/health" \
            2>/dev/null || echo "000")
        if [ "$OTHER_STATUS" != "200" ]; then
            echo ""
            echo "   ⚠️  [防御检查] test 10011 异常 (HTTP $OTHER_STATUS)"
            echo "      test 环境可能宕机或长期未同步"
            echo "      恢复: ssh $SERVER 'cd $REMOTE_JAR_DIR && bash restart.sh test'"
            echo "      或下次用: ./scripts/deploy/deploy-backend.sh --env all"
        else
            echo "   ✓ [防御检查] test 10011 同步运行"
        fi
    elif [[ "$DEPLOY_ENV" == "test" ]]; then
        # 通过 nginx upstream 检查 prod (兼容 Blue-Green 和 in-place)
        # `|| echo "000"` 防 set -e: ssh GATEWAY 失败 → 让 if-check 走异常分支, 不杀 deploy.
        # (Sweep follow-up to #556 — same bug class as the 'exit 28 cosmetic' fix.)
        OTHER_STATUS=$(ssh -o ConnectTimeout=5 $GATEWAY "curl -sk -o /dev/null --max-time 5 -w '%{http_code}' -H 'Host: api.cretaceousfuture.com' https://127.0.0.1/api/mobile/health" 2>/dev/null || echo "000")
        if [ "$OTHER_STATUS" != "200" ]; then
            echo ""
            echo "   ⚠️  [防御检查] prod 异常 (HTTP $OTHER_STATUS via nginx) — 生产可能宕机!"
            echo "      恢复: ssh $SERVER 'systemctl restart cretas-backend'"
        else
            echo "   ✓ [防御检查] prod 同步运行 (via nginx)"
        fi
    fi

    # Phase 1 #19 fix 2026-05-19: post-condition systemctl is-active 守门.
    # 防 deploy 报告成功但 service 实际 inactive 的 silent-kill bug (1.5h prod down 暴露).
    # bluegreen 模式: 至少一个 {cretas-backend, cretas-backend-green} 必须 active.
    # inplace / test:  对应 service 必须 active.
    if [[ "$DEPLOY_ENV" == "prod" || "$DEPLOY_ENV" == "all" ]]; then
        PROD_SVC_STATUS=$(ssh -o ConnectTimeout=10 $SERVER \
            "systemctl is-active cretas-backend cretas-backend-green 2>&1" 2>/dev/null || echo "ssh-fail")
        if ! echo "$PROD_SVC_STATUS" | grep -qx "active"; then
            echo ""
            echo "❌ FATAL [Phase 1 #19 gate]: prod deploy 报告完成但 cretas-backend / cretas-backend-green 都未 active!"
            echo "   systemctl is-active: $PROD_SVC_STATUS"
            echo "   恢复: ssh $SERVER 'systemctl start cretas-backend  # 或 -green'"
            exit 1
        fi
        echo "   ✓ [Phase 1 #19 gate] prod service is-active (blue-green at least one active)"
    fi
    if [[ "$DEPLOY_ENV" == "test" || "$DEPLOY_ENV" == "all" ]]; then
        TEST_SVC_STATUS=$(ssh -o ConnectTimeout=10 $SERVER \
            "systemctl is-active cretas-backend-test 2>&1" 2>/dev/null || echo "ssh-fail")
        if [ "$TEST_SVC_STATUS" != "active" ]; then
            echo ""
            echo "❌ FATAL [Phase 1 #19 gate]: test deploy 报告完成但 cretas-backend-test 未 active (status=$TEST_SVC_STATUS)!"
            echo "   恢复: ssh $SERVER 'systemctl start cretas-backend-test'"
            exit 1
        fi
        echo "   ✓ [Phase 1 #19 gate] cretas-backend-test is-active"
    fi

    echo ""
    echo "=========================================="
    echo "  ✅ 部署完成!"
    echo "  版本: $VERSION"
    echo "  环境: $DEPLOY_ENV"
    echo "  方式: $WINNER"
    echo "  MD5: $LOCAL_MD5"
    echo "  上传耗时: ${UPLOAD_DURATION}s (${SPEED_MBPS} MB/s)"
    [ "$HAS_GH" = "true" ] && echo "  Release: https://github.com/$REPO/releases/tag/$VERSION"
    echo "=========================================="
}

# ==================== Dry-run 模式 ====================
deploy_dry_run() {
    echo "=========================================="
    echo "  Dry-run 模式 — 仅构建验证"
    echo "=========================================="

    export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Java/jdk-17}"
    cd backend/java/cretas-api
    ./mvnw.cmd clean package -Dmaven.test.skip=true -q
    cd ../../..

    JAR_PATH="backend/java/cretas-api/target/$JAR_NAME"
    if [ ! -f "$JAR_PATH" ]; then
        log "ERROR" "JAR 文件不存在: $JAR_PATH"
        exit 1
    fi

    JAR_SIZE=$(get_file_size_human "$JAR_PATH")
    JAR_SIZE_BYTES=$(get_file_size_bytes "$JAR_PATH")
    LOCAL_MD5=$(md5sum "$JAR_PATH" | cut -d' ' -f1)

    log "INFO" "构建成功: $JAR_NAME"
    log "INFO" "大小: $JAR_SIZE ($JAR_SIZE_BYTES bytes)"
    log "INFO" "MD5: $LOCAL_MD5"
    log "INFO" "Dry-run 完成，未执行上传或部署"
}

# ==================== Rollback 模式 ====================
deploy_rollback() {
    echo "=========================================="
    echo "  Rollback 模式 — 恢复上一版本"
    echo "=========================================="

    log "INFO" "查找最新备份..."
    LATEST_BAK=$(ssh $SERVER "ls -t $REMOTE_JAR_DIR/aims-0.0.1-SNAPSHOT.jar.bak.* 2>/dev/null | head -1")

    if [ -z "$LATEST_BAK" ]; then
        log "ERROR" "无可用备份: $REMOTE_JAR_DIR/aims-0.0.1-SNAPSHOT.jar.bak.*"
        exit 1
    fi

    log "INFO" "回滚到: $LATEST_BAK"
    ssh $SERVER "
        cd $REMOTE_JAR_DIR
        cp '$LATEST_BAK' aims-0.0.1-SNAPSHOT.jar
        bash restart.sh $DEPLOY_ENV
    "

    # 2026-05-18: rollback 健康检查也改 SSH localhost (SG 收紧后 public-IP 永远 timeout)
    if [[ "$DEPLOY_ENV" == "prod" || "$DEPLOY_ENV" == "all" ]]; then
        if wait_for_health_via_ssh "$SERVER" 10010 /api/mobile/health 30 2; then
            log "INFO" "回滚完成，生产服务正常"
        else
            log "WARN" "回滚完成但生产健康检查超时，请手动检查"
        fi
    fi
    if [[ "$DEPLOY_ENV" == "test" || "$DEPLOY_ENV" == "all" ]]; then
        if wait_for_health_via_ssh "$SERVER" 10011 /api/mobile/health 30 2; then
            log "INFO" "回滚完成，测试服务正常"
        else
            log "WARN" "回滚完成但测试健康检查超时，请手动检查"
        fi
    fi
}

# ==================== 执行 ====================
case "$MODE" in
    jar)      deploy_jar "$ARG" ;;
    git)      deploy_git "$ARG" ;;
    dry-run)  deploy_dry_run ;;
    rollback) deploy_rollback ;;
esac
