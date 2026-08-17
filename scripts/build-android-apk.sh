#!/bin/bash

###############################################################################
# Android APK 自动化构建脚本
# 用途: 本地构建 Android APK（Debug 或 Release 版本）
# 项目: 白垩纪食品溯源系统
# 版本: 1.0.0
###############################################################################

set -e  # 遇到错误立即退出

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 项目路径
PROJECT_ROOT="/Users/jietaoxie/my-prototype-logistics"
FRONTEND_DIR="$PROJECT_ROOT/frontend/CretasFoodTrace"
ANDROID_DIR="$FRONTEND_DIR/android"

# 默认配置
BUILD_TYPE="debug"  # debug 或 release
ENV_FILE=".env.production"  # 默认使用生产环境配置
CLEAN_BUILD=false

###############################################################################
# 函数定义
###############################################################################

print_header() {
  echo -e "${BLUE}========================================${NC}"
  echo -e "${BLUE}$1${NC}"
  echo -e "${BLUE}========================================${NC}"
}

print_success() {
  echo -e "${GREEN}✅ $1${NC}"
}

print_error() {
  echo -e "${RED}❌ $1${NC}"
}

print_warning() {
  echo -e "${YELLOW}⚠️  $1${NC}"
}

print_info() {
  echo -e "${BLUE}ℹ️  $1${NC}"
}

check_dependencies() {
  print_header "检查依赖"

  # 检查 Node.js
  if ! command -v node &> /dev/null; then
    print_error "Node.js 未安装，请先安装 Node.js 18+"
    exit 1
  fi
  print_success "Node.js 版本: $(node --version)"

  # 检查 npm
  if ! command -v npm &> /dev/null; then
    print_error "npm 未安装"
    exit 1
  fi
  print_success "npm 版本: $(npm --version)"

  # 检查 Java
  if ! command -v java &> /dev/null; then
    print_error "Java 未安装，请先安装 JDK 17"
    print_info "macOS: brew install openjdk@17"
    exit 1
  fi
  print_success "Java 版本: $(java -version 2>&1 | head -n 1)"

  # 检查 ANDROID_HOME
  if [ -z "$ANDROID_HOME" ]; then
    print_error "ANDROID_HOME 环境变量未设置"
    print_info "请设置 ANDROID_HOME 指向 Android SDK 目录"
    print_info "例如: export ANDROID_HOME=\$HOME/Library/Android/sdk"
    exit 1
  fi
  print_success "ANDROID_HOME: $ANDROID_HOME"

  # 检查 Android SDK
  if [ ! -d "$ANDROID_HOME/platform-tools" ]; then
    print_error "Android SDK 未正确安装"
    print_info "请通过 Android Studio SDK Manager 安装 Android SDK"
    exit 1
  fi
  print_success "Android SDK 已安装"

  # 检查 adb
  if ! command -v adb &> /dev/null; then
    print_warning "adb 未在 PATH 中，可能无法直接安装 APK"
  else
    print_success "adb 版本: $(adb version | head -n 1)"
  fi
}

install_dependencies() {
  print_header "安装项目依赖"

  cd "$FRONTEND_DIR"

  # 🔴 2026-08-17: 这里曾经是「node_modules 存在就跳过安装」+ `npm install`。
  # 两处都出过事:
  #   · 跳过安装 ⇒ 打包机上留着的那套(可能早已漂过的)依赖被直接拿去打包;
  #   · `npm install` 会在 lockfile 之外重新解析, 与 `npm ci` 不同。
  # 实测代价: 分发中的 v1.0.4 APK **一启动就崩, 任何设备都崩** ——
  #   java.lang.NoSuchMethodError: getDirectConverter(...) in ReturnTypeKt
  #   at expo.modules.font.FontLoaderModule.definition(FontLoaderModule.kt:98)
  # 从 APK 的 dex 直接证死(dex 全 ABI 共用): 调用方在、被调方不在。
  # 而 lockfile 一直锁着好版本(expo-modules-core@2.5.0 / expo-font@13.3.2,
  # 两个包的源码里都没有 getDirectConverter 这个名字) ⇒ 打出来的包不是按锁装的。
  # ⇒ 一律 npm ci: 它会先清掉 node_modules, 再严格按 lockfile 装。
  print_info "按 lockfile 全量安装依赖 (npm ci)..."
  npm ci
  print_success "npm 依赖安装完成 (与 package-lock.json 一致)"
}

prepare_environment() {
  print_header "准备构建环境"

  cd "$FRONTEND_DIR"

  # 复制环境配置文件
  if [ -f "$ENV_FILE" ]; then
    print_info "使用环境配置: $ENV_FILE"
    cp "$ENV_FILE" .env
    print_success "已复制 $ENV_FILE 为 .env"

    # 显示配置内容（隐藏敏感信息）
    print_info "环境配置内容:"
    grep -v "^#" .env | grep -v "^$" | while read -r line; do
      echo "  $line"
    done
  else
    print_error "环境配置文件 $ENV_FILE 不存在"
    exit 1
  fi
}

generate_native_project() {
  print_header "生成原生 Android 项目"

  cd "$FRONTEND_DIR"

  if [ -d "android" ] && [ "$CLEAN_BUILD" = false ]; then
    print_warning "android 目录已存在"
    read -p "是否重新生成原生项目？(y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
      print_info "跳过生成，使用现有 android 项目"
      return 0
    fi
    CLEAN_BUILD=true
  fi

  if [ "$CLEAN_BUILD" = true ]; then
    print_info "清理旧的原生项目..."
    rm -rf android ios
    print_success "清理完成"
  fi

  print_info "运行 expo prebuild..."
  npx expo prebuild --clean --platform android

  if [ -d "android" ]; then
    print_success "原生 Android 项目生成成功"
  else
    print_error "原生项目生成失败"
    exit 1
  fi
}

build_apk() {
  print_header "构建 Android APK"

  if [ ! -d "$ANDROID_DIR" ]; then
    print_error "android 目录不存在，请先生成原生项目"
    exit 1
  fi

  cd "$ANDROID_DIR"

  # 确保 gradlew 可执行
  chmod +x gradlew

  if [ "$BUILD_TYPE" = "release" ]; then
    print_info "构建 Release APK..."
    print_warning "Release 版本需要签名密钥，请确保已配置"

    ./gradlew assembleRelease

    APK_PATH="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
  else
    print_info "构建 Debug APK..."

    # 清理之前的构建（可选）
    if [ "$CLEAN_BUILD" = true ]; then
      ./gradlew clean
    fi

    ./gradlew assembleDebug

    APK_PATH="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
  fi

  if [ -f "$APK_PATH" ]; then
    print_success "APK 构建成功！"
    print_info "APK 位置: $APK_PATH"

    # 显示 APK 信息
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    print_info "APK 大小: $APK_SIZE"

    # 复制到项目根目录
    OUTPUT_DIR="$PROJECT_ROOT/builds"
    mkdir -p "$OUTPUT_DIR"

    TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
    OUTPUT_APK="$OUTPUT_DIR/cretas-foodtrace-${BUILD_TYPE}-${TIMESTAMP}.apk"

    cp "$APK_PATH" "$OUTPUT_APK"
    print_success "APK 已复制到: $OUTPUT_APK"

    # 创建最新版本的符号链接
    ln -sf "$OUTPUT_APK" "$OUTPUT_DIR/cretas-foodtrace-${BUILD_TYPE}-latest.apk"
    print_info "最新版本链接: $OUTPUT_DIR/cretas-foodtrace-${BUILD_TYPE}-latest.apk"
  else
    print_error "APK 构建失败"
    exit 1
  fi
}

install_apk() {
  print_header "安装 APK 到设备"

  # 检查是否有连接的设备
  if ! command -v adb &> /dev/null; then
    print_warning "adb 未安装，跳过自动安装"
    return 0
  fi

  DEVICES=$(adb devices | grep -v "List" | grep "device$" | wc -l | tr -d ' ')

  if [ "$DEVICES" -eq 0 ]; then
    print_warning "未检测到 Android 设备"
    print_info "请手动安装 APK: $OUTPUT_APK"
    return 0
  fi

  print_info "检测到 $DEVICES 个设备"
  read -p "是否安装 APK 到设备？(y/N) " -n 1 -r
  echo

  if [[ $REPLY =~ ^[Yy]$ ]]; then
    print_info "安装 APK..."
    adb install -r "$OUTPUT_APK"
    print_success "APK 安装成功"

    read -p "是否启动应用？(y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
      adb shell monkey -p com.cretas.foodtrace -c android.intent.category.LAUNCHER 1
      print_success "应用已启动"
    fi
  fi
}

show_summary() {
  print_header "构建总结"

  echo -e "${GREEN}🎉 构建成功！${NC}"
  echo ""
  echo -e "${BLUE}构建信息:${NC}"
  echo "  - 构建类型: $BUILD_TYPE"
  echo "  - 环境配置: $ENV_FILE"
  echo "  - APK 位置: $OUTPUT_APK"
  echo "  - APK 大小: $APK_SIZE"
  echo ""
  echo -e "${BLUE}下一步:${NC}"
  echo "  1. 测试 APK: adb install -r $OUTPUT_APK"
  echo "  2. 查看日志: adb logcat | grep ReactNative"
  echo "  3. 分发测试: 将 APK 发送给测试人员"
  echo ""
}

show_usage() {
  cat << EOF
Android APK 自动化构建脚本

用法: $0 [选项]

选项:
  -t TYPE       构建类型: debug 或 release (默认: debug)
  -e ENV        环境配置文件 (默认: .env.production)
                可选: .env.local, .env.test, .env.production
  -c            清理构建（删除 android 目录重新生成）
  -i            构建后自动安装到设备
  -h            显示此帮助信息

示例:
  # 构建 Debug APK（生产环境配置）
  $0

  # 构建 Debug APK（测试环境配置）
  $0 -e .env.test

  # 构建 Release APK
  $0 -t release

  # 清理构建并自动安装
  $0 -c -i

  # 构建测试环境的 Release 版本
  $0 -t release -e .env.test

EOF
}

###############################################################################
# 主流程
###############################################################################

main() {
  # 解析命令行参数
  AUTO_INSTALL=false

  while getopts "t:e:cih" opt; do
    case $opt in
      t)
        BUILD_TYPE="$OPTARG"
        if [ "$BUILD_TYPE" != "debug" ] && [ "$BUILD_TYPE" != "release" ]; then
          print_error "无效的构建类型: $BUILD_TYPE (应为 debug 或 release)"
          exit 1
        fi
        ;;
      e)
        ENV_FILE="$OPTARG"
        ;;
      c)
        CLEAN_BUILD=true
        ;;
      i)
        AUTO_INSTALL=true
        ;;
      h)
        show_usage
        exit 0
        ;;
      \?)
        print_error "无效的选项: -$OPTARG"
        show_usage
        exit 1
        ;;
    esac
  done

  # 显示构建信息
  print_header "Android APK 构建工具"
  echo -e "${BLUE}构建类型:${NC} $BUILD_TYPE"
  echo -e "${BLUE}环境配置:${NC} $ENV_FILE"
  echo -e "${BLUE}清理构建:${NC} $CLEAN_BUILD"
  echo ""

  # 执行构建步骤
  check_dependencies
  install_dependencies
  prepare_environment
  generate_native_project
  build_apk

  if [ "$AUTO_INSTALL" = true ]; then
    install_apk
  fi

  show_summary
}

# 运行主函数
main "$@"
