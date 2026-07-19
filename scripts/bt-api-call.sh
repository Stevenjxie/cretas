#!/bin/bash
# 宝塔API调用脚本
# 使用方法: ./bt-api-call.sh <action> [参数]
# 示例: ./bt-api-call.sh GetSystemTotal
#       ./bt-api-call.sh GetDir path=/www/wwwroot

# Configuration: the API key must come from a root-only environment file or
# the caller environment. Never embed it in a tracked script.
BT_PANEL_URL="${BT_PANEL_URL:-https://139.196.165.140:16435}"
: "${BT_API_KEY:?Set BT_API_KEY from a root-only credential source}"
export BT_API_KEY

# Generate a short-lived request signature.
generate_token() {
    python3 << 'PYTHON_EOF'
import hashlib
import os
import time

api_sk = os.environ["BT_API_KEY"]
request_time = str(int(time.time()))
md5_api_sk = hashlib.md5(api_sk.encode()).hexdigest()
request_token = hashlib.md5((request_time + md5_api_sk).encode()).hexdigest()
print(f"{request_time}|{request_token}")
PYTHON_EOF
}
# 调用API
call_api() {
    local action=$1
    local module=${2:-system}
    shift 2 || true
    
    # 生成签名
    TIME_TOKEN=$(generate_token)
    REQUEST_TIME=$(echo $TIME_TOKEN | cut -d'|' -f1)
    REQUEST_TOKEN=$(echo $TIME_TOKEN | cut -d'|' -f2)
    
    # 构建URL
    URL="${BT_PANEL_URL}/${module}?action=${action}"
    
    # 构建POST数据
    POST_DATA="request_time=${REQUEST_TIME}&request_token=${REQUEST_TOKEN}"
    
    # 添加额外参数
    for arg in "$@"; do
        POST_DATA="${POST_DATA}&${arg}"
    done
    
    # 调用API
    echo "调用API: ${URL}"
    echo "Request signature: generated (value redacted)"
    echo "---"
    
    curl -k -X POST "${URL}" \
        -d "${POST_DATA}" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        --connect-timeout 10 \
        --max-time 30
    
    echo ""
}

# 主函数
if [ $# -eq 0 ]; then
    echo "使用方法: $0 <action> [module] [参数...]"
    echo ""
    echo "常用操作:"
    echo "  $0 GetSystemTotal                    # 获取系统信息"
    echo "  $0 GetDiskInfo                       # 获取磁盘信息"
    echo "  $0 GetDir files path=/www/wwwroot    # 获取目录列表"
    echo "  $0 GetFileBody files path=/path/to/file  # 读取文件"
    echo ""
    echo "模块说明:"
    echo "  system  - 系统相关 (默认)"
    echo "  files   - 文件管理"
    echo "  site    - 网站管理"
    echo "  data    - 数据查询"
    exit 1
fi

ACTION=$1
MODULE=${2:-system}
shift 2 || true

call_api "$ACTION" "$MODULE" "$@"
