jest.mock('expo-updates', () => ({
  isEnabled: true,
  updateId: null,
  runtimeVersion: '1.0.3',
  checkForUpdateAsync: jest.fn(),
  fetchUpdateAsync: jest.fn(),
  reloadAsync: jest.fn(),
}));

jest.mock('react-native', () => {
  return {
    Alert: { alert: jest.fn() },
    AppState: {
      addEventListener: jest.fn(),
    },
  };
});

jest.mock('../i18n', () => ({
  __esModule: true,
  default: {
    t: (key: string) => key,
  },
}));

type UpdatesMock = {
  checkForUpdateAsync: jest.Mock;
  fetchUpdateAsync: jest.Mock;
  reloadAsync: jest.Mock;
};

async function setupTest() {
  Object.defineProperty(global, '__DEV__', {
    configurable: true,
    writable: true,
    value: false,
  });
  jest.resetModules();

  const updates = jest.requireMock<UpdatesMock>('expo-updates');
  const { Alert, AppState } =
    jest.requireMock<typeof import('react-native')>('react-native');
  const asyncStorageModule = jest.requireMock<{
    default: typeof import('@react-native-async-storage/async-storage').default;
  }>('@react-native-async-storage/async-storage');

  jest.clearAllMocks();
  await asyncStorageModule.default.clear();
  const service = await import('./otaUpdateService');
  return {
    updates,
    Alert,
    AppState,
    AsyncStorage: asyncStorageModule.default,
    service,
  };
}

describe('otaUpdateService', () => {
  it('returns up_to_date without prompting or downloading', async () => {
    const { updates, Alert, service } = await setupTest();
    updates.checkForUpdateAsync.mockResolvedValue({
      isAvailable: false,
      isRollBackToEmbedded: false,
      reason: 'NO_UPDATE_AVAILABLE_ON_SERVER',
    });

    await expect(service.checkAndPromptForOtaUpdate()).resolves.toBe('up_to_date');
    expect(Alert.alert).not.toHaveBeenCalled();
    expect(updates.fetchUpdateAsync).not.toHaveBeenCalled();
  });

  it('prompts once and starts downloading as soon as an update is found', async () => {
    const { updates, Alert, service } = await setupTest();
    const manifest = { id: '11111111-2222-3333-4444-555555555555' };
    updates.checkForUpdateAsync.mockResolvedValue({
      isAvailable: true,
      isRollBackToEmbedded: false,
      manifest,
    });
    updates.fetchUpdateAsync.mockResolvedValue({
      isNew: true,
      manifest,
    });

    await expect(service.checkAndPromptForOtaUpdate()).resolves.toBe('available');
    expect(Alert.alert).toHaveBeenCalledTimes(1);
    expect(updates.fetchUpdateAsync).toHaveBeenCalledTimes(1);

    await expect(service.checkAndPromptForOtaUpdate()).resolves.toBe('available');
    expect(Alert.alert).toHaveBeenCalledTimes(1);
  });

  it('shows updating state and reloads only after the user confirms', async () => {
    const { updates, Alert, AsyncStorage, service } = await setupTest();
    const manifest = { id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' };
    updates.checkForUpdateAsync.mockResolvedValue({
      isAvailable: true,
      isRollBackToEmbedded: false,
      manifest,
    });
    updates.fetchUpdateAsync.mockResolvedValue({
      isNew: true,
      manifest,
    });
    updates.reloadAsync.mockResolvedValue(undefined);
    const updatingStates: boolean[] = [];
    service.subscribeOtaUpdating((state) => updatingStates.push(state));

    await service.checkAndPromptForOtaUpdate();
    const buttons = (Alert.alert as jest.Mock).mock.calls[0]?.[2] as
      | Array<{ text: string; onPress?: () => void }>
      | undefined;
    expect(buttons).toHaveLength(2);
    buttons?.[1]?.onPress?.();
    await new Promise((resolve) => setImmediate(resolve));

    expect(updatingStates).toContain(true);
    expect(updates.reloadAsync).toHaveBeenCalledTimes(1);
    expect(AsyncStorage.setItem).toHaveBeenCalledWith(
      '@cretas_ota_apply_attempt',
      expect.stringContaining(manifest.id),
    );
  });

  it('attaches one foreground listener and throttles immediate rechecks', async () => {
    const { updates, AppState, service } = await setupTest();
    updates.checkForUpdateAsync.mockResolvedValue({
      isAvailable: false,
      isRollBackToEmbedded: false,
      reason: 'NO_UPDATE_AVAILABLE_ON_SERVER',
    });

    service.scheduleBackgroundOtaCheck();
    service.scheduleBackgroundOtaCheck();
    await new Promise((resolve) => setImmediate(resolve));

    expect(AppState.addEventListener).toHaveBeenCalledTimes(1);
    expect(updates.checkForUpdateAsync).toHaveBeenCalledTimes(1);
  });
});
