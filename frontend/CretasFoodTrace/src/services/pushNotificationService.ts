import type * as NotificationsNS from 'expo-notifications';
import * as Device from 'expo-device';
import { Platform } from 'react-native';
import Constants from 'expo-constants';
import { logger } from '../utils/logger';

const pushLogger = logger.createContextLogger('PushNotification');

/**
 * APP-WEB-007 收尾 (2026-08-02): Web 上**连 import 都不能做**。
 *
 * 上一轮已经把本服务和 usePushNotifications 里每个方法都加了 `Platform.OS !== 'web'`
 * 守卫, 但 12 角色矩阵复跑时这条 warning 仍然 12/12 出现:
 *
 *   [expo-notifications] Listening to push token changes is not yet fully supported
 *   on web. Adding a listener will have no effect.
 *
 * 原因是它**不是我们调出来的**: expo-notifications 的 index 会 re-export
 * `DevicePushTokenAutoRegistration.fx.js`, 而那个模块在**全局作用域**直接跑
 * `addPushTokenListener(...)` (build/DevicePushTokenAutoRegistration.fx.js:60),
 * web 实现里 `PushTokenManager.addListener` 一被调用就 console.warn。
 * 也就是说: 只要这个模块被**求值**, 告警就产生 —— 我们函数体里加多少守卫都拦不住。
 *
 * 所以这里改成"按平台惰性求值": web 上永远不 require, 该模块不执行, 告警自然为 0。
 * 类型仍用 `import type` 静态引入 (纯类型, 编译期擦除, 不产生运行时依赖)。
 *
 * ⚠️ 别改回静态 `import * as Notifications from 'expo-notifications'` ——
 * 那会让这条告警原样回来, 且**看不出是谁引的**。
 */
const IS_WEB = Platform.OS === 'web';

/**
 * 运行时句柄。web 上是 null —— 但下面每一处使用点都在 `PUSH_SUPPORTED` 守卫之后,
 * 所以按非空类型暴露, 免得 18 个调用点全要写 `!`。
 * 类型仍走上面的 `import type * as NotificationsNS`(编译期擦除, 不产生运行时依赖)。
 */
// eslint-disable-next-line @typescript-eslint/no-var-requires, global-require
const Notifications = (IS_WEB ? null : require('expo-notifications')) as typeof NotificationsNS;

// 设置通知处理器 - 在前台时显示通知 (web 上没有原生通知层, 跳过)
if (!IS_WEB) {
  Notifications.setNotificationHandler({
    handleNotification: async () => ({
      shouldShowAlert: true,
      shouldPlaySound: true,
      shouldSetBadge: true,
      shouldShowBanner: true,
      shouldShowList: true,
    }),
  });
}

export interface PushNotificationService {
  /**
   * 初始化推送通知服务
   * - 请求权限
   * - 配置通知渠道 (Android)
   */
  initialize(): Promise<void>;

  /**
   * 获取 Expo Push Token
   * @returns Push Token 或 null（如果在模拟器或无权限）
   */
  getExpoPushToken(): Promise<string | null>;

  /**
   * 注册 Token 到后端
   * @param token - Expo Push Token
   */
  registerToken(token: string): Promise<void>;

  /**
   * 注销 Token（登出时）
   */
  unregisterToken(): Promise<void>;

  /**
   * 设置前台通知处理器
   * @param handler - 收到通知时的回调
   */
  setForegroundHandler(handler: (notification: NotificationsNS.Notification) => void): void;

  /**
   * 设置通知响应处理器（用户点击通知）
   * @param handler - 点击通知时的回调
   */
  setResponseHandler(handler: (response: NotificationsNS.NotificationResponse) => void): void;

  /**
   * 清除所有通知
   */
  clearAllNotifications(): Promise<void>;

  /**
   * 获取未读通知数量
   */
  getBadgeCount(): Promise<number>;

  /**
   * 设置应用角标数量
   * @param count - 角标数量
   */
  setBadgeCount(count: number): Promise<void>;
}

/**
 * APP-WEB-007 (2026-08-02): Expo 的原生推送在 Web 上不可用 —— 没有 EAS projectId 概念,
 * 也不支持 push token listener。此前只用 `Device.isDevice` 判断, 而浏览器**就是**真机,
 * 于是 12 个角色每次登录都会走完"请求权限 → 读 projectId → 注册原生监听"整条链路,
 * 稳定产出「未找到 EAS Project ID」「推送通知权限被拒绝」两条 error。
 * 这里在服务最外层对 Web 明确 no-op: 不请求权限、不读 token、不注册监听、不报 error。
 * 若将来要支持 Web Push, 必须走独立的 service worker/VAPID 实现, 不能复用这条原生链路。
 */
export const isPushSupported = Platform.OS !== 'web';
const PUSH_SUPPORTED = isPushSupported;

class PushNotificationServiceImpl implements PushNotificationService {
  private notificationListener?: NotificationsNS.Subscription;
  private responseListener?: NotificationsNS.Subscription;
  private currentToken: string | null = null;

  async initialize(): Promise<void> {
    if (!PUSH_SUPPORTED) {
      pushLogger.debug('Web 环境不支持原生推送, 跳过初始化');
      return;
    }
    pushLogger.info('初始化推送通知服务');

    // 检查是否为真机
    if (!Device.isDevice) {
      pushLogger.warn('推送通知仅支持真机，当前为模拟器');
      return;
    }

    // 请求权限
    const { status: existingStatus } = await Notifications.getPermissionsAsync();
    let finalStatus = existingStatus;

    if (existingStatus !== 'granted') {
      const { status } = await Notifications.requestPermissionsAsync();
      finalStatus = status;
    }

    if (finalStatus !== 'granted') {
      pushLogger.error('推送通知权限被拒绝');
      return;
    }

    pushLogger.info('推送通知权限已授予');

    // Android 配置通知渠道
    if (Platform.OS === 'android') {
      await this.setupAndroidChannel();
    }
  }

  private async setupAndroidChannel(): Promise<void> {
    await Notifications.setNotificationChannelAsync('default', {
      name: '默认通知',
      importance: Notifications.AndroidImportance.MAX,
      vibrationPattern: [0, 250, 250, 250],
      lightColor: '#1976D2',
    });

    await Notifications.setNotificationChannelAsync('approval', {
      name: '审批提醒',
      importance: Notifications.AndroidImportance.HIGH,
      vibrationPattern: [0, 250, 250, 250],
      lightColor: '#FF5722',
    });

    await Notifications.setNotificationChannelAsync('quality', {
      name: '质检通知',
      importance: Notifications.AndroidImportance.HIGH,
      vibrationPattern: [0, 250, 250, 250],
      lightColor: '#FFC107',
    });

    pushLogger.info('Android 通知渠道已配置');
  }

  async getExpoPushToken(): Promise<string | null> {
    if (!PUSH_SUPPORTED) {
      pushLogger.debug('Web 环境无 Expo Push Token');
      return null;
    }
    if (!Device.isDevice) {
      pushLogger.warn('模拟器无法获取 Push Token');
      return null;
    }

    try {
      const projectId = Constants.expoConfig?.extra?.eas?.projectId;
      if (!projectId) {
        pushLogger.error('未找到 EAS Project ID');
        return null;
      }

      const token = await Notifications.getExpoPushTokenAsync({
        projectId,
      });

      this.currentToken = token.data;
      pushLogger.info('获取 Push Token 成功', { token: token.data });
      return token.data;
    } catch (error) {
      pushLogger.error('获取 Push Token 失败', error);
      return null;
    }
  }

  async registerToken(token: string): Promise<void> {
    // 此方法由外部调用，将 token 发送到后端
    // 实际的 HTTP 请求在 deviceApiClient 中实现
    this.currentToken = token;
    pushLogger.info('Token 已设置，等待注册到后端', { token });
  }

  async unregisterToken(): Promise<void> {
    // 此方法由外部调用，从后端注销 token
    this.currentToken = null;
    pushLogger.info('Token 已清除');
  }

  setForegroundHandler(handler: (notification: NotificationsNS.Notification) => void): void {
    if (!PUSH_SUPPORTED) return;
    // 移除旧的监听器
    if (this.notificationListener) {
      this.notificationListener.remove();
    }

    this.notificationListener = Notifications.addNotificationReceivedListener((notification) => {
      pushLogger.debug('收到前台通知', {
        title: notification.request.content.title,
        body: notification.request.content.body,
      });
      handler(notification);
    });

    pushLogger.info('前台通知处理器已设置');
  }

  setResponseHandler(handler: (response: NotificationsNS.NotificationResponse) => void): void {
    if (!PUSH_SUPPORTED) return;
    // 移除旧的监听器
    if (this.responseListener) {
      this.responseListener.remove();
    }

    this.responseListener = Notifications.addNotificationResponseReceivedListener((response) => {
      pushLogger.debug('用户点击通知', {
        actionIdentifier: response.actionIdentifier,
        data: response.notification.request.content.data,
      });
      handler(response);
    });

    pushLogger.info('通知响应处理器已设置');
  }

  async clearAllNotifications(): Promise<void> {
    if (!PUSH_SUPPORTED) return;
    await Notifications.dismissAllNotificationsAsync();
    pushLogger.info('所有通知已清除');
  }

  async getBadgeCount(): Promise<number> {
    if (!PUSH_SUPPORTED) return 0;
    const count = await Notifications.getBadgeCountAsync();
    return count;
  }

  async setBadgeCount(count: number): Promise<void> {
    if (!PUSH_SUPPORTED) return;
    await Notifications.setBadgeCountAsync(count);
    pushLogger.debug('角标数量已设置', { count });
  }

  /**
   * 清理资源（组件卸载时调用）
   */
  cleanup(): void {
    if (this.notificationListener) {
      this.notificationListener.remove();
    }
    if (this.responseListener) {
      this.responseListener.remove();
    }
    pushLogger.info('推送通知服务已清理');
  }
}

// Singleton export
export const pushNotificationService: PushNotificationService = new PushNotificationServiceImpl();
