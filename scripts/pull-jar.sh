#!/bin/bash
# 从 GitHub Release 下载 JAR 并部署
# 用法: bash pull-jar.sh [version]
# 示例: bash pull-jar.sh v20260123_120000
#       bash pull-jar.sh latest

set -e

# 配置
REPO="Stevenjxie/cretas"
JAR_NAME="cretas-backend-system-1.0.0.jar"
DEPLOY_DIR="/www/wwwroot/cretas"
VERSION="${1:-latest}"

# 日志函数
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

log "========== 开始 JAR 部署 (版本: $VERSION) =========="

cd "$DEPLOY_DIR"

# 检查 gh CLI
if ! command -v gh &> /dev/null; then
    log "❌ GitHub CLI 未安装，正在安装..."
    # CentOS/RHEL
    if command -v yum &> /dev/null; then
        curl -fsSL https://cli.github.com/packages/rpm/gh-cli.repo | tee /etc/yum.repos.d/gh-cli.repo
        yum install gh -y
    # Debian/Ubuntu
    elif command -v apt &> /dev/null; then
        curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg
        echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" | tee /etc/apt/sources.list.d/github-cli.list
        apt update && apt install gh -y
    else
        log "❌ 无法自动安装 gh，请手动安装"
        exit 1
    fi
fi

# 检查 gh 登录状态
if ! gh auth status &> /dev/null 2>&1; then
    log "❌ GitHub CLI 未登录，请执行: gh auth login"
    exit 1
fi

# 1. 停止旧服务
log "停止旧服务..."
PID=$(pgrep -f "$JAR_NAME" || true)
if [ -n "$PID" ]; then
    kill "$PID" 2>/dev/null || true
    sleep 3
    # 强制杀死如果还在运行
    if pgrep -f "$JAR_NAME" > /dev/null; then
        kill -9 "$PID" 2>/dev/null || true
        sleep 1
    fi
    log "   旧服务已停止 (PID: $PID)"
else
    log "   没有运行中的服务"
fi

# 2. 备份旧 JAR
if [ -f "$JAR_NAME" ]; then
    BACKUP_NAME="${JAR_NAME}.bak.$(date +%Y%m%d_%H%M%S)"
    log "备份旧 JAR -> $BACKUP_NAME"
    mv "$JAR_NAME" "$BACKUP_NAME"
fi

# 3. 使用已认证的 GitHub CLI 下载（同时支持 public/private repo）
log "从 GitHub Release 下载 JAR (authenticated gh CLI)..."

# 如果是 latest，先获取最新版本号
if [ "$VERSION" = "latest" ]; then
    log "   获取最新 Release 版本..."
    VERSION=$(gh release view --repo "$REPO" --json tagName -q '.tagName' 2>/dev/null || true)
    if [ -z "$VERSION" ]; then
        log "❌ 无法获取最新版本，请指定版本号"
        exit 1
    fi
    log "   最新版本: $VERSION"
fi

if ! gh release download "$VERSION" \
    --repo "$REPO" \
    --pattern "$JAR_NAME" \
    --dir "."; then
    log "❌ JAR 下载失败"
    LATEST_BACKUP=$(ls -t ${JAR_NAME}.bak.* 2>/dev/null | head -1)
    if [ -n "$LATEST_BACKUP" ]; then
        log "恢复备份: $LATEST_BACKUP"
        mv "$LATEST_BACKUP" "$JAR_NAME"
    fi
    exit 1
fi

if [ ! -f "$JAR_NAME" ]; then
    log "❌ JAR 下载失败"
    # 恢复备份
    LATEST_BACKUP=$(ls -t ${JAR_NAME}.bak.* 2>/dev/null | head -1)
    if [ -n "$LATEST_BACKUP" ]; then
        log "恢复备份: $LATEST_BACKUP"
        mv "$LATEST_BACKUP" "$JAR_NAME"
    fi
    exit 1
fi

JAR_SIZE=$(du -h "$JAR_NAME" | cut -f1)
log "   下载完成: $JAR_NAME ($JAR_SIZE)"

# 4. 启动服务
log "启动服务..."
nohup java -jar "$JAR_NAME" \
    -DJWT_SECRET="${JWT_SECRET}" \
    -DALIBABA_ACCESSKEY_ID="${ALIBABA_ACCESSKEY_ID}" \
    -DALIBABA_SECRET_KEY="${ALIBABA_SECRET_KEY}" \
    --server.port=10010 \
    > cretas-backend.log 2>&1 &

NEW_PID=$!
log "   服务启动中 (PID: $NEW_PID)"

# 5. 等待并验证
sleep 8
if pgrep -f "$JAR_NAME" > /dev/null; then
    log "✅ 部署成功！服务已启动"

    # 清理旧备份 (保留最近3个)
    ls -t ${JAR_NAME}.bak.* 2>/dev/null | tail -n +4 | xargs -r rm -f
    log "   已清理旧备份"
else
    log "❌ 部署失败！服务未启动"
    log "   查看日志: tail -100 $DEPLOY_DIR/cretas-backend.log"
    exit 1
fi

log "========== 部署完成 =========="
