#!/bin/bash
# ============================================================
# Mall 后端部署脚本 v1.0
# 与 deploy-backend.sh 相同的 winner 竞速上传模式
# 用法: ./deploy-mall-backend.sh
# ============================================================
set -e

# ==================== 配置 ====================
SERVER="root@139.196.165.140"
REMOTE_DIR="/www/wwwroot/mall/backend"
REMOTE_TMP="/tmp"
JAR_NAME="logistics-admin.jar"
SERVICE_NAME="mall-backend"
HEALTH_URL="http://localhost:8080/weixin/api/ma/home/init"
LOCAL_PROJECT="MallCenter/mall_admin_center"
JAVA_HOME_PATH="${JAVA_HOME:-C:/Program Files/Java/jdk-17}"

# 检查可用工具
HAS_RSYNC=false
command -v rsync &> /dev/null && HAS_RSYNC=true

# 临时目录
UPLOAD_STATUS_DIR="/tmp/mall-deploy-$$"
mkdir -p "$UPLOAD_STATUS_DIR"

cleanup() {
    rm -rf "$UPLOAD_STATUS_DIR"
    jobs -p | xargs -r kill 2>/dev/null || true
}
trap cleanup EXIT

# 颜色
info()  { echo -e "\033[0;32m[INFO]\033[0m $1"; }
warn()  { echo -e "\033[1;33m[WARN]\033[0m $1"; }
error() { echo -e "\033[0;31m[ERROR]\033[0m $1"; exit 1; }

# 项目根目录
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
# ssh_local_path: Windows 盘符路径会被 rsync/scp 当成主机名, 传输直接失败。
# 注意 PROJECT_ROOT 在本脚本里其实是 scripts/deploy 目录, 所以往上两级取仓库根。
source "$(cd "$(dirname "$0")/../.." && pwd)/scripts/lib/deploy-common.sh"
LOCAL_PROJECT_ABS="${PROJECT_ROOT}/${LOCAL_PROJECT}"

# ==================== Step 1: Maven 打包 ====================
info "Step 1/4: Maven 打包..."
cd "${LOCAL_PROJECT_ABS}"
JAVA_HOME="${JAVA_HOME_PATH}" ./mvnw.cmd package -DskipTests -pl logistics-admin -am -q 2>&1 | tail -5
JAR_PATH="${LOCAL_PROJECT_ABS}/logistics-admin/target/${JAR_NAME}"

if [ ! -f "$JAR_PATH" ]; then
    error "JAR 未生成: $JAR_PATH"
fi

JAR_SIZE=$(du -h "$JAR_PATH" | cut -f1)
LOCAL_MD5=$(md5sum "$JAR_PATH" | cut -d' ' -f1)
info "打包完成: ${JAR_SIZE}, MD5: ${LOCAL_MD5}"

# ==================== Step 2: 并行上传 (Winner 模式) ====================
info "Step 2/4: 并行上传..."

check_winner() {
    [ -f "$UPLOAD_STATUS_DIR/winner" ]
}

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
            echo "   [$METHOD_NAME] OK (MD5 verified)"
        fi
    else
        check_winner || echo "   [$METHOD_NAME] MD5 failed"
    fi
}

# 方法1: SFTP
upload_sftp() {
    check_winner && return 0
    local TMP_FILE="${JAR_NAME}.sftp"
    echo "   [SFTP] uploading..."
    local SFTP_BATCH="$UPLOAD_STATUS_DIR/sftp-batch.txt"
    echo "put \"$JAR_PATH\" \"$REMOTE_TMP/$TMP_FILE\"" > "$SFTP_BATCH"
    if sftp -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 -b "$SFTP_BATCH" $SERVER 2>/dev/null; then
        check_winner || verify_and_claim "$TMP_FILE" "SFTP"
    else
        check_winner || echo "   [SFTP] failed"
    fi
}

# 方法2: SSH 管道
upload_ssh_pipe() {
    check_winner && return 0
    local TMP_FILE="${JAR_NAME}.pipe"
    echo "   [SSH pipe] uploading..."
    if cat "$JAR_PATH" | ssh -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 $SERVER "cat > $REMOTE_TMP/$TMP_FILE" 2>/dev/null; then
        check_winner || verify_and_claim "$TMP_FILE" "SSH pipe"
    else
        check_winner || echo "   [SSH pipe] failed"
    fi
}

# 方法3: rsync
upload_rsync() {
    [ "$HAS_RSYNC" != "true" ] && return 1
    check_winner && return 0
    local TMP_FILE="${JAR_NAME}.rsync"
    echo "   [rsync] uploading..."
    if rsync -az --timeout=120 "$(ssh_local_path "$JAR_PATH")" "$SERVER:$REMOTE_TMP/$TMP_FILE" 2>/dev/null; then
        check_winner || verify_and_claim "$TMP_FILE" "rsync"
    else
        check_winner || echo "   [rsync] failed"
    fi
}

# 方法4: rsync 压缩
upload_rsync_compress() {
    [ "$HAS_RSYNC" != "true" ] && return 1
    check_winner && return 0
    local TMP_FILE="${JAR_NAME}.rsync_z"
    echo "   [rsync+z] uploading..."
    if rsync -az --compress-level=9 --timeout=120 "$(ssh_local_path "$JAR_PATH")" "$SERVER:$REMOTE_TMP/$TMP_FILE" 2>/dev/null; then
        check_winner || verify_and_claim "$TMP_FILE" "rsync+z"
    else
        check_winner || echo "   [rsync+z] failed"
    fi
}

# 方法5: Relay 通过 47 中转 (同区域内网，速度快)
RELAY_SERVER="root@47.100.235.168"
upload_relay() {
    check_winner && return 0
    local TMP_FILE="${JAR_NAME}.relay"
    echo "   [relay via 47] uploading to relay..."
    # Step 1: 上传到 47
    if scp -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=8 "$(ssh_local_path "$JAR_PATH")" "$RELAY_SERVER:/tmp/$TMP_FILE" 2>/dev/null; then
        check_winner && return 0
        echo "   [relay via 47] forwarding to 139..."
        # Step 2: 47 → 139 (内网/同区域)
        if ssh -o ConnectTimeout=10 $RELAY_SERVER "scp -o ConnectTimeout=10 /tmp/$TMP_FILE $SERVER:$REMOTE_TMP/$TMP_FILE && rm -f /tmp/$TMP_FILE" 2>/dev/null; then
            check_winner || verify_and_claim "$TMP_FILE" "relay"
        else
            check_winner || echo "   [relay via 47] forward failed"
        fi
    else
        check_winner || echo "   [relay via 47] upload to relay failed"
    fi
}

# 并行启动所有上传方法
upload_sftp &
upload_ssh_pipe &
upload_rsync &
upload_rsync_compress &
upload_relay &

# 等待任意一个完成
TIMEOUT=600
ELAPSED=0
while ! check_winner && [ $ELAPSED -lt $TIMEOUT ]; do
    sleep 2
    ELAPSED=$((ELAPSED + 2))
    # 检查是否还有上传进程在运行
    if ! jobs -r | grep -q .; then
        break
    fi
done

# 结束其他上传
jobs -p | xargs -r kill 2>/dev/null || true
wait 2>/dev/null || true

if ! check_winner; then
    error "所有上传方式均失败"
fi

WINNER=$(cat "$UPLOAD_STATUS_DIR/winner")
info "上传完成 (winner: $WINNER)"

# ==================== Step 3: 备份 + 替换 ====================
info "Step 3/4: 备份旧 JAR..."
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
ssh $SERVER "
    cd ${REMOTE_DIR}
    if [ -f ${JAR_NAME} ]; then
        cp ${JAR_NAME} ${JAR_NAME}.bak.${TIMESTAMP}
        echo 'backup: ${JAR_NAME}.bak.${TIMESTAMP}'
        # 保留最近3份备份
        ls -t ${JAR_NAME}.bak.* 2>/dev/null | tail -n +4 | xargs -r rm -f
    fi
    mv ${REMOTE_TMP}/${JAR_NAME} ${REMOTE_DIR}/${JAR_NAME}
    echo 'replaced'
"

# ==================== Step 4: 重启 + 健康检查 ====================
info "Step 4/4: 重启 ${SERVICE_NAME}..."
ssh $SERVER "systemctl restart ${SERVICE_NAME}"
info "等待启动..."

MAX_RETRIES=30
RETRY_INTERVAL=3
SUCCESS=false

for i in $(seq 1 $MAX_RETRIES); do
    sleep $RETRY_INTERVAL
    HTTP_CODE=$(ssh $SERVER "curl -s -o /dev/null -w '%{http_code}' ${HEALTH_URL}" 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "200" ]; then
        SUCCESS=true
        break
    fi
    echo "  waiting... ($i/${MAX_RETRIES}) HTTP=$HTTP_CODE"
done

if [ "$SUCCESS" = true ]; then
    info "=========================================="
    info "  Mall 后端部署成功!"
    info "  Upload: $WINNER"
    info "  Service: ${SERVICE_NAME} on port 8080"
    info "  Health: HTTP 200"
    info "=========================================="
else
    error "健康检查失败! 查看日志: ssh ${SERVER} 'journalctl -u ${SERVICE_NAME} --since \"5 min ago\" --no-pager'"
fi
