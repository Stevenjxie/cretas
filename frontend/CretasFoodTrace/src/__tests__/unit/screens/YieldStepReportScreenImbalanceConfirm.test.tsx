/**
 * 完工出成确认弹窗 — 不平衡前置提示 (2026-08-17 F006 生产走查裁定).
 *
 * prod 实测: 工人在「完工出成」填一个大于投入的数也能提交 (投入 10kg / 产出 99kg) ——
 * 确认弹窗只说"完工后本道出成率锁定, 确认要完工出成吗?", 一个字没提这个数不对。提交
 * 之后才弹"物料平衡偏差 890%", 而那时出成率已锁定、99kg 已入半成品库, 工人自己撤不回来。
 *
 * 裁定: 保持不阻塞 (现场确实有合法的异常批次), 但把这个判断前移到确认弹窗里。本文件
 * 断言跑在产品真实入口 (渲染 YieldStepReportScreen 本体, 只桩 API/store), 不是直接调 helper。
 *
 * ⚠️ 单位不同时(如上道半成品 kg vs 本道产出 盒)前端拿不到换算率 —— 判断口径必须走
 * describeYieldImbalance 的三态 (unknown/balanced/unit-mismatch/imbalanced), 不许裸减法。
 * 相关文件刚被 PR #2786 改过 (完工出成块「损耗(自动)」的跨单位假等式已经修掉了),
 * 本文件只加确认弹窗这一层, 不碰那段既有逻辑。
 */
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

// ── React Navigation: 与 YieldStepReportScreenAutoWaste.test.tsx 同一套最小 mock ──
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

// 与 YieldStepReportScreenAutoWaste.test.tsx 同一份内联 react-native mock (物理上独立一份,
// 不经过任何 require('react-native' 相关路径, 避免 moduleNameMapper 递归 — 见那份文件的注释).
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
    PanResponder: {
      create: () => ({ panHandlers: {} }),
    },
  };
});

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

/** 装配一次渲染所需的全部 API 桩, 渲染 + 切到 OUTPUT 阶段 + 填产出量 + 按下"标记完工". */
async function renderAndTapComplete(params: {
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

  await waitFor(() => {
    expect(screen.getByTestId('yield-go-output-step-btn')).toBeTruthy();
  });

  fireEvent.press(screen.getByTestId('yield-go-output-step-btn'));
  await waitFor(() => {
    expect(screen.getByTestId('yield-output-qty')).toBeTruthy();
  });

  fireEvent.press(screen.getByTestId('yield-output-qty-tap-to-edit'));
  fireEvent.changeText(screen.getByTestId('yield-output-qty-modal-input'), outputQty);
  fireEvent.press(screen.getByTestId('yield-output-qty-modal-confirm'));

  await waitFor(() => {
    expect(screen.getByTestId('yield-submit-output-btn')).toBeTruthy();
  });
  fireEvent.press(screen.getByTestId('yield-submit-output-btn'));
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

describe('YieldStepReportScreen 完工出成确认弹窗 — 不平衡前置提示', () => {
  it('①不平衡时(同单位, 偏差>15%): 确认弹窗里出现投入/产出/偏差提示', async () => {
    const task = buildTask({ outputUnit: null }); // outUnit 沿用 unit=kg, 与投入同单位
    await renderAndTapComplete({ task, stepInput: 10, stepInputUnit: 'kg', outputQty: '99' });

    await waitFor(() => {
      expect(screen.getByTestId('app-dialog-btn-1')).toBeTruthy();
    });
    expect(screen.getByText('⚠️ 投入产出不平衡 — 完工出成确认')).toBeTruthy();
    expect(
      screen.getByText(
        '卤猪蹄 定量包装\n' +
        '投入 10kg, 产出 99kg, 偏差约 890%, 请核对数量是否填错。\n' +
        '完工后本道出成率锁定, 确认要完工出成吗? (若还要继续产出, 请改用"保存, 稍后继续")',
      ),
    ).toBeTruthy();

    // 按钮仍然可点 (不阻塞) —— 点确认要能触发提交.
    mockedYieldApi.submitReport.mockResolvedValue({
      success: true, code: 200, message: 'ok',
      data: { balanceWarning: undefined } as any,
    });
    fireEvent.press(screen.getByTestId('app-dialog-btn-1'));
    await waitFor(() => {
      expect(mockedYieldApi.submitReport).toHaveBeenCalled();
    });
  });

  it('②平衡时(同单位, 偏差≤15%): 阴性对照 — 确认弹窗不出现偏差提示', async () => {
    const task = buildTask({ outputUnit: null });
    await renderAndTapComplete({ task, stepInput: 99, stepInputUnit: 'kg', outputQty: '90' });

    await waitFor(() => {
      expect(screen.getByTestId('app-dialog-btn-1')).toBeTruthy();
    });
    // 标题保持原样, 不升级措辞.
    expect(screen.getByText('完工出成确认')).toBeTruthy();
    expect(
      screen.getByText(
        '卤猪蹄 定量包装\n' +
        '完工后本道出成率锁定, 确认要完工出成吗? (若还要继续产出, 请改用"保存, 稍后继续")',
      ),
    ).toBeTruthy();
    // 阴性对照: 偏差提示不出现 (与①同一份实现产出的字符串, 证明它不是恒真式).
    expect(screen.queryByText(/偏差约/)).toBeNull();
  });

  it('③单位不同时: 不出现假的减法/偏差结果, 只诚实说明由后端换算核对', async () => {
    const task = buildTask({ outputUnit: '盒' }); // outUnit=盒, 投入单位=kg
    await renderAndTapComplete({ task, stepInput: 99, stepInputUnit: 'kg', outputQty: '80' });

    await waitFor(() => {
      expect(screen.getByTestId('app-dialog-btn-1')).toBeTruthy();
    });
    expect(screen.getByText('完工出成确认')).toBeTruthy();
    expect(
      screen.getByText(
        '卤猪蹄 定量包装\n' +
        '投入单位 kg 与产出单位 盒 不同, 完工后由后端按规格换算核对是否平衡。\n' +
        '完工后本道出成率锁定, 确认要完工出成吗? (若还要继续产出, 请改用"保存, 稍后继续")',
      ),
    ).toBeTruthy();
    // 阴性对照: 不许出现跨单位裸算出来的假偏差/假损耗数字.
    expect(screen.queryByText(/偏差约/)).toBeNull();
    expect(screen.queryByText(/= *-?\d+(\.\d+)?盒/)).toBeNull();
  });
});
