/**
 * Jest 全局测试配置
 * 在所有测试运行前执行
 */

// Mock console — 保留 error/warn 用于调试，静默 log/debug/info
global.console = {
  ...console,
  log: jest.fn(),
  debug: jest.fn(),
  info: jest.fn(),
};

// Mock Logger 以避免 Platform.OS 问题
jest.mock('../utils/logger', () => {
  const makeLogger = (): Record<string, any> => ({
    debug: jest.fn(), info: jest.fn(), warn: jest.fn(), error: jest.fn(),
    child: jest.fn(() => makeLogger()),
    createContextLogger: jest.fn(() => makeLogger()),
  });
  return {
    logger: makeLogger(),
    Logger: jest.fn().mockImplementation(makeLogger),
    ContextLogger: jest.fn().mockImplementation(makeLogger),
    LogLevel: { DEBUG: 0, INFO: 1, WARN: 2, ERROR: 3 },
  };
});

// Mock StorageService
jest.mock('../services/storage/storageService', () => ({
  StorageService: {
    getSecureItem: jest.fn(() => Promise.resolve(null)),
    setSecureItem: jest.fn(() => Promise.resolve()),
    removeSecureItem: jest.fn(() => Promise.resolve()),
    getItem: jest.fn(() => Promise.resolve(null)),
    setItem: jest.fn(() => Promise.resolve()),
    removeItem: jest.fn(() => Promise.resolve()),
    clear: jest.fn(() => Promise.resolve()),
  },
}));

// Mock apiClient with a real axios instance for MockAdapter compatibility
const axios = require('axios');
const mockAxiosInstance = axios.create({
  baseURL: 'http://localhost:10010',
  timeout: 30000,
});

// Add response interceptor to unwrap data (like the real apiClient does)
mockAxiosInstance.interceptors.response.use(
  (response: any) => response.data,
  (error: any) => Promise.reject(error)
);

jest.mock('../services/api/apiClient', () => ({
  apiClient: mockAxiosInstance,
}));

// Mock factoryIdHelper to return a default factory ID for tests
jest.mock('../utils/factoryIdHelper', () => {
  const DEFAULT_FACTORY = 'CRETAS_2024_001';
  return {
    getCurrentFactoryId: jest.fn((provided?: string) => provided || DEFAULT_FACTORY),
    getFactoryId: jest.fn((provided?: string) => provided || DEFAULT_FACTORY),
    requireFactoryId: jest.fn((provided?: string) => provided || DEFAULT_FACTORY),
    getFactoryIdWithFallback: jest.fn((provided?: string) => provided || DEFAULT_FACTORY),
    isValidFactoryId: jest.fn((id: string | null | undefined) => !!(id && id.trim() !== '')),
    isFactoryUser: jest.fn(() => true),
    isPlatformAdmin: jest.fn(() => false),
    FactoryIdStrategy: {
      REQUIRED: 'required',
      FROM_USER: 'from_user',
      OPTIONAL: 'optional',
      PLATFORM_ADMIN: 'platform_admin',
    },
  };
});

// Mock React Native模块
jest.mock('react-native/Libraries/Animated/NativeAnimatedHelper');

// Mock Alert
jest.mock('react-native/Libraries/Alert/Alert', () => ({
  alert: jest.fn(),
}));

// Mock Expo模块
jest.mock('expo-secure-store', () => ({
  setItemAsync: jest.fn(),
  getItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));

jest.mock('expo-location', () => ({
  requestForegroundPermissionsAsync: jest.fn(() => Promise.resolve({ status: 'granted' })),
  getCurrentPositionAsync: jest.fn(() => Promise.resolve({
    coords: {
      latitude: 31.2304,
      longitude: 121.4737,
      altitude: 0,
      accuracy: 10,
      altitudeAccuracy: 0,
      heading: 0,
      speed: 0,
    },
    timestamp: Date.now(),
  })),
}));

jest.mock('expo-image-picker', () => ({
  requestMediaLibraryPermissionsAsync: jest.fn(() => Promise.resolve({ status: 'granted' })),
  launchImageLibraryAsync: jest.fn(() => Promise.resolve({
    canceled: false,
    assets: [{ uri: 'file://mock-image.jpg', width: 100, height: 100 }],
  })),
  MediaTypeOptions: {
    Images: 'Images',
  },
}));

jest.mock('expo-local-authentication', () => ({
  authenticateAsync: jest.fn(() => Promise.resolve({ success: true })),
  hasHardwareAsync: jest.fn(() => Promise.resolve(true)),
  isEnrolledAsync: jest.fn(() => Promise.resolve(true)),
}));

// Mock React Navigation
jest.mock('@react-navigation/native', () => ({
  ...jest.requireActual('@react-navigation/native'),
  useNavigation: () => ({
    navigate: jest.fn(),
    goBack: jest.fn(),
    setOptions: jest.fn(),
    addListener: jest.fn(),
  }),
  useRoute: () => ({
    params: {},
  }),
  useFocusEffect: jest.fn(),
  // 2026-08-16: 补 useNavigationState。屏幕(如 ProcessTaskListScreen 的 canGoBack)
  // 在这份 mock 写好之后才用上它, 而 mock 没跟上 ⇒ 渲染期直接抛
  //   "Couldn't get the navigation state. Is your component inside a navigator?"
  // ⚠️ 这类红是【测试过期】不是屏幕回归 —— 判错方向就会去改屏幕。
  // 返回单路由栈: 语义 = 「当前在栈底」, 于是 canGoBack 为 false, 不凭空造出返回按钮。
  useNavigationState: (selector: (state: unknown) => unknown) =>
    selector({ index: 0, routes: [{ key: 'test-0', name: 'Test' }] }),
}));

// Mock React Native Paper
jest.mock('react-native-paper', () => {
  const RealModule = jest.requireActual('react-native-paper');
  const React = require('react');
  const { View } = require('react-native');
  // 2026-08-16: 补 Appbar。整个 integration/screens 目录解禁后, ProcessTaskListScreen:225
  // 的 <Appbar.Header> 抛 "Cannot read properties of undefined (reading 'Header')"
  // —— RealModule 在本 jest 环境下拿不到 Appbar。⚠️ 这是 mock 缺口(测试过期),
  // 不是屏幕回归; 判错方向就会去改屏幕。
  const passthrough = (name: string) => {
    const C = ({ children }: any) => React.createElement(View, null, children);
    C.displayName = name;
    return C;
  };
  // ⚠️ 必须【构造新对象】, 不能往 RealModule.Appbar 上赋值:
  //    jest.requireActual 返回的是冻结的命名空间对象, `Appbar.Header = x` 会
  //    【静默失败】(非严格模式下不抛错), 于是 mock 看起来写了却没生效 —— 实测踩过一次。
  const real: any = RealModule.Appbar ?? {};
  const Appbar: any = Object.assign(passthrough('Appbar'), {
    Header: real.Header ?? passthrough('Appbar.Header'),
    Content: real.Content ?? passthrough('Appbar.Content'),
    Action: real.Action ?? passthrough('Appbar.Action'),
    BackAction: real.BackAction ?? passthrough('Appbar.BackAction'),
  });
  return {
    ...RealModule,
    Appbar,
    // Portal需要特殊处理
    Portal: ({ children }: any) => children,
  };
});

// Mock AsyncStorage - must provide both default and named exports
// Zustand's persist middleware uses: import AsyncStorage from '...' (default import)
// Then passes it to createJSONStorage(() => AsyncStorage)
jest.mock('@react-native-async-storage/async-storage', () => {
  const store: Record<string, string> = {};
  const mockAsyncStorage = {
    setItem: jest.fn((key: string, value: string) => { store[key] = value; return Promise.resolve(); }),
    getItem: jest.fn((key: string) => Promise.resolve(store[key] ?? null)),
    removeItem: jest.fn((key: string) => { delete store[key]; return Promise.resolve(); }),
    clear: jest.fn(() => { Object.keys(store).forEach(k => delete store[k]); return Promise.resolve(); }),
    getAllKeys: jest.fn(() => Promise.resolve(Object.keys(store))),
    multiGet: jest.fn((keys: string[]) => Promise.resolve(keys.map(k => [k, store[k] ?? null]))),
    multiSet: jest.fn((pairs: [string, string][]) => { pairs.forEach(([k, v]) => { store[k] = v; }); return Promise.resolve(); }),
    multiRemove: jest.fn((keys: string[]) => { keys.forEach(k => delete store[k]); return Promise.resolve(); }),
  };
  return { __esModule: true, default: mockAsyncStorage, ...mockAsyncStorage };
});

// Mock expo-camera (用于BarcodeScannerModal)
jest.mock('expo-camera', () => ({
  CameraView: 'CameraView',
  useCameraPermissions: jest.fn(() => [
    { granted: true },
    jest.fn(() => Promise.resolve({ granted: true })),
  ]),
}));

// Mock react-native-vector-icons
jest.mock('react-native-vector-icons/MaterialCommunityIcons', () => 'MaterialCommunityIcons');

// Mock @expo/vector-icons —— 注意它和上面那行是【两个不同的包】。
//
// 2026-08-16: integration/screens 解禁后, 3 个 suite 在加载期崩在
//   @expo/vector-icons -> expo-font -> expo-modules-core/NativeModule.ts
//   TypeError: Cannot read properties of undefined (reading 'NativeModule')
// 因为 expo-modules-core 要拿 native 运行时对象, jest 的 node 环境里没有。
//
// src/ 下有 197 个文件 import 它, 所以这条属于全局 setup, 与上面已有的
// expo-secure-store / expo-camera / expo-haptics 同一档。此前只有
// AIChatScreenRestaurantAgent.test.tsx 自己写了一份局部 mock —— 那也正是
// 4 个解禁文件里唯一一开始就绿的那个。
//
// ⚠️ 用 createElement 不用 JSX: 本文件是 .ts, 不是 .tsx。
jest.mock('@expo/vector-icons', () => {
  const React = require('react');
  const { Text } = require('react-native');
  const makeIcon = (family: string) => {
    const Icon = (props: Record<string, unknown>) =>
      React.createElement(Text, { ...props, testID: props.testID ?? `icon-${family}` },
        String(props.name ?? ''));
    Icon.displayName = family;
    return Icon;
  };
  return new Proxy({}, {
    get: (_target, key: string) =>
      key === '__esModule' ? true : makeIcon(key),
  });
});

// Mock react-native-safe-area-context —— 解禁后 16 条报
//   TypeError: (0 , react_native_safe_area_context_1.useSafeAreaInsets) is not a function
// 插值给 0, 布局断言不依赖具体安全区高度。
jest.mock('react-native-safe-area-context', () => {
  const React = require('react');
  const inset = { top: 0, right: 0, bottom: 0, left: 0 };
  return {
    __esModule: true,
    SafeAreaProvider: ({ children }: { children?: unknown }) =>
      React.createElement(React.Fragment, null, children),
    SafeAreaView: ({ children }: { children?: unknown }) =>
      React.createElement(React.Fragment, null, children),
    useSafeAreaInsets: () => inset,
    useSafeAreaFrame: () => ({ x: 0, y: 0, width: 390, height: 844 }),
    initialWindowMetrics: { frame: { x: 0, y: 0, width: 390, height: 844 }, insets: inset },
  };
});

// Mock expo-av —— DynamicReportScreen 依赖的语音识别 import { Audio } from 'expo-av',
// 同样走 expo-modules-core 的 native 运行时, 在 jest 里加载期就崩。
jest.mock('expo-av', () => ({
  __esModule: true,
  Audio: {
    Recording: class {
      prepareToRecordAsync = jest.fn();
      startAsync = jest.fn();
      stopAndUnloadAsync = jest.fn();
      getURI = jest.fn(() => 'file:///mock-recording.m4a');
    },
    Sound: { createAsync: jest.fn(async () => ({ sound: { playAsync: jest.fn(), unloadAsync: jest.fn() } })) },
    requestPermissionsAsync: jest.fn(async () => ({ status: 'granted', granted: true })),
    setAudioModeAsync: jest.fn(),
    RecordingOptionsPresets: { HIGH_QUALITY: {} },
  },
  InterruptionModeAndroid: { DoNotMix: 1 },
  InterruptionModeIOS: { DoNotMix: 1 },
}));

// Mock expo-haptics
jest.mock('expo-haptics', () => ({
  impactAsync: jest.fn(),
  notificationAsync: jest.fn(),
  selectionAsync: jest.fn(),
  ImpactFeedbackStyle: { Light: 'Light', Medium: 'Medium', Heavy: 'Heavy' },
  NotificationFeedbackType: { Success: 'Success', Warning: 'Warning', Error: 'Error' },
}));

// 全局清理 afterEach
afterEach(() => {
  jest.clearAllMocks();
});

// 设置测试超时时间
jest.setTimeout(10000);
