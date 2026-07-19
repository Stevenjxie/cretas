#!/bin/bash

# API 集成测试脚本
# 用途: 快速验证后端 API 是否正常工作
# 使用: bash test-integration.sh

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 配置
BACKEND_URL="http://localhost:10010"
FACTORY_ID="CRETAS_2024_001"
ACCESS_TOKEN="${ACCESS_TOKEN:-}"
if [ -z "$ACCESS_TOKEN" ]; then
    echo "ERROR: ACCESS_TOKEN must be supplied by the caller" >&2
    exit 2
fi

# 计数器
TESTS_PASSED=0
TESTS_FAILED=0

# 测试函数
test_endpoint() {
    local TEST_NAME=$1
    local METHOD=$2
    local ENDPOINT=$3
    local DATA=$4
    local EXPECT_CODE=$5

    echo -e "${BLUE}测试: $TEST_NAME${NC}"

    if [ "$METHOD" == "GET" ]; then
        RESPONSE=$(curl -s -w "\n%{http_code}" -X GET "$BACKEND_URL$ENDPOINT" \
            -H "Authorization: Bearer $ACCESS_TOKEN" \
            -H "Content-Type: application/json")
    elif [ "$METHOD" == "POST" ]; then
        RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BACKEND_URL$ENDPOINT" \
            -H "Authorization: Bearer $ACCESS_TOKEN" \
            -H "Content-Type: application/json" \
            -d "$DATA")
    fi

    HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
    BODY=$(echo "$RESPONSE" | head -n-1)

    if [ "$HTTP_CODE" == "$EXPECT_CODE" ]; then
        echo -e "${GREEN}✅ 通过 (HTTP $HTTP_CODE)${NC}"
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        echo -e "${RED}❌ 失败 (期望 $EXPECT_CODE，实际 $HTTP_CODE)${NC}"
        echo "  响应: $BODY"
        TESTS_FAILED=$((TESTS_FAILED + 1))
    fi
    echo ""
}

# 显示标题
echo "================================"
echo -e "${BLUE}🧪 API 集成测试${NC}"
echo "================================"
echo ""
echo -e "${YELLOW}配置:${NC}"
echo "  后端 URL: $BACKEND_URL"
echo "  工厂 ID: $FACTORY_ID"
echo ""

# 检查后端是否运行
echo -e "${BLUE}检查后端服务...${NC}"
if ! curl -s "$BACKEND_URL/api/mobile/health" > /dev/null 2>&1; then
    echo -e "${RED}❌ 错误: 后端服务未运行${NC}"
    echo "  请先运行: mvn spring-boot:run"
    exit 1
fi
echo -e "${GREEN}✅ 后端服务在线${NC}"
echo ""

# ========== 测试用例 ==========

echo -e "${YELLOW}=== 1. 健康检查 ===${NC}"
test_endpoint "后端健康检查" "GET" "/api/mobile/health" "" "200"

echo -e "${YELLOW}=== 2. 成本分析报表 API ===${NC}"
test_endpoint "获取成本分析报表" "GET" "/api/mobile/$FACTORY_ID/reports/cost-analysis?startDate=2024-11-01&endDate=2024-11-30" "" "200"

echo -e "${YELLOW}=== 3. 生产相关 API ===${NC}"
test_endpoint "获取生产批次概览" "GET" "/api/mobile/$FACTORY_ID/processing/dashboard/overview" "" "200"
test_endpoint "获取生产统计" "GET" "/api/mobile/$FACTORY_ID/processing/dashboard/production?period=week" "" "200"

echo -e "${YELLOW}=== 4. 质量相关 API ===${NC}"
test_endpoint "获取质量仪表盘" "GET" "/api/mobile/$FACTORY_ID/processing/dashboard/quality" "" "200"
test_endpoint "获取质检统计" "GET" "/api/mobile/$FACTORY_ID/processing/quality/statistics" "" "200"

echo -e "${YELLOW}=== 5. AI 分析 API ===${NC}"

# 创建 AI 分析请求体
AI_REQUEST_BODY=$(cat <<EOF
{
  "startDate": "2024-11-15",
  "endDate": "2024-11-21",
  "dimension": "overall",
  "question": null
}
EOF
)

test_endpoint "时间范围 AI 分析" "POST" "/api/mobile/$FACTORY_ID/ai/analysis/cost/time-range" "$AI_REQUEST_BODY" "200"

# ========== 测试总结 ==========

echo ""
echo "================================"
echo -e "${BLUE}测试总结${NC}"
echo "================================"
echo -e "${GREEN}通过: $TESTS_PASSED${NC}"
if [ $TESTS_FAILED -gt 0 ]; then
    echo -e "${RED}失败: $TESTS_FAILED${NC}"
else
    echo -e "${GREEN}失败: 0${NC}"
fi

if [ $TESTS_FAILED -eq 0 ]; then
    echo ""
    echo -e "${GREEN}✅ 所有测试通过！${NC}"
    exit 0
else
    echo ""
    echo -e "${RED}❌ 部分测试失败${NC}"
    exit 1
fi
