/**
 * 缺陷 A — 完工出成块「损耗 (自动)」的显示修复.
 *
 * prod 实测 (2026-08-17): `损耗 (自动) = 投入 {inp}{reportedInputUnit} − 产出 {out}{outUnit} = {computed}{unit}`
 * 在两侧单位不同时做了跨单位相减 (99kg − 80盒 = "19盒"), 且负值被 Math.max(0,...) 夹成 0
 * 但照样印 "=" (10kg 投入却填 99 产出 → "= 0kg", 看起来像刚好用完, 其实是填反了).
 *
 * 断言跑在产品真实入口 (渲染 YieldStepReportScreen 本体, 只桩 API/store), 不是直接调 helper.
 */
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

// ── React Navigation: setup.ts 的全局 mock 对 v7 ESM build 会 requireActual 崩掉,
//    这里用不 spread actual 的最小 mock 覆盖它 (同 StaffAIAnalysisScreen.test.tsx 的做法). ──
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: jest.fn(), goBack: jest.fn(), setOptions: jest.fn(), addListener: jest.fn() }),
  useRoute: () => ({
    params: { batchId: 501, batchNumber: 'B-20260817-501' },
  }),
}));

jest.mock('expo-image-manipulator', () => ({
  manipulateAsync: jest.fn(() => Promise.resolve({ uri: 'file://mock-manipulated.jpg' })),
  SaveFormat: { JPEG: 'jpeg' },
}));

// __mocks__/react-native.js (共享 jest 手写 mock, moduleNameMapper 全局指到它) 没有
// PanResponder —— YieldStepReportScreen 里的 TimeRangeSlider (时段报工块) 用它做拖拽滑轨。
// 只在本测试文件局部补上, 不改共享 mock 文件本身 (那是全仓所有 RN 测试都吃的文件,
// 改它风险/影响面大得多)。
// ⚠️ 两种更直接的写法都踩了坑, 记录一下别再走回头路:
//   1. jest.requireActual('react-native') —— moduleNameMapper 对 'react-native' 是宽泛正则
//      匹配, requireActual 绕不开它, 会一头撞进 react-native preset 自己的 jest/mockComponent.js
//      内部逻辑并崩溃 (「Cannot read properties of undefined (reading 'displayName')」)。
//   2. require('../../../../__mocks__/react-native.js') —— 这个路径本身仍然含 "react-native"
//      子串, 同一条正则又把它重新导向这个 jest.mock 工厂, 递归到 Maximum call stack exceeded。
// ⇒ 干脆不经过任何 require('react-native' 相关路径), 直接在本文件内联最小实现
//    (与共享 mock 文件同构, 但物理上是两份独立代码, 不会互相递归)。
jest.mock('react-native', () => {
  const ReactLib = require('react');
  const createMockComponent = (name: string) => {
    const component = ({ children, ...props }: any) => ReactLib.createElement(name, props, children);
    component.displayName = name;
    return component;
  };
  return {
    View: createMockComponent('View'),
    Text: createMockComponent('Text'),
    TouchableOpacity: createMockComponent('TouchableOpacity'),
    TouchableHighlight: createMockComponent('TouchableHighlight'),
    TouchableWithoutFeedback: createMockComponent('TouchableWithoutFeedback'),
    Pressable: createMockComponent('Pressable'),
    ScrollView: createMockComponent('ScrollView'),
    FlatList: createMockComponent('FlatList'),
    SectionList: createMockComponent('SectionList'),
    TextInput: createMockComponent('TextInput'),
    Image: createMockComponent('Image'),
    ImageBackground: createMockComponent('ImageBackground'),
    Modal: createMockComponent('Modal'),
    ActivityIndicator: createMockComponent('ActivityIndicator'),
    Switch: createMockComponent('Switch'),
    KeyboardAvoidingView: createMockComponent('KeyboardAvoidingView'),
    SafeAreaView: createMockComponent('SafeAreaView'),
    StatusBar: createMockComponent('StatusBar'),
    RefreshControl: createMockComponent('RefreshControl'),
    StyleSheet: {
      create: (styles: any) => styles,
      flatten: (style: any) => (Array.isArray(style) ? Object.assign({}, ...style) : style || {}),
      hairlineWidth: 0.5,
      absoluteFill: { position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 },
      absoluteFillObject: { position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 },
    },
    Platform: { OS: 'ios', select: (obj: any) => obj.ios || obj.default, Version: 14 },
    // TimeRangeSlider 用它算 SLIDER_WIDTH (模块加载期常量, 只要不是 0/NaN 就行).
    Dimensions: {
      get: () => ({ width: 375, height: 812, scale: 2, fontScale: 1 }),
      addEventListener: jest.fn(),
      removeEventListener: jest.fn(),
    },
    Alert: { alert: jest.fn() },
    Animated: {
      View: createMockComponent('Animated.View'),
      Text: createMockComponent('Animated.Text'),
      Value: jest.fn().mockImplementation(() => ({ setValue: jest.fn(), interpolate: jest.fn(() => ({ __getValue: () => 0 })) })),
      timing: jest.fn(() => ({ start: jest.fn((cb: any) => cb && cb({ finished: true })) })),
    },
    Keyboard: { dismiss: jest.fn(), addListener: jest.fn(() => ({ remove: jest.fn() })), removeListener: jest.fn() },
    NativeModules: {},
    NativeEventEmitter: jest.fn().mockImplementation(() => ({ addListener: jest.fn(), removeAllListeners: jest.fn() })),
    useColorScheme: jest.fn(() => 'light'),
    useWindowDimensions: jest.fn(() => ({ width: 375, height: 812, scale: 2, fontScale: 1 })),
    I18nManager: { isRTL: false },
    InteractionManager: { runAfterInteractions: jest.fn((cb: any) => { if (typeof cb === 'function') cb(); return { then: jest.fn(), cancel: jest.fn() }; }) },
    // 缺陷 A 真正要补的那个键 —— TimeRangeSlider 的拖拽手柄靠它, 渲染阶段只需要
    // create() 不抛错并给出 panHandlers 让 {...startPan.panHandlers} 能 spread。
    PanResponder: {
      create: () => ({ panHandlers: {} }),
    },
  };
});

// ── theme/index.ts 在这套 jest 环境下从 react-native-paper 拿 MD3LightTheme 会拿到 undefined
//    (与本缺陷无关的既有 infra 问题, ProcessTaskListScreen.test.tsx 同样绕过) —— 整个 theme 换成静态对象. ──
jest.mock('../../../theme', () => ({
  theme: {
    colors: {
      primary: '#1890ff',
      onPrimary: '#ffffff',
      secondaryContainer: '#E6F7FF',
      onSecondaryContainer: '#0050B3',
      error: '#ef4444',
      onError: '#ffffff',
      background: '#f5f5f5',
      surface: '#ffffff',
      surfaceVariant: '#f0f0f0',
      text: '#1F2937',
      textSecondary: '#6B7280',
      textTertiary: '#9CA3AF',
      outlineVariant: '#e5e7eb',
      outline: '#DCDFE6',
      onSurface: '#1F2937',
      onSurfaceVariant: '#6B7280',
      elevation: { level1: '#ffffff' },
    },
    custom: {
      borderRadius: { s: 4, m: 8, l: 12 },
      spacing: { xs: 4, s: 8, m: 12, l: 16, xl: 24 },
      shadows: {
        small: {},
        medium: {},
        large: {},
      },
    },
  },
}));

// NeoCard/NeoButton/ScreenWrapper 都从 react-native-paper (间接经 theme/index.ts 的
// MD3LightTheme) 取值, 而 react-native-paper 在这套自定义 react-native mock 下没法正常工作
// (它的 Button/Card 等内部实现依赖一堆我们没有理由在这里重建的 RN 原生行为)。这三个都是纯展示
// 容器组件, 不影响本缺陷要验证的文本/testID —— 直接换成最小 View/Text/TouchableOpacity 包装。
jest.mock('../../../components/ui/ScreenWrapper', () => {
  const { View } = require('react-native');
  return { ScreenWrapper: ({ children }: any) => <View>{children}</View> };
});
jest.mock('../../../components/ui/NeoCard', () => {
  const { View } = require('react-native');
  return { NeoCard: ({ children, style, testID }: any) => <View style={style} testID={testID}>{children}</View> };
});
jest.mock('../../../components/ui/NeoButton', () => {
  const { TouchableOpacity, Text } = require('react-native');
  return {
    NeoButton: ({ children, onPress, disabled, testID }: any) => (
      <TouchableOpacity onPress={onPress} disabled={disabled} testID={testID}>
        <Text>{children}</Text>
      </TouchableOpacity>
    ),
  };
});
// YieldStepReportScreen 直接 `import { TouchableRipple } from 'react-native-paper'` (SP3 二次加工
// 首道块用, 本测试场景走不到那个分支, 但模块顶层 import 仍会执行, 必须能解析成功).
jest.mock('react-native-paper', () => {
  const { TouchableOpacity } = require('react-native');
  return { TouchableRipple: ({ children, onPress, ...props }: any) => <TouchableOpacity onPress={onPress} {...props}>{children}</TouchableOpacity> };
});

jest.mock('../../../services/api/yieldReportApi', () => {
  const actual = jest.requireActual('../../../services/api/yieldReportApi');
  return {
    ...actual,
    yieldReportApi: {
      listWorkProcessTasks: jest.fn(),
      getYield: jest.fn(),
      getYieldLimits: jest.fn(),
      getOutputOptions: jest.fn(),
      submitReport: jest.fn(),
      recordMaterialInput: jest.fn(),
      settleDay: jest.fn(),
      listReports: jest.fn(),
      listAssignedWorkProcessTasks: jest.fn(),
      listWip: jest.fn(),
      postBatchReversal: jest.fn(),
      listAvailableWip: jest.fn(),
      uploadEvidence: jest.fn(),
    },
  };
});

jest.mock('../../../services/api/processingApiClient', () => ({
  processingApiClient: {
    getBatchById: jest.fn(),
    getBatches: jest.fn(),
  },
}));

jest.mock('../../../utils/errorHandler', () => ({
  handleError: jest.fn(),
}));

// ScreenWrapper 用它做顶层容器; jest 环境下这个包解不出真实 SafeAreaView (同 StaffAIAnalysisScreen.test.tsx 做法).
jest.mock('react-native-safe-area-context', () => {
  const { View } = require('react-native');
  return {
    SafeAreaView: ({ children, ...props }: any) => <View {...props}>{children}</View>,
    useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
    SafeAreaProvider: ({ children }: { children: React.ReactNode }) => children,
  };
});

import YieldStepReportScreen from '../../../screens/processing/YieldStepReportScreen';
import { yieldReportApi, WorkProcessTask, BatchYieldDTO, YieldLimitsDTO } from '../../../services/api/yieldReportApi';
import { processingApiClient } from '../../../services/api/processingApiClient';
import { useAuthStore } from '../../../store/authStore';
import type { FactoryUser } from '../../../types/auth';

const mockedYieldApi = yieldReportApi as jest.Mocked<typeof yieldReportApi>;
const mockedProcessingApi = processingApiClient as jest.Mocked<typeof processingApiClient>;

const mockSupervisor: FactoryUser = {
  id: 30,
  username: 'workshop_sup2',
  email: 'ws2@cretas.com',
  fullName: '李主管',
  isActive: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  userType: 'factory',
  factoryId: 'F006',
  factoryUser: {
    role: 'workshop_supervisor',
    factoryId: 'F006',
    permissions: [],
  },
};

/** 建一条已进入 IN_PRODUCTION 阶段的单任务批次 (reportedInput/inputUnit/outputUnit 可参数化). */
function buildTask(overrides: Partial<WorkProcessTask> = {}): WorkProcessTask {
  return {
    id: 9001,
    factoryId: 'F006',
    productionBatchId: 501,
    productWorkProcessId: 1,
    workProcessId: 'WP-PACK',
    productTypeId: 'PT-1',
    processOrder: 1,
    status: 'IN_PROGRESS',
    plannedQuantity: 99,
    plannedUnit: 'kg',
    processName: '定量包装',
    outputUnit: null,
    ...overrides,
  };
}

function buildYield(
  task: WorkProcessTask,
  stepOverrides: Partial<BatchYieldDTO['steps'][number]> = {},
): BatchYieldDTO {
  return {
    batchId: 501,
    batchNumber: 'B-20260817-501',
    firstStepInput: 99,
    lastStepOutput: null,
    firstStepInputUnit: 'kg',
    lastStepOutputUnit: null,
    cumulativeYieldRate: null,
    complete: false,
    totalWorkMinutes: null,
    totalWorkers: null,
    totalLaborCost: null,
    totalMaterialCost: null,
    totalCost: null,
    steps: [
      {
        workProcessTaskId: task.id,
        processOrder: task.processOrder,
        processName: task.processName ?? null,
        totalInput: 99,
        totalOutput: null,
        inputUnit: 'kg',
        outputUnit: task.outputUnit ?? null,
        yieldRate: null,
        unitComparable: null,
        carryover: null,
        yieldAlert: null,
        totalWorkMinutes: null,
        totalWorkers: null,
        laborCost: null,
        materialCost: null,
        stepCost: null,
        photos: null,
        laborSegments: null,
        processedQuantity: null,
        processedUnit: null,
        stageOutputQuantity: null,
        stageOutputUnit: null,
        segmentWasteQuantity: null,
        segmentWasteUnit: null,
        byproducts: null,
        wasteQuantity: null,
        sampleRetainQuantity: null,
        phase: 'IN_PRODUCTION',
        inputPhotos: null,
        outputPhotos: null,
        inputPhotoAnnotations: null,
        outputPhotoAnnotations: null,
        ...stepOverrides,
      },
    ],
  };
}

const emptyLimits: YieldLimitsDTO = {
  targetQuantity: null,
  standardYieldMax: null,
  unit: 'kg',
  alreadyReported: null,
  toleranceRate: null,
  maxAllowed: null,
  remaining: null,
  wipAvailable: null,
  wipAvailableUnit: null,
  sourceWipNo: null,
  message: '未配置标准出成上限',
};

/** 装配一次渲染所需的全部 API 桩, 并渲染 + 切到 OUTPUT 阶段 + 填产出量. */
async function renderAtOutputStep(params: {
  task: WorkProcessTask;
  stepInput: number;
  stepInputUnit: string;
  outputQty: string;
}) {
  const { task, stepInput, stepInputUnit, outputQty } = params;
  const yieldData = buildYield(task, { totalInput: stepInput, inputUnit: stepInputUnit });

  mockedYieldApi.listWorkProcessTasks.mockResolvedValue({
    success: true, code: 200, message: 'ok', data: [task],
  });
  mockedProcessingApi.getBatchById.mockResolvedValue({
    success: true, code: 200, message: 'ok',
    data: {
      id: 501,
      productType: '卤猪蹄',
      productTypeId: 'PT-1',
      batchNumber: 'B-20260817-501',
      targetQuantity: 99,
      status: 'IN_PROGRESS',
    } as any,
  });
  mockedYieldApi.getYield.mockResolvedValue({ success: true, code: 200, message: 'ok', data: yieldData });
  mockedYieldApi.getYieldLimits.mockResolvedValue({ success: true, code: 200, message: 'ok', data: emptyLimits });
  mockedYieldApi.getOutputOptions.mockResolvedValue({
    success: true, code: 200, message: 'ok', data: { items: [] },
  });

  render(<YieldStepReportScreen />);

  // 等首屏加载完成, 进入生产阶段 (② 过程报工 / SEGMENT).
  await waitFor(() => {
    expect(screen.getByTestId('yield-go-output-step-btn')).toBeTruthy();
  });

  // 切到 ③ 完工出成.
  fireEvent.press(screen.getByTestId('yield-go-output-step-btn'));
  await waitFor(() => {
    expect(screen.getByTestId('yield-output-qty')).toBeTruthy();
  });

  // 用大键盘直填产出量 (YieldQuantityInput 的 +/- 步进对大数字不现实).
  fireEvent.press(screen.getByTestId('yield-output-qty-tap-to-edit'));
  fireEvent.changeText(screen.getByTestId('yield-output-qty-modal-input'), outputQty);
  fireEvent.press(screen.getByTestId('yield-output-qty-modal-confirm'));
}

beforeEach(() => {
  jest.clearAllMocks();
  useAuthStore.setState({
    user: mockSupervisor,
    isAuthenticated: true,
    tokens: null,
    isLoading: false,
  });
});

describe('YieldStepReportScreen 完工出成 — 损耗自动计算展示 (缺陷 A)', () => {
  it('两侧单位不同时不做跨单位相减, 改为换算提示', async () => {
    const task = buildTask({ outputUnit: '盒' }); // unit=kg(plannedUnit), outUnit=盒
    await renderAtOutputStep({ task, stepInput: 99, stepInputUnit: 'kg', outputQty: '80' });

    await waitFor(() => {
      expect(screen.getByTestId('output-auto-waste')).toBeTruthy();
    });
    expect(
      screen.getByText('损耗需要换算: 投入 99kg, 产出 80盒 — 单位不同, 完工后由后端按规格换算'),
    ).toBeTruthy();
    // 阴性对照: 不许再出现旧版的跨单位假等式.
    expect(screen.queryByText(/= 19盒/)).toBeNull();
    // ⚠️ 2026-08-18: 原来这里断言的是「不出现 `损耗 (自动)`」, 而那句措辞现在**整个界面都没有了**
    //    ⇒ 这条阴性断言变成了恒真式(本仓形态 B′)。换成断言"差额那句话在这一支不出现" ——
    //    它在同单位分支才该出现, 跨单位分支必须走换算提示。
    expect(screen.queryByText(/未说明去向/)).toBeNull();
  });

  it('同单位但产出大于投入时不印假的 "=0", 改为核对提示', async () => {
    const task = buildTask({ outputUnit: null }); // outUnit 沿用 unit=kg
    await renderAtOutputStep({ task, stepInput: 10, stepInputUnit: 'kg', outputQty: '99' });

    await waitFor(() => {
      expect(screen.getByTestId('output-auto-waste')).toBeTruthy();
    });
    expect(
      screen.getByText('产出 99kg 大于投入 10kg — 请核对是不是填错了'),
    ).toBeTruthy();
    expect(screen.queryByText(/= 0kg/)).toBeNull();
  });

  it('同单位且投入≥产出时正常相减, 结果单位跟相减两侧一致', async () => {
    const task = buildTask({ outputUnit: null });
    await renderAtOutputStep({ task, stepInput: 99, stepInputUnit: 'kg', outputQty: '64' });

    await waitFor(() => {
      expect(screen.getByTestId('output-auto-waste')).toBeTruthy();
    });
    // 🔴 2026-08-18 改口径, 不是改断言取巧 —— 原断言钉的是字面 `损耗 (自动) = ... = 35kg`,
    //    而真机走查 + 查库确证: 后端**没有**把这个差额记成损耗
    //    (INPUT/OUTPUT 两条报工的 waste_quantity 都是 NULL), 它把缺口当成
    //    "未说明的物料平衡偏差" 并要求核对。界面叫它「损耗」= 界面在说后端没在记的事。
    //    ⇒ 断言从「字面」抬到「性质」: 三个数和单位都对, 且差额单位与相减两侧一致。
    // ⚠️ 不要 JSON.stringify(banner.props.children) —— React fiber 有循环引用, 直接 TypeError。
    //    用 RNTL 的文本匹配, 它会把子节点拍平成一个字符串再比。
    expect(screen.getByText(/投入 99kg − 产出 64kg = 35kg/)).toBeTruthy();
    // 阳性: 必须如实说明这笔差额还没被解释
    expect(screen.getByText(/未说明去向/)).toBeTruthy();
    // 阴性对照: 那个已被判定为误导的措辞不许回来
    expect(screen.queryByText(/损耗 \(自动\)/)).toBeNull();
  });
});
