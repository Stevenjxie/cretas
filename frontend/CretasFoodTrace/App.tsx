import React, { useEffect } from 'react';
import Toast from 'react-native-toast-message';
import { AppNavigator } from './src/navigation/AppNavigator';
import { useLanguageStore } from './src/store/languageStore';
import { checkAppMinVersion } from './src/services/appVersionCheck';
import { scheduleBackgroundOtaCheck } from './src/services/otaUpdateService';
import UpdateOverlay from './src/components/common/UpdateOverlay';

// 初始化 i18n（必须在 App 组件之前导入）
import './src/i18n';

/**
 * 白垩纪食品溯源系统 - React Native 移动端
 *
 * 功能特性:
 * - 多角色认证系统 / 基于权限的动态导航 / 生物识别 / 自动登录 / 离线 / Token / Toast / i18n
 *
 * OTA 更新策略:
 *   原生启动检查保持 NEVER/0 等待，避免工厂弱网阻塞 splash。
 *   JS 壳启动及回前台主动检查；发现更新后立即提示并并行预下载，
 *   只有用户确认才显示全屏反馈并 reload。
 */
export default function App() {
  const initializeLanguage = useLanguageStore((state) => state.initializeLanguage);

  useEffect(() => {
    // 初始化语言设置
    initializeLanguage();
    // 检查 App 最低版本 (PR #309 B5) — 失败时静默忽略, 不阻塞启动
    checkAppMinVersion();
    scheduleBackgroundOtaCheck();
  }, [initializeLanguage]);

  return (
    <>
      <AppNavigator />
      <UpdateOverlay />
      <Toast />
    </>
  );
}
