import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';
import { logger } from '../../../utils/logger';
import { pushNotificationService } from '../../../services/pushNotificationService';

jest.mock('expo-notifications', () => ({
  setNotificationHandler: jest.fn(),
  getPermissionsAsync: jest.fn(),
  requestPermissionsAsync: jest.fn(),
  setNotificationChannelAsync: jest.fn(),
  getExpoPushTokenAsync: jest.fn(),
  dismissAllNotificationsAsync: jest.fn(),
  getBadgeCountAsync: jest.fn(),
  setBadgeCountAsync: jest.fn(),
  addNotificationReceivedListener: jest.fn(),
  addNotificationResponseReceivedListener: jest.fn(),
  AndroidImportance: {
    MAX: 'MAX',
    HIGH: 'HIGH',
  },
}));

jest.mock('expo-device', () => ({
  isDevice: true,
}));

jest.mock('expo-constants', () => ({
  expoConfig: {
    extra: {
      eas: {
        projectId: 'com.cretas.foodtrace',
      },
    },
  },
}));

describe('pushNotificationService', () => {
  it('skips push token registration as a warning when Firebase is not configured', async () => {
    (Notifications.getExpoPushTokenAsync as jest.Mock).mockRejectedValue(
      new Error(
        'Default FirebaseApp is not initialized in this process com.cretas.foodtrace. Make sure to call FirebaseApp.initializeApp(Context) first.'
      )
    );

    const token = await pushNotificationService.getExpoPushToken();

    const createContextLoggerMock = logger.createContextLogger as jest.MockedFunction<
      typeof logger.createContextLogger
    >;
    const firstLoggerResult = createContextLoggerMock.mock.results[0];
    expect(firstLoggerResult).toBeDefined();

    const pushLogger = firstLoggerResult!.value;
    expect(token).toBeNull();
    expect(pushLogger.warn).toHaveBeenCalledWith(
      'Push Token 未配置或当前包未启用 FCM，跳过设备推送注册',
      expect.any(Error)
    );
    expect(pushLogger.error).not.toHaveBeenCalled();
  });

  it('returns and stores an Expo push token when notification infrastructure is configured', async () => {
    (Notifications.getExpoPushTokenAsync as jest.Mock).mockResolvedValue({
      data: 'ExponentPushToken[f006-test]',
    });

    const token = await pushNotificationService.getExpoPushToken();

    expect(token).toBe('ExponentPushToken[f006-test]');
    expect(Notifications.getExpoPushTokenAsync).toHaveBeenCalledWith({
      projectId: 'com.cretas.foodtrace',
    });
  });

  it('initializes Android notification channels after permission is granted', async () => {
    Platform.OS = 'android';
    (Notifications.getPermissionsAsync as jest.Mock).mockResolvedValue({ status: 'undetermined' });
    (Notifications.requestPermissionsAsync as jest.Mock).mockResolvedValue({ status: 'granted' });
    (Notifications.setNotificationChannelAsync as jest.Mock).mockResolvedValue(undefined);

    await pushNotificationService.initialize();

    expect(Notifications.requestPermissionsAsync).toHaveBeenCalled();
    expect(Notifications.setNotificationChannelAsync).toHaveBeenCalledTimes(3);
    expect(Notifications.setNotificationChannelAsync).toHaveBeenCalledWith(
      'default',
      expect.objectContaining({ name: '默认通知' })
    );
    expect(Notifications.setNotificationChannelAsync).toHaveBeenCalledWith(
      'approval',
      expect.objectContaining({ name: '审批提醒' })
    );
    expect(Notifications.setNotificationChannelAsync).toHaveBeenCalledWith(
      'quality',
      expect.objectContaining({ name: '质检通知' })
    );
  });

  it('wires foreground and response handlers through Expo subscriptions', () => {
    const removeForeground = jest.fn();
    const removeResponse = jest.fn();
    (Notifications.addNotificationReceivedListener as jest.Mock).mockReturnValue({
      remove: removeForeground,
    });
    (Notifications.addNotificationResponseReceivedListener as jest.Mock).mockReturnValue({
      remove: removeResponse,
    });

    const onForeground = jest.fn();
    const onResponse = jest.fn();
    pushNotificationService.setForegroundHandler(onForeground);
    pushNotificationService.setResponseHandler(onResponse);

    expect(Notifications.addNotificationReceivedListener).toHaveBeenCalledWith(expect.any(Function));
    expect(Notifications.addNotificationResponseReceivedListener).toHaveBeenCalledWith(expect.any(Function));
  });

  it('clears notifications and manages badge count', async () => {
    (Notifications.dismissAllNotificationsAsync as jest.Mock).mockResolvedValue(undefined);
    (Notifications.setBadgeCountAsync as jest.Mock).mockResolvedValue(undefined);
    (Notifications.getBadgeCountAsync as jest.Mock).mockResolvedValue(3);

    await pushNotificationService.clearAllNotifications();
    await pushNotificationService.setBadgeCount(0);
    const badgeCount = await pushNotificationService.getBadgeCount();

    expect(Notifications.dismissAllNotificationsAsync).toHaveBeenCalled();
    expect(Notifications.setBadgeCountAsync).toHaveBeenCalledWith(0);
    expect(badgeCount).toBe(3);
  });
});
