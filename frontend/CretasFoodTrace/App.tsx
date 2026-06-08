import React, { useEffect, useState } from 'react';
import Toast from 'react-native-toast-message';
import { AppNavigator } from './src/navigation/AppNavigator';
import { useLanguageStore } from './src/store/languageStore';
import { checkAppMinVersion } from './src/services/appVersionCheck';
import UpdateOverlay from './src/components/common/UpdateOverlay';

// 初始化 i18n（必须在 App 组件之前导入）
import './src/i18n';

// ──────────────────────────────────────────────────────────────────────────────
// expo-updates: gracefully guarded — same defensive-require pattern as T160
// (EnhancedLoginScreen) so both places stay consistent.
//
// FAIL-SAFE CONTRACT (LAUNCH-CRITICAL):
//   • __DEV__ or !Updates.isEnabled  → skip gate immediately, proceed to app
//   • checkForUpdateAsync timeout (8 s) → swallow, proceed to app
//   • Any thrown error anywhere → swallow, proceed to app on cached bundle
//   • Updates.reloadAsync() path → JS reload; code after it never runs
// ──────────────────────────────────────────────────────────────────────────────
let Updates: {
  isEnabled?: boolean;
  checkForUpdateAsync?: () => Promise<{ isAvailable: boolean }>;
  fetchUpdateAsync?: () => Promise<void>;
  reloadAsync?: () => Promise<void>;
} = {};
try {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  Updates = require('expo-updates');
} catch (_e) {
  // expo-updates not bundled (bare web / unit-test host); gate is no-op
}

/**
 * 白垩纪食品溯源系统 - React Native 移动端
 *
 * 功能特性:
 * - 多角色认证系统 (8种角色: developer, platform_admin, factory roles...)
 * - 基于权限的动态导航
 * - 生物识别登录
 * - 自动登录
 * - 离线支持
 * - Token管理
 * - Toast消息提示 (react-native-toast-message)
 * - 多语言支持 (i18n: zh-CN, en-US)
 * - 启动自动 OTA 更新门 (T162) — FAIL-SAFE, 8 s 超时, 全错误吞噬
 */
export default function App() {
  const initializeLanguage = useLanguageStore((state) => state.initializeLanguage);

  // ── OTA 更新门 ─────────────────────────────────────────────────────────────
  // true  = 正在检查/下载更新 → 渲染全屏遮罩 (UpdateOverlay)
  // false = 更新结束 / 跳过 / 失败 → 渲染主应用
  const [updateChecking, setUpdateChecking] = useState(true);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        // 跳过条件:
        //   1. __DEV__           — Expo Go / Metro bundler 开发模式
        //   2. !Updates.isEnabled — 构建时 OTA 未启用 (EXPO_NO_OTA / EAS Embedded build)
        //   3. 方法不存在         — bare web 或 updates 未 bundle
        if (
          __DEV__ ||
          !Updates.isEnabled ||
          typeof Updates.checkForUpdateAsync !== 'function'
        ) {
          if (!cancelled) setUpdateChecking(false);
          return;
        }

        // checkForUpdateAsync 有 8 s 超时，防止弱网/离线永久卡住启动
        const result = await Promise.race([
          Updates.checkForUpdateAsync(),
          new Promise<never>((_, rej) =>
            setTimeout(() => rej(new Error('OTA check timeout')), 8000)
          ),
        ]);

        if (!cancelled && result?.isAvailable) {
          // 有新版本: 下载 → 重载（用户看到 UpdateOverlay 直到新 bundle 启动）
          if (typeof Updates.fetchUpdateAsync === 'function') {
            await Updates.fetchUpdateAsync();
          }
          // reloadAsync 触发 JS 引擎重启，下面代码不会执行
          if (typeof Updates.reloadAsync === 'function') {
            await Updates.reloadAsync();
          }
          return; // 防御: reloadAsync 后不应到达此处
        }
      } catch (_e) {
        // 任何错误（网络/签名/超时/未知）→ 静默吞噬，使用缓存 bundle 继续启动
        // NEVER block launch
      }

      // 无更新 / 跳过 / 失败 → 隐藏遮罩，渲染主应用
      if (!cancelled) setUpdateChecking(false);
    })();

    return () => {
      cancelled = true; // 组件卸载时（不会发生，但防御性标记）
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── 启动遮罩 ───────────────────────────────────────────────────────────────
  // updateChecking=true 期间渲染全屏 "正在更新到最新版…" 遮罩。
  // 仅在 OTA 真正下载完毕并 reloadAsync 前可见（通常 <2 s）。
  // dev/skip 路径: setUpdateChecking(false) 在首帧之前，遮罩几乎不可见。
  if (updateChecking) {
    return <UpdateOverlay />;
  }

  // ── 主应用初始化（原有逻辑，未改动）─────────────────────────────────────
  return <AppRoot initializeLanguage={initializeLanguage} />;
}

// 拆出 AppRoot 避免 hooks 在条件分支前调用（Rules of Hooks）
function AppRoot({
  initializeLanguage,
}: {
  initializeLanguage: () => void;
}) {
  useEffect(() => {
    // 初始化语言设置
    initializeLanguage();
    // 检查 App 最低版本 (PR #309 B5) — 失败时静默忽略, 不阻塞启动
    checkAppMinVersion();
  }, [initializeLanguage]);

  return (
    <>
      <AppNavigator />
      <Toast />
    </>
  );
}
