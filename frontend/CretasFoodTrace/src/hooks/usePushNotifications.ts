import { useEffect, useRef } from 'react';
import * as Notifications from 'expo-notifications';
import { pushNotificationService, isPushSupported } from '../services/pushNotificationService';
import { deviceAPI } from '../services/api/deviceApiClient';
import { useAuthStore } from '../store/authStore';
import { logger } from '../utils/logger';

const notificationLogger = logger.createContextLogger('usePushNotifications');

/**
 * 推送通知 Hook
 * 自动处理推送通知的初始化、Token 注册和事件监听
 *
 * 使用方法：
 * ```tsx
 * function App() {
 *   usePushNotifications({
 *     onNotificationReceived: (notification) => {
 *       console.log('收到通知:', notification);
 *     },
 *     onNotificationTapped: (response) => {
 *       // 导航到相关页面
 *       navigation.navigate(response.notification.request.content.data.screen);
 *     }
 *   });
 *
 *   return <YourApp />;
 * }
 * ```
 */
export interface UsePushNotificationsOptions {
  /**
   * 收到前台通知时的回调
   */
  onNotificationReceived?: (notification: Notifications.Notification) => void;

  /**
   * 用户点击通知时的回调
   */
  onNotificationTapped?: (response: Notifications.NotificationResponse) => void;

  /**
   * 是否在登录后自动注册设备
   * @default true
   */
  autoRegisterOnLogin?: boolean;

  /**
   * 是否在登出时自动注销设备
   * @default true
   */
  autoUnregisterOnLogout?: boolean;
}

/**
 * 推送通知 Hook
 */
export function usePushNotifications(options: UsePushNotificationsOptions = {}) {
  const {
    onNotificationReceived,
    onNotificationTapped,
    autoRegisterOnLogin = true,
    autoUnregisterOnLogout = true,
  } = options;

  const { isAuthenticated, user } = useAuthStore();
  const isInitialized = useRef(false);
  const isRegistered = useRef(false);

  /**
   * 初始化推送通知
   */
  useEffect(() => {
    // APP-WEB-007: Web 不支持原生推送, 整个 hook 直接空转 —— 不初始化、不取 token、
    // 不注册设备, 也就不会每次登录都刷两条 error 和一条"无法获取 Push Token"的 warn。
    if (!isPushSupported) return;
    if (isInitialized.current) return;

    async function initializeNotifications() {
      try {
        notificationLogger.info('初始化推送通知服务');
        await pushNotificationService.initialize();
        isInitialized.current = true;
      } catch (error) {
        notificationLogger.error('初始化推送通知失败', error);
      }
    }

    initializeNotifications();
  }, []);

  /**
   * 设置通知事件处理器
   */
  useEffect(() => {
    if (!isPushSupported) return;
    if (onNotificationReceived) {
      pushNotificationService.setForegroundHandler(onNotificationReceived);
    }

    if (onNotificationTapped) {
      pushNotificationService.setResponseHandler(onNotificationTapped);
    }
  }, [onNotificationReceived, onNotificationTapped]);

  /**
   * 登录后注册设备
   */
  useEffect(() => {
    if (!isPushSupported) return;
    if (!isAuthenticated || !user || !autoRegisterOnLogin) {
      return;
    }

    if (isRegistered.current) {
      notificationLogger.debug('设备已注册，跳过重复注册');
      return;
    }

    async function registerDevice() {
      try {
        notificationLogger.info('用户已登录，注册设备');

        // 获取 Push Token
        const token = await pushNotificationService.getExpoPushToken();
        if (!token) {
          notificationLogger.warn('无法获取 Push Token，跳过设备注册');
          return;
        }

        // 注册到后端
        await deviceAPI.registerDevice(token);
        isRegistered.current = true;

        notificationLogger.info('设备注册成功');
      } catch (error) {
        notificationLogger.error('设备注册失败', error);
      }
    }

    registerDevice();
  }, [isAuthenticated, user, autoRegisterOnLogin]);

  /**
   * 登出时注销设备
   */
  useEffect(() => {
    if (isAuthenticated || !autoUnregisterOnLogout) {
      return;
    }

    if (!isRegistered.current) {
      return;
    }

    async function unregisterDevice() {
      try {
        notificationLogger.info('用户已登出，注销设备');

        await deviceAPI.unregisterDevice();
        isRegistered.current = false;

        // 清除所有通知
        await pushNotificationService.clearAllNotifications();
        await pushNotificationService.setBadgeCount(0);

        notificationLogger.info('设备注销成功');
      } catch (error) {
        notificationLogger.error('设备注销失败', error);
      }
    }

    unregisterDevice();
  }, [isAuthenticated, autoUnregisterOnLogout]);

  return {
    /**
     * 手动注册设备
     */
    registerDevice: async () => {
      try {
        const token = await pushNotificationService.getExpoPushToken();
        if (!token) {
          throw new Error('无法获取 Push Token');
        }

        await deviceAPI.registerDevice(token);
        isRegistered.current = true;
        notificationLogger.info('手动注册设备成功');
      } catch (error) {
        notificationLogger.error('手动注册设备失败', error);
        throw error;
      }
    },

    /**
     * 手动注销设备
     */
    unregisterDevice: async () => {
      try {
        await deviceAPI.unregisterDevice();
        isRegistered.current = false;
        notificationLogger.info('手动注销设备成功');
      } catch (error) {
        notificationLogger.error('手动注销设备失败', error);
        throw error;
      }
    },

    /**
     * 清除所有通知
     */
    clearAllNotifications: async () => {
      await pushNotificationService.clearAllNotifications();
    },

    /**
     * 设置应用角标数量
     */
    setBadgeCount: async (count: number) => {
      await pushNotificationService.setBadgeCount(count);
    },

    /**
     * 获取应用角标数量
     */
    getBadgeCount: async () => {
      return await pushNotificationService.getBadgeCount();
    },
  };
}
