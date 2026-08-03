#!/bin/bash

# Local development build helper only. Production releases must use
# scripts/deploy/release-cretas.sh so tests and the trusted manifest stay bound
# to the deployed artifact.
set -e

cd "$(dirname "$0")"

echo "========================================"
echo "  Cretas Backend - Local Build"
echo "========================================"

if [ -x ./mvnw ]; then
    MVN=./mvnw
elif [ -f ./mvnw.cmd ]; then
    MVN=./mvnw.cmd
else
    echo "❌ Maven wrapper 不存在"
    exit 1
fi

"$MVN" clean package -DskipTests

JAR_FILE="target/cretas-backend-system-1.0.0.jar"
[ -f "$JAR_FILE" ] || { echo "❌ JAR 文件生成失败: $JAR_FILE"; exit 1; }

echo "✅ 本地构建完成: $JAR_FILE"
echo "ℹ️  该文件不是可信生产制品；生产发布请从仓库根目录运行 scripts/deploy/release-cretas.sh。"
