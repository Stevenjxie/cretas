import * as Notifications from 'expo-notifications';
import { logger } from '../../../utils/logger';
import { pushNotificationService } from '../../../services/pushNotificationService';

jest.mock('expo-notifications', () => ({
  setNotificationHandler: jest.fn(),
  getExpoPushTokenAsync: jest.fn(),
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
});
