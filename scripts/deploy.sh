#!/bin/bash
# 宝塔部署脚本 - 从 GitHub 拉取代码并打包部署

set -e

# 配置
DEPLOY_DIR="/www/wwwroot/cretas"
CODE_DIR="/www/wwwroot/cretas/code"
REPO_URL="https://github.com/Stevenjxie/cretas.git"
BRANCH="${1:-steven}"  # 从参数获取分支，默认 steven
JAR_NAME="aims-0.0.1-SNAPSHOT.jar"

# 日志
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

log "========== 开始部署 (分支: $BRANCH) =========="

# 1. 拉取代码
if [ -d "$CODE_DIR/.git" ]; then
    log "更新代码..."
    cd "$CODE_DIR"
    git fetch origin
    git checkout $BRANCH
    git reset --hard origin/$BRANCH
else
    log "首次克隆代码..."
    git clone -b $BRANCH $REPO_URL "$CODE_DIR"
    cd "$CODE_DIR"
fi

# 2. Maven 打包
log "Maven 打包..."
cd "$CODE_DIR/backend/java/cretas-api"
./mvnw clean package -Dmaven.test.skip=true -q

# 3. 停止旧服务 (先 SIGTERM，等待优雅关闭，再 SIGKILL)
log "停止旧服务..."
PID=$(pgrep -f "$JAR_NAME" || true)
if [ -n "$PID" ]; then
    kill "$PID"
    for i in {1..10}; do
        if ! kill -0 "$PID" 2>/dev/null; then
            log "进程已停止"
            break
        fi
        sleep 1
    done
    # 如果还活着，强制终止
    if kill -0 "$PID" 2>/dev/null; then
        log "强制终止进程..."
        kill -9 "$PID" 2>/dev/null || true
        sleep 1
    fi
fi

# 4. 备份旧 JAR (带时间戳，保留最近3份)
if [ -f "$DEPLOY_DIR/$JAR_NAME" ]; then
    BACKUP_NAME="${JAR_NAME}.bak.$(date +%Y%m%d_%H%M%S)"
    log "备份旧 JAR: $BACKUP_NAME"
    cp "$DEPLOY_DIR/$JAR_NAME" "$DEPLOY_DIR/$BACKUP_NAME"
    ls -t "$DEPLOY_DIR/${JAR_NAME}.bak."* 2>/dev/null | tail -n +4 | xargs -r rm -f 2>/dev/null || true
fi

# 5. 复制新 JAR
log "部署新 JAR..."
cp "$CODE_DIR/backend/java/cretas-api/target/$JAR_NAME" "$DEPLOY_DIR/"

# 6. 启动服务
log "启动服务..."
cd "$DEPLOY_DIR"
nohup java -jar $JAR_NAME \
    -DJWT_SECRET=${JWT_SECRET} \
    -DALIBABA_ACCESSKEY_ID=${ALIBABA_ACCESSKEY_ID} \
    -DALIBABA_SECRET_KEY=${ALIBABA_SECRET_KEY} \
    --server.port=10010 \
    > app.log 2>&1 &

# 7. 清理构建产物
log "清理构建产物..."
rm -rf "$CODE_DIR/backend/java/cretas-api/target"

# 8. 等待启动 (最多60秒，每2秒检查一次)
log "等待服务启动..."
for i in {1..30}; do
    if curl -s http://localhost:10010/api/mobile/health > /dev/null 2>&1; then
        log "✅ 部署成功！服务已启动 (${i}x2s)"
        exit 0
    fi
    echo "  等待服务启动... ($i/30)"
    sleep 2
done

log "❌ 部署失败！服务启动超时 (60s)"
log "查看日志: tail -100 $DEPLOY_DIR/app.log"
exit 1

log "========== 部署完成 =========="
