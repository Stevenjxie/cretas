#!/bin/bash
# build-apk.sh - 构建 Android APK
# 支持 Debug 和 Release 两种模式
# 用法: ./build-apk.sh [项目路径] [release|debug] [--clean]

set -e

PROJECT_ROOT="${1:-/Users/jietaoxie/my-prototype-logistics/frontend/CretasFoodTrace}"
BUILD_TYPE="${2:-release}"  # debug 或 release
DO_CLEAN="${3:-}"  # --clean 表示清理构建

echo "================================================"
echo "  构建 Android APK"
echo "  模式: $BUILD_TYPE"
echo "================================================"
echo ""

cd "$PROJECT_ROOT"

# 设置 Java 环境
if [ -d "/Library/Java/JavaVirtualMachines/jdk-17.0.1.jdk/Contents/Home" ]; then
    export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-17.0.1.jdk/Contents/Home"
elif [ -d "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home" ]; then
    export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
fi
echo "JAVA_HOME: $JAVA_HOME"

# 设置 Android SDK
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools"
echo "ANDROID_HOME: $ANDROID_HOME"
echo ""

# 1. 按 lockfile 全量安装依赖
# 🔴 2026-08-17: 这里曾经是「node_modules 存在就跳过」+ `npm install` ——
# 打包机上留着的旧依赖被直接拿去打包, 打出的 v1.0.4 APK 一启动就崩(任何设备):
#   NoSuchMethodError: getDirectConverter(...) in ReturnTypeKt
#   at expo.modules.font.FontLoaderModule.definition(FontLoaderModule.kt:98)
# 而 lockfile 一直锁着好版本 ⇒ 那个包不是按锁装出来的。
# ⇒ 一律 npm ci(先清 node_modules, 再严格按锁装)。⛔ 不要改回 npm install / 不要加跳过分支。
# ⚠️ 必须带 --legacy-peer-deps —— 裸 npm ci 在本仓会因 peer-dep 解析差异直接失败(实测)。
echo "按 lockfile 全量安装依赖 (npm ci --legacy-peer-deps)..."
npm ci --legacy-peer-deps

# 2. 执行 Expo prebuild (如果需要)
if [ ! -d "android" ]; then
    echo "执行 Expo prebuild..."
    npx expo prebuild --platform android
fi

# 3. 进入 android 目录构建
cd android

# 4. 可选：清理之前的构建
if [ "$DO_CLEAN" = "--clean" ]; then
    echo "清理之前的构建..."
    ./gradlew clean --quiet
else
    echo "跳过清理（增量构建，更快）"
fi

# 5. 执行构建
if [ "$BUILD_TYPE" = "release" ]; then
    echo "构建 Release APK..."
    ./gradlew assembleRelease --no-daemon --console=plain

    APK_PATH="app/build/outputs/apk/release/app-release.apk"
else
    echo "构建 Debug APK..."
    ./gradlew assembleDebug --no-daemon --console=plain

    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
fi

# 6. 检查构建结果
if [ -f "$APK_PATH" ]; then
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    echo ""
    echo "================================================"
    echo "  构建成功!"
    echo "================================================"
    echo "  APK 路径: $PROJECT_ROOT/android/$APK_PATH"
    echo "  APK 大小: $APK_SIZE"
    echo ""

    # 复制到项目根目录
    VERSION=$(grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' "$PROJECT_ROOT/app.json" | head -1 | cut -d'"' -f4)
    DATE=$(date +%Y%m%d)
    OUTPUT_NAME="CretasFoodTrace-${BUILD_TYPE}-v${VERSION}-${DATE}.apk"

    cp "$APK_PATH" "$PROJECT_ROOT/../../../$OUTPUT_NAME"
    echo "  已复制到: $OUTPUT_NAME"

    # 7. 启动冒烟 —— 把它装起来跑一次
    # 🔴 2026-08-17: v1.0.4 一启动就崩(任何设备), 而当时每一道既有判据都是绿的,
    #    因为没有一条判据是「把它装起来跑一次」。「构建成功」不等于「跑得起来」。
    SMOKE="$PROJECT_ROOT/../../scripts/smoke-android-apk.sh"
    if [ -x "$SMOKE" ]; then
        set +e
        "$SMOKE" "$APK_PATH"
        SMOKE_RC=$?
        set -e
        case "$SMOKE_RC" in
            0) echo "  冒烟通过: 能启动, 无 FATAL / NoSuchMethodError" ;;
            # rc=2 是三态里的「这次没量到」, 不是通过 (本仓硬约束 4)
            2) echo "  ⚠️ 冒烟**没量到**(没有设备/装不上) —— 这不是通过, 分发前请在模拟器上补跑" ;;
            *) echo "  ⛔ 冒烟失败: 这个包装上去打不开, 禁止分发"; exit 1 ;;
        esac
    else
        echo "  ⚠️ 找不到 $SMOKE —— 这个包没有人打开过, 不要直接分发"
    fi

    exit 0
else
    echo ""
    echo "❌ 构建失败，APK 文件未生成"
    exit 1
fi
