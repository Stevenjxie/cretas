#!/usr/bin/env bash
###############################################################################
# APK 启动冒烟闸 —— 把包装起来跑一次, 确认它没在启动时死。
#
# 🔴 为什么有这个脚本 (2026-08-17):
#   分发中的 v1.0.4 APK **一启动就崩, 任何设备都崩**:
#     java.lang.NoSuchMethodError: getDirectConverter(...) in ReturnTypeKt
#     at expo.modules.font.FontLoaderModule.definition(FontLoaderModule.kt:98)
#   而当时**每一道既有判据都是绿的** —— OTA 四步验收(命令成功/manifest 发新的/
#   配置对得上/包里 grep 到改动标记)全过, jest / tsc / CI 也全绿。
#   ⇒ 因为**没有任何一条判据是「把它装起来跑一次」**。包发出去了、bundle 也推上去了,
#     而没有一个人打开过它。
#
#   这与本仓部署硬约束 2 是同一句话, 只是载体从 jar 换成了 APK:
#   「在制品里 grep 到标记」证明**发出去了**, 不证明**跑得起来**。
#
# 用法:
#   ./scripts/smoke-android-apk.sh <apk-path> [package-name] [wait-seconds]
#
# 退出码是**三态**(硬约束 4 —— 「没量到」必须与「没问题」分开):
#   0  启动正常
#   1  真的崩了 / 起不来          ← 有读数, 且指向缺陷
#   2  **这次没量到**(没有设备 / 装不上 / logcat 读不到) ← 读数作废, 不要当成通过
###############################################################################
set -uo pipefail

APK="${1:-}"
PKG="${2:-com.cretas.foodtrace}"
WAIT="${3:-25}"

ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null 2>&1 || ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"

die_nomeasure() { echo "SMOKE=NO_MEASUREMENT reason=$1" >&2; exit 2; }

[ -n "$APK" ] || die_nomeasure "未传 apk 路径"
[ -f "$APK" ] || die_nomeasure "apk 不存在: $APK"
command -v "$ADB" >/dev/null 2>&1 || die_nomeasure "找不到 adb"

# ANDROID_SERIAL 优先 —— 同时连着真机和模拟器时, 别让它随手挑第一个。
# ⚠️ 厂商 ROM(如 MIUI)的真机装不上, 那会得到 rc=2「没量到」而不是 rc=1;
#    做这道闸请指定模拟器: ANDROID_SERIAL=emulator-5554 ./scripts/smoke-android-apk.sh <apk>
DEV="${ANDROID_SERIAL:-}"
if [ -n "$DEV" ]; then
    "$ADB" devices | awk -v d="$DEV" '$1==d && $2=="device"{found=1} END{exit !found}' \
      || die_nomeasure "ANDROID_SERIAL=$DEV 不在已授权设备列表里"
else
    DEV=$("$ADB" devices | awk '$2=="device"{print $1; exit}')
fi
[ -n "$DEV" ] || die_nomeasure "没有已连接且已授权的设备/模拟器"
echo "SMOKE_DEVICE=$DEV"

# ⚠️ MIUI 等厂商 ROM 会用 INSTALL_FAILED_USER_RESTRICTED 拒掉 adb 安装(需设备端开「USB 安装」),
#    `pm install` 走同一个检查点也拒 —— 那种情况是「没量到」, 不是「包坏了」。
"$ADB" -s "$DEV" uninstall "$PKG" >/dev/null 2>&1 || true
INSTALL_OUT=$("$ADB" -s "$DEV" install -r "$APK" 2>&1)
echo "$INSTALL_OUT" | grep -q "^Success" || die_nomeasure "安装失败: $(echo "$INSTALL_OUT" | tail -1)"

# 🔴 阳性对照 1: 包真的装上了。没有这一条,「没崩」可能只是因为根本没装。
"$ADB" -s "$DEV" shell pm list packages 2>/dev/null | grep -q "^package:$PKG$" \
  || die_nomeasure "install 报 Success 但 pm 里查不到 $PKG"

"$ADB" -s "$DEV" logcat -c >/dev/null 2>&1
"$ADB" -s "$DEV" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep "$WAIT"

LOG=$("$ADB" -s "$DEV" logcat -d 2>/dev/null)
# 🔴 阳性对照 2: logcat 真的读到了东西。空 logcat 会让下面的 grep 恒不命中 ⇒ 假绿。
LOGLINES=$(printf '%s\n' "$LOG" | wc -l)
[ "$LOGLINES" -gt 50 ] || die_nomeasure "logcat 只读到 $LOGLINES 行, 仪器可疑"
echo "SMOKE_LOGLINES=$LOGLINES"

FAILED=0

CRASH=$(printf '%s\n' "$LOG" | grep -cE "FATAL EXCEPTION|NoSuchMethodError|NoClassDefFoundError|UnsatisfiedLinkError")
echo "SMOKE_CRASH_LINES=$CRASH"
if [ "$CRASH" -gt 0 ]; then
    echo "--- 崩溃摘录 ---"
    printf '%s\n' "$LOG" | grep -E "FATAL EXCEPTION|NoSuchMethodError|NoClassDefFoundError|UnsatisfiedLinkError" | head -5
    FAILED=1
fi

ALIVE=$("$ADB" -s "$DEV" shell "ps -A 2>/dev/null | grep -c $PKG" | tr -d '\r')
echo "SMOKE_PROCESS_ALIVE=$ALIVE"
[ "${ALIVE:-0}" -ge 1 ] || FAILED=1

# 前台必须是自己 —— 崩回桌面时进程可能还在, 但 topResumedActivity 会是 Launcher。
TOP=$("$ADB" -s "$DEV" shell dumpsys activity activities 2>/dev/null | grep -m1 "topResumedActivity" | tr -d '\r')
echo "SMOKE_TOP=$TOP"
printf '%s' "$TOP" | grep -q "$PKG" || FAILED=1

if [ "$FAILED" -eq 0 ]; then
    echo "SMOKE=PASS"
    exit 0
fi
echo "SMOKE=FAIL  ← 这个包装上去打不开, ⛔ 不要分发" >&2
exit 1
