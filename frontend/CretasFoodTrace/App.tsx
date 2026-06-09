import React, { useEffect } from 'react';
import Toast from 'react-native-toast-message';
import { AppNavigator } from './src/navigation/AppNavigator';
import { useLanguageStore } from './src/store/languageStore';
import { checkAppMinVersion } from './src/services/appVersionCheck';

// 初始化 i18n（必须在 App 组件之前导入）
import './src/i18n';

/**
 * 白垩纪食品溯源系统 - React Native 移动端
 *
 * 功能特性:
 * - 多角色认证系统 / 基于权限的动态导航 / 生物识别 / 自动登录 / 离线 / Token / Toast / i18n
 *
 * OTA 更新策略 (2026-06-09 回退阻塞门):
 *   由 app.json `expo.updates.checkAutomatically: ON_LOAD` 原生后台下载 + 下次冷启动瞬间生效。
 *   不再用「启动阻塞式更新门」—— 每次 OTA 需重下整个 ~15MB Hermes 包(不能增量),
 *   工厂弱网下阻塞等待会卡死/超时丢弃/反复重下(踩过)。原生后台下载可断点续传, 可靠不阻塞。
 */
export default function App() {
  const initializeLanguage = useLanguageStore((state) => state.initializeLanguage);

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
