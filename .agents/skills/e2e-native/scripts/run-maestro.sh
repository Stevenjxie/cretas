#!/bin/bash
set -e

# run-maestro.sh — Maestro 测试执行封装
#
# 用法:
#   bash run-maestro.sh <role> [--apk <path>] [--device <id>]
#
# 示例:
#   bash run-maestro.sh factory_admin
#   bash run-maestro.sh factory_admin --apk ./CretasFoodTrace.apk
#   bash run-maestro.sh all

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
MAESTRO_DIR="$PROJECT_ROOT/tests/e2e-native/.maestro"
SCREENSHOT_DIR="$PROJECT_ROOT/tests/e2e-native/screenshots"
REPORT_DIR="$PROJECT_ROOT/tests/e2e-native/reports"
APP_ID="com.cretas.foodtrace"
FLOW_TIMEOUT=60000
TOTAL_TIMEOUT=600000

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 解析参数
ROLE=""
APK_PATH=""
DEVICE_ID=""
ERRORS=0

while [[ $# -gt 0 ]]; do
    case $1 in
        --apk)
            APK_PATH="$2"
            shift 2
            ;;
        --device)
            DEVICE_ID="$2"
            shift 2
            ;;
        *)
            ROLE="$1"
            shift
            ;;
    esac
done

if [ -z "$ROLE" ]; then
    echo -e "${RED}Error: 请指定角色（如 factory_admin）或 all${NC}"
    echo "用法: bash run-maestro.sh <role> [--apk <path>] [--device <id>]"
    echo ""
    echo "可用角色:"
    echo "  factory_admin    工厂超级管理员"
    echo "  platform_admin   平台管理员"
    echo "  workshop_sup     车间主管"
    echo "  warehouse_mgr    仓储主管"
    echo "  hr_admin         HR 管理员"
    echo "  dispatcher       调度员"
    echo "  quality_insp     质检员"
    echo "  all              所有角色"
    exit 1
fi

echo "================================================"
echo "  Maestro E2E 测试执行"
echo "  角色: $ROLE"
echo "  时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "================================================"
echo ""

# Phase 0: 环境检查
echo -e "${YELLOW}[Phase 0] 环境检查...${NC}"

# 检查 Maestro CLI
if ! command -v maestro &> /dev/null; then
    echo -e "${RED}Error: Maestro CLI 未安装${NC}"
    echo "安装命令: curl -Ls \"https://get.maestro.mobile.dev\" | bash"
    exit 1
fi
MAESTRO_VERSION=$(maestro --version 2>/dev/null || echo "unknown")
echo "  Maestro CLI: $MAESTRO_VERSION"

# 检查设备
if command -v adb &> /dev/null; then
    DEVICE_COUNT=$(adb devices | grep -c "device$" || true)
    if [ "$DEVICE_COUNT" -eq 0 ]; then
        echo -e "${RED}Error: 未检测到 Android 设备/模拟器${NC}"
        echo "请启动 Android 模拟器或连接真机"
        exit 1
    fi
    echo "  Android 设备: $DEVICE_COUNT 个"

    # 检查 APK 安装
    if ! adb shell pm list packages 2>/dev/null | grep -q "$APP_ID"; then
        if [ -n "$APK_PATH" ]; then
            echo "  安装 APK: $APK_PATH"
            adb install -r "$APK_PATH"
        else
            echo -e "${RED}Error: 应用未安装 ($APP_ID)${NC}"
            echo "安装方式:"
            echo "  1. adb install <apk-path>"
            echo "  2. eas build --profile preview --platform android"
            echo "  3. npx expo run:android"
            exit 1
        fi
    else
        echo "  应用已安装: $APP_ID"
    fi
elif command -v xcrun &> /dev/null; then
    BOOTED=$(xcrun simctl list devices booted 2>/dev/null | grep -c "Booted" || true)
    if [ "$BOOTED" -eq 0 ]; then
        echo -e "${RED}Error: 未检测到 iOS 模拟器${NC}"
        echo "请启动 iOS 模拟器"
        exit 1
    fi
    echo "  iOS 模拟器: $BOOTED 个"
else
    echo -e "${RED}Error: 未找到 adb 或 xcrun${NC}"
    exit 1
fi

# 检查 flows 目录
if [ ! -d "$MAESTRO_DIR/flows" ]; then
    echo -e "${RED}Error: Maestro flows 目录不存在: $MAESTRO_DIR/flows/${NC}"
    echo "请先运行: /e2e-native gen $ROLE"
    exit 1
fi

# 创建输出目录
mkdir -p "$SCREENSHOT_DIR" "$REPORT_DIR"

echo -e "${GREEN}  环境检查通过 ✓${NC}"
echo ""

# Phase 1: 执行测试
echo -e "${YELLOW}[Phase 1] 执行 Maestro 测试...${NC}"

TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
REPORT_FILE="$REPORT_DIR/report_${ROLE}_${TIMESTAMP}.json"
TOTAL_FLOWS=0
PASSED_FLOWS=0
FAILED_FLOWS=0
RESULTS=()

run_flows() {
    local role_dir="$1"
    local flow_dir="$MAESTRO_DIR/flows/$role_dir"

    if [ ! -d "$flow_dir" ]; then
        echo -e "${YELLOW}  跳过: $flow_dir 不存在${NC}"
        return
    fi

    for flow_file in "$flow_dir"/*.yaml; do
        [ -f "$flow_file" ] || continue

        local flow_name=$(basename "$flow_file")
        # 跳过以下划线开头的子流
        [[ "$flow_name" == _* ]] && continue

        TOTAL_FLOWS=$((TOTAL_FLOWS + 1))
        echo -n "  运行 $role_dir/$flow_name ... "

        local start_time=$(date +%s)

        # 构建 Maestro 命令
        local maestro_cmd="maestro test"
        [ -n "$DEVICE_ID" ] && maestro_cmd="$maestro_cmd --device $DEVICE_ID"
        maestro_cmd="$maestro_cmd --format junit"
        maestro_cmd="$maestro_cmd --output $REPORT_DIR/${role_dir}_${flow_name%.yaml}.xml"
        maestro_cmd="$maestro_cmd $flow_file"

        if timeout $((FLOW_TIMEOUT / 1000)) bash -c "$maestro_cmd" > /dev/null 2>&1; then
            local end_time=$(date +%s)
            local duration=$((end_time - start_time))
            echo -e "${GREEN}PASS ✅ (${duration}s)${NC}"
            PASSED_FLOWS=$((PASSED_FLOWS + 1))
            RESULTS+=("{\"flow\":\"$role_dir/$flow_name\",\"status\":\"pass\",\"duration\":$duration}")
        else
            local end_time=$(date +%s)
            local duration=$((end_time - start_time))
            echo -e "${RED}FAIL ❌ (${duration}s)${NC}"
            FAILED_FLOWS=$((FAILED_FLOWS + 1))
            RESULTS+=("{\"flow\":\"$role_dir/$flow_name\",\"status\":\"fail\",\"duration\":$duration}")

            # 截图保存
            if command -v adb &> /dev/null; then
                local screenshot_name="${role_dir}_${flow_name%.yaml}_${TIMESTAMP}.png"
                adb exec-out screencap -p > "$SCREENSHOT_DIR/$screenshot_name" 2>/dev/null || true
                echo "    截图: $SCREENSHOT_DIR/$screenshot_name"
            fi
        fi
    done
}

if [ "$ROLE" = "all" ]; then
    for role_dir in "$MAESTRO_DIR/flows"/*/; do
        [ -d "$role_dir" ] || continue
        local dir_name=$(basename "$role_dir")
        # 跳过 common 目录
        [ "$dir_name" = "common" ] && continue
        run_flows "$dir_name"
    done
else
    # 角色名映射到目录名
    case "$ROLE" in
        factory_admin)  run_flows "factory-admin" ;;
        platform_admin) run_flows "platform-admin" ;;
        workshop_sup)   run_flows "workshop" ;;
        warehouse_mgr)  run_flows "warehouse" ;;
        hr_admin)       run_flows "hr" ;;
        dispatcher)     run_flows "dispatcher" ;;
        quality_insp)   run_flows "quality-inspector" ;;
        *)              run_flows "$ROLE" ;;
    esac
fi

echo ""

# Phase 2: 结果汇总
echo -e "${YELLOW}[Phase 2] 结果汇总${NC}"
echo "================================================"
echo "  总流程: $TOTAL_FLOWS"
echo -e "  通过:   ${GREEN}$PASSED_FLOWS${NC}"
echo -e "  失败:   ${RED}$FAILED_FLOWS${NC}"
echo "  截图:   $SCREENSHOT_DIR/"
echo "  报告:   $REPORT_DIR/"
echo "================================================"

# 生成 JSON 报告
RESULTS_JSON=$(printf '%s,' "${RESULTS[@]}" | sed 's/,$//')
cat > "$REPORT_FILE" << EOF
{
  "timestamp": "$TIMESTAMP",
  "role": "$ROLE",
  "device": "$(adb devices 2>/dev/null | grep 'device$' | head -1 | awk '{print $1}' || echo 'unknown')",
  "maestro_version": "$MAESTRO_VERSION",
  "total": $TOTAL_FLOWS,
  "passed": $PASSED_FLOWS,
  "failed": $FAILED_FLOWS,
  "results": [$RESULTS_JSON]
}
EOF

echo ""
echo "JSON 报告: $REPORT_FILE"

# 退出码
if [ "$FAILED_FLOWS" -gt 0 ]; then
    exit 1
fi
exit 0
