#!/bin/bash
# Deploy fine-tuned embedding model to server
# v13.0: AI Intent Recognition Optimization
# 支持多种上传方式并行，取最快的

set -e

# 加载共享函数库
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/scripts/lib/deploy-common.sh" ]; then
    source "$SCRIPT_DIR/scripts/lib/deploy-common.sh"
else
    log() { echo "[$(date '+%Y-%m-%dT%H:%M:%S')] [$1] ${*:2}"; }
fi

SERVER="root@47.100.235.168"
REMOTE_MODEL_DIR="/www/wwwroot/cretas/models"
MODEL_NAME="gte-base-zh-finetuned-contrastive"
LOCAL_MODEL_DIR="scripts/finetune/models/$MODEL_NAME"
REPO="Stevenjxie/cretas"
VERSION="model-$(date +%Y%m%d_%H%M%S)"

IS_PRIVATE_REPO=false
if command -v gh &> /dev/null; then
    REPO_PRIVATE=$(gh api "repos/$REPO" --jq '.private' 2>/dev/null || echo "unknown")
    [ "$REPO_PRIVATE" = "true" ] && IS_PRIVATE_REPO=true
fi

# 临时文件用于追踪哪个方法先完成
UPLOAD_STATUS_DIR="/tmp/embedding-upload-$$"
mkdir -p "$UPLOAD_STATUS_DIR"

echo "=== Deploying Fine-tuned Embedding Model ==="
echo "Model: $MODEL_NAME"
echo "Version: $VERSION"
echo ""

# 检查本地模型是否存在
if [ ! -d "$LOCAL_MODEL_DIR" ]; then
    echo "ERROR: Fine-tuned model not found at $LOCAL_MODEL_DIR"
    exit 1
fi

# 创建远程目录
echo "1. 准备远程目录..."
ssh $SERVER "mkdir -p $REMOTE_MODEL_DIR/$MODEL_NAME"

# 先压缩模型文件
echo "2. 压缩模型文件..."
MODEL_TAR="$MODEL_NAME.tar.gz"
tar -czf "/tmp/$MODEL_TAR" -C "scripts/finetune/models" "$MODEL_NAME"
TAR_SIZE=$(get_file_size_human "/tmp/$MODEL_TAR")
log "INFO" "压缩完成: $TAR_SIZE"

echo ""
echo "3. 并行上传 (使用多种方式，取最快)..."
echo "   启动上传进程..."

# 清理函数
cleanup() {
    rm -rf "$UPLOAD_STATUS_DIR"
    rm -f "/tmp/$MODEL_TAR"
    # 杀死所有子进程
    jobs -p | xargs -r kill 2>/dev/null || true
}
trap cleanup EXIT

# 方法1: 直接 SCP 上传
upload_scp() {
    echo "   [SCP] 开始直接上传..."
    if scp "/tmp/$MODEL_TAR" "$SERVER:/tmp/$MODEL_TAR" 2>/dev/null; then
        echo "SCP" > "$UPLOAD_STATUS_DIR/winner"
        echo "   [SCP] ✓ 上传完成!"
    else
        echo "   [SCP] ✗ 失败"
    fi
}

# 方法2: GitHub Release + 镜像下载
upload_github_release() {
    echo "   [GitHub] 开始上传到 Release..."

    if [ "$IS_PRIVATE_REPO" = "true" ]; then
        echo "   [GitHub] 跳过: private repo 的匿名镜像无法下载 Release asset"
        return 1
    fi

    # 检查 gh CLI
    if ! command -v gh &> /dev/null; then
        echo "   [GitHub] ✗ gh CLI 不可用"
        return 1
    fi

    # 删除已存在的同名 release
    gh release delete "$VERSION" --repo "$REPO" -y 2>/dev/null || true

    # 创建 Release 并上传
    if gh release create "$VERSION" \
        "/tmp/$MODEL_TAR" \
        --repo "$REPO" \
        --title "Embedding Model $VERSION" \
        --notes "Fine-tuned embedding model for AI intent recognition" 2>/dev/null; then

        echo "   [GitHub] Release 创建成功，通知服务器下载..."

        # 服务器通过镜像下载
        DOWNLOAD_URL="https://ghproxy.cc/https://github.com/$REPO/releases/download/$VERSION/$MODEL_TAR"

        if ssh $SERVER "cd /tmp && curl -L -o $MODEL_TAR '$DOWNLOAD_URL' 2>/dev/null"; then
            echo "GITHUB" > "$UPLOAD_STATUS_DIR/winner"
            echo "   [GitHub] ✓ 下载完成!"
        else
            echo "   [GitHub] ✗ 服务器下载失败"
        fi
    else
        echo "   [GitHub] ✗ Release 创建失败"
    fi
}

# 方法3: rsync with compression (备选)
upload_rsync() {
    echo "   [Rsync] 开始上传..."
    if rsync -avz --progress "/tmp/$MODEL_TAR" "$SERVER:/tmp/$MODEL_TAR" 2>/dev/null; then
        echo "RSYNC" > "$UPLOAD_STATUS_DIR/winner"
        echo "   [Rsync] ✓ 上传完成!"
    else
        echo "   [Rsync] ✗ 失败"
    fi
}

# 启动所有上传方法（并行）
upload_scp &
PID_SCP=$!

upload_github_release &
PID_GITHUB=$!

upload_rsync &
PID_RSYNC=$!

# 等待第一个完成
echo ""
echo "   等待上传完成..."
WINNER=""
TIMEOUT=600  # 10分钟超时
ELAPSED=0

while [ -z "$WINNER" ] && [ $ELAPSED -lt $TIMEOUT ]; do
    if [ -f "$UPLOAD_STATUS_DIR/winner" ]; then
        WINNER=$(cat "$UPLOAD_STATUS_DIR/winner")
        break
    fi

    # 检查是否所有进程都已结束
    if ! kill -0 $PID_SCP 2>/dev/null && \
       ! kill -0 $PID_GITHUB 2>/dev/null && \
       ! kill -0 $PID_RSYNC 2>/dev/null; then
        if [ -f "$UPLOAD_STATUS_DIR/winner" ]; then
            WINNER=$(cat "$UPLOAD_STATUS_DIR/winner")
        fi
        break
    fi

    sleep 2
    ELAPSED=$((ELAPSED + 2))
done

# 杀死其他进程
kill $PID_SCP $PID_GITHUB $PID_RSYNC 2>/dev/null || true
wait 2>/dev/null || true

if [ -z "$WINNER" ]; then
    echo ""
    echo "❌ 所有上传方式都失败或超时"
    exit 1
fi

echo ""
echo "   🏆 胜出方式: $WINNER"

# 4. 在服务器解压
echo ""
echo "4. 在服务器解压模型..."
ssh $SERVER "
    cd /tmp
    if [ -f $MODEL_TAR ]; then
        # 备份旧模型
        if [ -d $REMOTE_MODEL_DIR/$MODEL_NAME ]; then
            rm -rf $REMOTE_MODEL_DIR/${MODEL_NAME}.bak 2>/dev/null || true
            mv $REMOTE_MODEL_DIR/$MODEL_NAME $REMOTE_MODEL_DIR/${MODEL_NAME}.bak
        fi

        # 解压新模型
        tar -xzf $MODEL_TAR -C $REMOTE_MODEL_DIR/
        rm -f $MODEL_TAR

        echo '解压完成'
        ls -la $REMOTE_MODEL_DIR/$MODEL_NAME/
    else
        echo 'ERROR: 压缩包不存在'
        exit 1
    fi
"

# 5. 重启 embedding service
echo ""
echo "5. 重启 embedding service..."
ssh $SERVER "
    # 停止旧进程
    pkill -f 'embedding-service.*jar' || true
    sleep 2

    # 启动新进程
    if [ -f /www/wwwroot/cretas/embedding-service/start.sh ]; then
        cd /www/wwwroot/cretas/embedding-service && bash start.sh
    else
        echo 'WARNING: start.sh not found'
    fi
"

# 6. 验证
echo ""
echo "6. 验证服务状态..."
sleep 3
ssh $SERVER "
    if pgrep -f 'embedding-service.*jar' > /dev/null; then
        echo '✓ Embedding service 正在运行'
    else
        echo '⚠ Service 可能未启动，请手动检查'
    fi
"

echo ""
echo "=========================================="
echo "  ✅ 模型部署完成!"
echo "  上传方式: $WINNER"
echo "  模型路径: $REMOTE_MODEL_DIR/$MODEL_NAME"
echo "=========================================="
echo ""
echo "下一步: 部署后端代码"
echo "  ./deploy-backend.sh"
