#!/bin/bash
# Embedding Service 部署脚本
# 本地打包 -> GitHub Release -> 服务器拉取
#
# 用法: ./deploy-embedding.sh [version]
# 示例: ./deploy-embedding.sh              # 自动生成版本号
#       ./deploy-embedding.sh v1.0.0       # 指定版本号

set -e

# 配置
REPO="Stevenjxie/cretas"
JAR_NAME="embedding-service-1.0.0.jar"
VERSION="${1:-embedding-v$(date +%Y%m%d_%H%M%S)}"
SERVER="root@47.100.235.168"
SERVER_DIR="/www/wwwroot/embedding-service"

# Windows 环境设置 PATH
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" || "$OSTYPE" == "win32" ]]; then
    export PATH="$PATH:/c/Program Files/GitHub CLI:/c/tools/apache-maven-3.9.6/bin"
fi

echo "=========================================="
echo "  Embedding Service 部署 - 版本: $VERSION"
echo "=========================================="

# 检查 gh CLI
if ! command -v gh &> /dev/null; then
    echo "Error: 需要安装 GitHub CLI (gh)"
    echo "   Windows: winget install GitHub.cli"
    echo "   Mac: brew install gh"
    exit 1
fi

# 检查登录状态
if ! gh auth status &> /dev/null; then
    echo "Error: 请先登录 GitHub CLI: gh auth login"
    exit 1
fi

# 1. 本地 Maven 打包
echo ""
echo "[1/4] 本地 Maven 打包..."
cd embedding-service
mvn clean package -DskipTests -q
cd ..

JAR_PATH="embedding-service/target/$JAR_NAME"
if [ ! -f "$JAR_PATH" ]; then
    echo "Error: JAR 文件不存在: $JAR_PATH"
    exit 1
fi

JAR_SIZE=$(du -h "$JAR_PATH" | cut -f1)
echo "   Done: $JAR_NAME ($JAR_SIZE)"

# 2. 创建 GitHub Release 并上传 JAR
echo ""
echo "[2/4] 上传到 GitHub Release..."

# 删除已存在的同名 release (如果有)
gh release delete "$VERSION" --repo "$REPO" -y 2>/dev/null || true

gh release create "$VERSION" \
    "$JAR_PATH" \
    --repo "$REPO" \
    --title "Embedding Service $VERSION" \
    --notes "Embedding service release from deploy-embedding.sh at $(date '+%Y-%m-%d %H:%M:%S')"

echo "   Done: Release 创建成功"

# 3. 服务器部署
echo ""
echo "[3/4] 服务器部署..."

ssh $SERVER << EOF
set -e

# 创建目录
mkdir -p $SERVER_DIR
cd $SERVER_DIR

# 备份旧 JAR
if [ -f "$JAR_NAME" ]; then
    mv "$JAR_NAME" "$JAR_NAME.bak.\$(date +%Y%m%d_%H%M%S)"
    # 保留最近 3 个备份
    ls -t $JAR_NAME.bak.* 2>/dev/null | tail -n +4 | xargs -r rm -f
fi

# 下载新 JAR (使用 GitHub 镜像加速)
echo "   Downloading JAR via ghproxy.cc mirror..."
DOWNLOAD_URL="https://ghproxy.cc/https://github.com/$REPO/releases/download/$VERSION/$JAR_NAME"
wget -q --show-progress -O "$JAR_NAME" "\$DOWNLOAD_URL"

# 停止旧服务
PID=\$(pgrep -f "embedding-service.*jar" || true)
if [ -n "\$PID" ]; then
    echo "   Stopping old service (PID: \$PID)..."
    kill \$PID
    sleep 2
fi

# 启动新服务
echo "   Starting new service..."
nohup java -jar -Xmx512M "$JAR_NAME" \
    --grpc.server.port=9090 \
    --embedding.model-path=/www/wwwroot/cretas/models/gte-base-zh \
    > embedding-service.log 2>&1 &

NEW_PID=\$!
echo "   Service started (PID: \$NEW_PID)"
EOF

# 4. 验证部署
echo ""
echo "[4/4] 验证部署..."
sleep 3

# 检查进程是否存在
RUNNING=$(ssh $SERVER "pgrep -f 'embedding-service.*jar' || echo ''")
if [ -n "$RUNNING" ]; then
    echo "   Done: 服务正在运行 (PID: $RUNNING)"
else
    echo "   Warning: 服务可能未启动成功，请检查日志"
    echo "   ssh $SERVER 'tail -50 $SERVER_DIR/embedding-service.log'"
fi

echo ""
echo "=========================================="
echo "  Done! 部署完成"
echo "  版本: $VERSION"
echo "  Release: https://github.com/$REPO/releases/tag/$VERSION"
echo "=========================================="
echo ""
echo "管理命令:"
echo "  查看日志: ssh $SERVER 'tail -f $SERVER_DIR/embedding-service.log'"
echo "  停止服务: ssh $SERVER 'pkill -f embedding-service.*jar'"
echo "  启动服务: ssh $SERVER 'cd $SERVER_DIR && bash start.sh'"
