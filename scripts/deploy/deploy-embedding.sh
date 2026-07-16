#!/bin/bash
# Embedding Service 部署脚本
# 本地打包 -> SSH 直传 -> 服务器部署
#
# 用法: ./deploy-embedding.sh [version]
# 示例: ./deploy-embedding.sh              # 自动生成版本号
#       ./deploy-embedding.sh v1.0.0       # 指定版本号

set -e

# 配置
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
EMBEDDING_DIR="$PROJECT_ROOT/backend/java/embedding-service"
JAR_NAME="embedding-service-1.0.0.jar"
VERSION="${1:-embedding-v$(date +%Y%m%d_%H%M%S)}"
SERVER="root@47.100.235.168"
SERVER_DIR="/www/wwwroot/cretas/embedding-service"
SERVICE_NAME="cretas-embedding"

# Windows 环境设置 PATH
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" || "$OSTYPE" == "win32" ]]; then
    export PATH="$PATH:/c/Program Files/GitHub CLI:/c/tools/apache-maven-3.9.6/bin"
fi

echo "=========================================="
echo "  Embedding Service 部署 - 版本: $VERSION"
echo "=========================================="

# 1. 本地 Maven 打包
echo ""
echo "[1/4] 本地 Maven 打包..."
cd "$EMBEDDING_DIR"
mvn clean package -DskipTests -q

JAR_PATH="$EMBEDDING_DIR/target/$JAR_NAME"
if [ ! -f "$JAR_PATH" ]; then
    echo "Error: JAR 文件不存在: $JAR_PATH"
    exit 1
fi

JAR_SIZE=$(du -h "$JAR_PATH" | cut -f1)
echo "   Done: $JAR_NAME ($JAR_SIZE)"

# 2. 通过 SSH 直传 JAR（private repo 无需在服务器保存 GitHub Token）
echo ""
echo "[2/4] 通过 SSH 上传 JAR..."
REMOTE_INCOMING="$SERVER_DIR/$JAR_NAME.incoming.$VERSION"
ssh "$SERVER" "mkdir -p '$SERVER_DIR'"
if command -v rsync &> /dev/null \
    && rsync --version &> /dev/null \
    && rsync -az --progress "$JAR_PATH" "$SERVER:$REMOTE_INCOMING"; then
    TRANSFER_METHOD="rsync"
else
    echo "   rsync 不可用或上传失败，切换 scp..."
    scp "$JAR_PATH" "$SERVER:$REMOTE_INCOMING"
    TRANSFER_METHOD="scp"
fi
echo "   Done: $TRANSFER_METHOD 上传完成"

# 3. 服务器部署
echo ""
echo "[3/4] 服务器部署..."

ssh $SERVER << EOF
set -e

cd $SERVER_DIR

# 备份旧 JAR
if [ -f "$JAR_NAME" ]; then
    cp "$JAR_NAME" "$JAR_NAME.bak.\$(date +%Y%m%d_%H%M%S)"
    # 保留最近 3 个备份
    ls -t $JAR_NAME.bak.* 2>/dev/null | tail -n +4 | xargs -r rm -f
fi

# 原子提升已上传的候选 JAR
mv "$REMOTE_INCOMING" "$JAR_NAME"

echo "   Restarting $SERVICE_NAME via systemd..."
systemctl restart "$SERVICE_NAME"
EOF

# 4. 验证部署
echo ""
echo "[4/4] 验证部署..."
sleep 5
if ssh "$SERVER" "systemctl is-active --quiet '$SERVICE_NAME' && ss -ltn | grep -q ':9090 '"; then
    echo "   Done: $SERVICE_NAME active，9090 正在监听"
else
    echo "   Error: $SERVICE_NAME 未正常运行或 9090 未监听"
    echo "   ssh $SERVER 'systemctl status $SERVICE_NAME --no-pager; journalctl -u $SERVICE_NAME -n 80 --no-pager'"
    exit 1
fi

echo ""
echo "=========================================="
echo "  Done! 部署完成"
echo "  版本: $VERSION"
echo "  传输: $TRANSFER_METHOD (SSH)"
echo "=========================================="
echo ""
echo "管理命令:"
echo "  查看日志: ssh $SERVER 'journalctl -u $SERVICE_NAME -f'"
echo "  状态: ssh $SERVER 'systemctl status $SERVICE_NAME --no-pager'"
