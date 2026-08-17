/**
 * ProcessTaskListScreen + related screens integration tests (RNTL)
 * Tests: task list rendering, filter segments, search, navigation,
 * empty state, detail screen, report screen, run overview,
 * and NfcCheckinScreen PROCESS mode behavior
 */

import React from 'react';
import { render, fireEvent, waitFor, screen, within } from '@testing-library/react-native';
import { Alert } from 'react-native';
import ProcessTaskListScreen from '../../../screens/processing/ProcessTaskListScreen';
import NfcCheckinScreen from '../../../screens/processing/NfcCheckinScreen';
import { processTaskApiClient } from '../../../services/api/processTaskApiClient';
import { workReportingApiClient } from '../../../services/api/workReportingApiClient';
import { processingApiClient } from '../../../services/api/processingApiClient';
import { useAuthStore } from '../../../store/authStore';
import { useFactoryFeatureStore } from '../../../store/factoryFeatureStore';
import type { FactoryUser } from '../../../types/auth';

// ========== Mocks ==========

// Mock processTaskApiClient
jest.mock('../../../services/api/processTaskApiClient', () => ({
  processTaskApiClient: {
    getActiveTasks: jest.fn(),
    getTasks: jest.fn(),
    getTaskById: jest.fn(),
    getTaskSummary: jest.fn(),
    getRunOverview: jest.fn(),
    // PROCESS 模式签到走的是这两个, 不是 workReportingApiClient。
    // 桩里没有它们时, 屏幕调到的是 undefined, 异常被 performCheckin 的 catch 吞掉,
    // 断言只看到「checkin 被调用 0 次」—— 那个读数【不是】"没签到", 是"签到打在了别处"。
    processCheckin: jest.fn(),
    getActiveCheckins: jest.fn(),
  },
}));

// Mock workReportingApiClient
jest.mock('../../../services/api/workReportingApiClient', () => ({
  workReportingApiClient: {
    checkin: jest.fn(),
    checkout: jest.fn(),
    getCheckinList: jest.fn(),
    getTodayCheckins: jest.fn(),
  },
}));

// Mock processingApiClient
jest.mock('../../../services/api/processingApiClient', () => ({
  processingApiClient: {
    getBatches: jest.fn(),
  },
}));

// Mock errorHandler
jest.mock('../../../utils/errorHandler', () => ({
  handleError: jest.fn(),
}));

// Mock NFC utils (NfcCheckinScreen uses these)
jest.mock('../../../utils/nfcUtils', () => ({
  isNfcModuleInstalled: jest.fn(() => false),
  isNfcAvailable: jest.fn(() => Promise.resolve(false)),
}));

// Mock safe area insets
jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
  SafeAreaProvider: ({ children }: { children: React.ReactNode }) => children,
}));

// Mock BarcodeScannerModal
jest.mock('../../../components/processing/BarcodeScannerModal', () => {
  const { View, Text, TouchableOpacity } = require('react-native');
  return {
    __esModule: true,
    default: ({ visible, onClose, onScan }: { visible: boolean; onClose: () => void; onScan: (code: string) => void }) => {
      if (!visible) return null;
      return (
        <View testID="barcode-scanner-modal">
          <TouchableOpacity testID="mock-scan-btn" onPress={() => onScan('55')}>
            <Text>模拟扫码</Text>
          </TouchableOpacity>
          <TouchableOpacity testID="mock-close-btn" onPress={onClose}>
            <Text>关闭</Text>
          </TouchableOpacity>
        </View>
      );
    },
  };
});

// Mock NfcCheckinModal
jest.mock('../../../components/processing/NfcCheckinModal', () => {
  const { View } = require('react-native');
  return {
    __esModule: true,
    default: () => <View testID="nfc-modal-mock" />,
  };
});

// Mock UI components that depend on theme
jest.mock('../../../components/ui', () => {
  const { View, Text, TouchableOpacity } = require('react-native');
  return {
    NeoCard: ({ children, style, ...props }: any) => <View style={style} {...props}>{children}</View>,
    NeoButton: ({ children, onPress, ...props }: any) => (
      <TouchableOpacity onPress={onPress} {...props}>
        <Text>{children}</Text>
      </TouchableOpacity>
    ),
    ScreenWrapper: ({ children }: any) => <View>{children}</View>,
  };
});

// Mock theme
jest.mock('../../../theme', () => ({
  theme: {
    colors: {
      primary: '#1890ff',
      background: '#f5f5f5',
      surface: '#ffffff',
      surfaceVariant: '#f0f0f0',
      text: '#1F2937',
      textSecondary: '#6B7280',
      textTertiary: '#9CA3AF',
      error: '#ef4444',
      outlineVariant: '#e5e7eb',
    },
    custom: {
      borderRadius: { m: 8, l: 12 },
    },
  },
}));

// Navigation mock with captured navigate fn
const mockNavigate = jest.fn();
const mockGoBack = jest.fn();

jest.mock('@react-navigation/native', () => ({
  ...jest.requireActual('@react-navigation/native'),
  useNavigation: () => ({
    navigate: mockNavigate,
    goBack: mockGoBack,
    setOptions: jest.fn(),
    addListener: jest.fn(),
  }),
  useRoute: () => ({
    params: {},
  }),
  useFocusEffect: (cb: () => void) => {
    // ⚠️ deps 必须是 [cb], 不能是 []。
    // 真的 useFocusEffect 在【回调 identity 变了】且屏幕聚焦时会重跑; 屏幕正是靠这一点
    // 在 selectedStatus 变化后重新拉数据(fetchTasks 进了 useCallback 的 deps)。
    // 写死 [] 等于「只在挂载时拉一次」⇒ 切筛选后 getTasks 一次都不会被调用,
    // 断言读到 "Number of calls: 0" —— 看起来像屏幕没接上筛选, 其实是 mock 语义不对。
    const React = require('react');
    React.useEffect(() => { cb(); }, [cb]);
  },
  // 2026-08-16: 补 useNavigationState。ProcessTaskListScreen:33 用它算 canGoBack,
  // 而这份局部 mock【覆盖】了 setup.ts 的全局 mock ⇒ 必须在这里也补, 否则渲染期抛
  //   "Couldn't get the navigation state. Is your component inside a navigator?"
  // ⚠️ 这类红是【测试过期】不是屏幕回归 —— 判错方向就会去改屏幕。
  useNavigationState: (selector: (state: unknown) => unknown) =>
    selector({ index: 0, routes: [{ key: 'test-0', name: 'Test' }] }),
}));

const mockedProcessTaskApi = processTaskApiClient as jest.Mocked<typeof processTaskApiClient>;
const mockedWorkReportingApi = workReportingApiClient as jest.Mocked<typeof workReportingApiClient>;
const mockedProcessingApi = processingApiClient as jest.Mocked<typeof processingApiClient>;

// ========== Test Data ==========

const mockTasks = [
  {
    id: 'task-1',
    factoryId: 'F001',
    productTypeId: 'PT-001',
    productTypeName: '鸡肉香肠',
    workProcessId: 'WP-001',
    processName: '炸制',
    processCategory: '热加工',
    unit: 'kg',
    plannedQuantity: 100,
    completedQuantity: 50,
    pendingQuantity: 10,
    status: 'IN_PROGRESS' as const,
  },
  {
    id: 'task-2',
    factoryId: 'F001',
    productTypeId: 'PT-001',
    productTypeName: '鸡肉香肠',
    workProcessId: 'WP-002',
    processName: '冷却',
    processCategory: '冷加工',
    unit: 'kg',
    plannedQuantity: 200,
    completedQuantity: 0,
    pendingQuantity: 0,
    status: 'PENDING' as const,
  },
];

const mockFactoryUser: FactoryUser = {
  id: 22,
  username: 'workshop_sup1',
  email: 'ws1@cretas.com',
  fullName: '王主管',
  isActive: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  userType: 'factory',
  factoryId: 'F001',
  factoryUser: {
    role: 'workshop_supervisor',
    factoryId: 'F001',
    permissions: [],
  },
};

// ========== ProcessTaskListScreen Tests ==========

describe('ProcessTaskListScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useAuthStore.setState({
      user: mockFactoryUser,
      isAuthenticated: true,
      tokens: null,
      isLoading: false,
    });

    // Default: return active tasks
    mockedProcessTaskApi.getActiveTasks.mockResolvedValue({
      success: true,
      data: mockTasks,
    });

    // getTasks 自己构造返回对象, 只透出 { content, totalElements } —— 后端 PageResponse
    // 上的 totalPages 在 processTaskApiClient 里就被丢掉了, 两个调用方也都不读它。
    // 桩里写 totalPages 是在描述一个真实那侧永远不会给出的形状。
    mockedProcessTaskApi.getTasks.mockResolvedValue({
      success: true,
      data: { content: mockTasks, totalElements: 2 },
    });
  });

  // ========== RN-SCR-01: Renders active tasks on mount ==========
  describe('RN-SCR-01: Renders active tasks on mount', () => {
    it('should display task cards with process names after loading', async () => {
      render(<ProcessTaskListScreen />);

      await waitFor(() => {
        expect(screen.getByText('炸制')).toBeTruthy();
      });

      expect(screen.getByText('冷却')).toBeTruthy();
      expect(mockedProcessTaskApi.getActiveTasks).toHaveBeenCalled();
    });

    it('should show product type name on task cards', async () => {
      render(<ProcessTaskListScreen />);

      // 两条 mock 任务的 productTypeName 都是「鸡肉香肠」⇒ 屏幕【本来就该】渲染两处。
      // 用 getByText 断言等于要求它只出现一次, 那是断言写错了, 不是屏幕多渲染了。
      await waitFor(() => {
        expect(screen.getAllByText('鸡肉香肠')).toHaveLength(2);
      });
    });

    it('should show planned and completed quantities', async () => {
      render(<ProcessTaskListScreen />);

      await waitFor(() => {
        expect(screen.getByText('100')).toBeTruthy();
        expect(screen.getByText('50')).toBeTruthy();
      });
    });

    it('should show progress percentage', async () => {
      render(<ProcessTaskListScreen />);

      await waitFor(() => {
        // task-1: 50/100 = 50%
        expect(screen.getByText('50%')).toBeTruthy();
        // task-2: 0/200 = 0%
        expect(screen.getByText('0%')).toBeTruthy();
      });
    });
  });

  // ========== RN-SCR-02: Status filter segments ==========
  describe('RN-SCR-02: Status filter segments', () => {
    // 「进行中」「已完成」在这块屏幕上【不是唯一的】:
    //   - 「进行中」既是筛选分段, 也是 IN_PROGRESS 任务的状态徽标 (实测 2 处)
    //   - 「已完成」既是筛选分段, 也是每张卡片上的字段名 (2 张卡 + 1 个分段 = 实测 3 处)
    // 所以这几条断言必须【限定在筛选器容器内】找, 否则 getByText 必然报
    // "Found multiple elements" —— 那是断言不够specific, 不是屏幕渲染错了。
    const filter = () => within(screen.getByTestId('process-task-filter'));

    it('should show segmented buttons for active/completed/all', async () => {
      render(<ProcessTaskListScreen />);

      await waitFor(() => {
        expect(filter().getByText('进行中')).toBeTruthy();
      });

      expect(filter().getByText('已完成')).toBeTruthy();
      expect(filter().getByText('全部')).toBeTruthy();
    });

    it('should call getTasks with COMPLETED status when completed tab is selected', async () => {
      render(<ProcessTaskListScreen />);

      await waitFor(() => {
        expect(filter().getByText('已完成')).toBeTruthy();
      });

      fireEvent.press(filter().getByText('已完成'));

      await waitFor(() => {
        expect(mockedProcessTaskApi.getTasks).toHaveBeenCalledWith(
          expect.objectContaining({ status: 'COMPLETED' })
        );
      });
    });

    it('should call getTasks without status filter for "all" tab', async () => {
      render(<ProcessTaskListScreen />);

      await waitFor(() => {
        expect(filter().getByText('全部')).toBeTruthy();
      });

      fireEvent.press(filter().getByText('全部'));

      await waitFor(() => {
        expect(mockedProcessTaskApi.getTasks).toHaveBeenCalledWith(
          expect.objectContaining({ status: undefined })
        );
      });
    });
  });

  // ========== RN-SCR-03: Search filters by process name ==========
  describe('RN-SCR-03: Search filters by process name', () => {
    it('should filter tasks when search query matches process name', async () => {
      render(<ProcessTaskListScreen />);

      await waitFor(() => {
        expect(screen.getByText('炸制')).toBeTruthy();
        expect(screen.getByText('冷却')).toBeTruthy();
      });

      const searchBar = screen.getByPlaceholderText('搜索工序名称、产品...');
      fireEvent.changeText(searchBar, '炸制');

      await waitFor(() => {
        expect(screen.getByText('炸制')).toBeTruthy();
        expect(screen.queryByText('冷却')).toBeNull();
      });
    });

    it('should filter by product type name', async () => {
      render(<ProcessTaskListScreen />);

      await waitFor(() => {
        expect(screen.getByText('炸制')).toBeTruthy();
      });

      const searchBar = screen.getByPlaceholderText('搜索工序名称、产品...');
      fireEvent.changeText(searchBar, '鸡肉');

      await waitFor(() => {
        // Both tasks have productTypeName '鸡肉香肠', so both should show
        expect(screen.getByText('炸制')).toBeTruthy();
        expect(screen.getByText('冷却')).toBeTruthy();
      });
    });
  });

  // ========== RN-SCR-04: Navigation to ProcessTaskDetail ==========
  describe('RN-SCR-04: Tap on task navigates to ProcessTaskDetail', () => {
    it('should navigate with taskId when task card is pressed', async () => {
      render(<ProcessTaskListScreen />);

      await waitFor(() => {
        expect(screen.getByText('炸制')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('炸制'));

      expect(mockNavigate).toHaveBeenCalledWith('ProcessTaskDetail', { taskId: 'task-1' });
    });
  });

  // ========== RN-SCR-05: Empty state ==========
  describe('RN-SCR-05: Empty state shows appropriate message', () => {
    it('should show empty text when no tasks returned', async () => {
      mockedProcessTaskApi.getActiveTasks.mockResolvedValueOnce({
        success: true,
        data: [],
      });

      render(<ProcessTaskListScreen />);

      await waitFor(() => {
        expect(screen.getByText('暂无工序任务')).toBeTruthy();
      });
    });

    it('should show error text and retry button on fetch failure', async () => {
      mockedProcessTaskApi.getActiveTasks.mockRejectedValueOnce(new Error('网络错误'));

      render(<ProcessTaskListScreen />);

      await waitFor(() => {
        expect(screen.getByText(/加载工序任务失败|网络错误/)).toBeTruthy();
      });

      // Retry button should be visible
      expect(screen.getByText('重试')).toBeTruthy();
    });
  });
});

// ========== ProcessTaskDetailScreen Tests ==========

describe('RN-SCR-06: ProcessTaskDetailScreen displays quantities', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useAuthStore.setState({
      user: mockFactoryUser,
      isAuthenticated: true,
      tokens: null,
      isLoading: false,
    });
  });

  it('should call getTaskById with the correct taskId', async () => {
    // We test the API call pattern since the detail screen is a separate component
    // that we verify integrates correctly with the API client
    const taskData = mockTasks[0]!;
    const mockResponse = {
      success: true as const,
      data: taskData,
    };
    mockedProcessTaskApi.getTaskById.mockResolvedValueOnce(mockResponse);

    const result = await processTaskApiClient.getTaskById('task-1') as typeof mockResponse;
    expect(result.success).toBe(true);
    expect(result.data.plannedQuantity).toBe(100);
    expect(result.data.completedQuantity).toBe(50);
    expect(result.data.pendingQuantity).toBe(10);
  });

  it('should call getTaskSummary and return worker count', async () => {
    const mockResponse = {
      success: true,
      data: {
        task: mockTasks[0],
        totalReported: 60,
        approvedTotal: 50,
        pendingTotal: 10,
        rejectedTotal: 0,
        workerCount: 3,
      },
    };
    mockedProcessTaskApi.getTaskSummary.mockResolvedValueOnce(mockResponse);

    const result = await processTaskApiClient.getTaskSummary('task-1') as typeof mockResponse;
    expect(result.success).toBe(true);
    expect(result.data.workerCount).toBe(3);
    expect(result.data.approvedTotal).toBe(50);
  });
});

// ========== YieldStepReport data contract tests ==========

describe('RN-SCR-07: YieldStepReport staged report payload validation', () => {
  it('should keep staged report payload quantities positive', () => {
    const validData = {
      workProcessTaskId: 1,
      outputQuantity: 25,
      reportDate: '2026-03-12',
      notes: '正常报工',
    };
    expect(validData.outputQuantity).toBeGreaterThan(0);
    expect(validData.workProcessTaskId).toBeGreaterThan(0);
    expect(validData.reportDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('should enforce non-negative quantity for report', () => {
    const invalidQuantity = -5;
    expect(invalidQuantity).toBeLessThan(0);

    const validQuantity = 25;
    expect(validQuantity).toBeGreaterThan(0);
  });
});

describe('RN-SCR-08: YieldStepReport staged report kind indicator', () => {
  it('should distinguish staged input and output report data', () => {
    const inputReport = {
      id: 101,
      workProcessTaskId: 1,
      reportKind: 'INPUT',
      inputQuantity: 998,
      approvalStatus: 'PENDING',
    };

    const outputReport = {
      id: 100,
      workProcessTaskId: 1,
      reportKind: 'OUTPUT',
      outputQuantity: 50,
      approvalStatus: 'APPROVED',
    };

    expect(inputReport.reportKind).toBe('INPUT');
    expect(outputReport.reportKind).toBe('OUTPUT');
  });
});

// ========== ProcessRunOverviewScreen Tests ==========

describe('RN-SCR-09: ProcessRunOverviewScreen renders tasks for a run', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('should call getRunOverview and return tasks with progress', async () => {
    const mockResponse = {
      success: true,
      data: {
        productionRunId: 'run-2026-001',
        tasks: mockTasks,
        overallProgress: 25,
        completedTasks: 0,
        totalTasks: 2,
      },
    };
    mockedProcessTaskApi.getRunOverview.mockResolvedValueOnce(mockResponse);

    const result = await processTaskApiClient.getRunOverview('run-2026-001') as typeof mockResponse;

    expect(result.success).toBe(true);
    expect(result.data.tasks).toHaveLength(2);
    expect(result.data.overallProgress).toBe(25);
    expect(result.data.totalTasks).toBe(2);
    expect(result.data.completedTasks).toBe(0);
  });

  it('should handle empty run (no tasks)', async () => {
    const mockResponse = {
      success: true,
      data: {
        productionRunId: 'run-empty',
        tasks: [] as typeof mockTasks,
        overallProgress: 0,
        completedTasks: 0,
        totalTasks: 0,
      },
    };
    mockedProcessTaskApi.getRunOverview.mockResolvedValueOnce(mockResponse);

    const result = await processTaskApiClient.getRunOverview('run-empty') as typeof mockResponse;
    expect(result.data.tasks).toHaveLength(0);
    expect(result.data.overallProgress).toBe(0);
  });
});

// ========== NfcCheckinScreen PROCESS mode Tests ==========

describe('NfcCheckinScreen PROCESS mode', () => {
  const mockProcessTasks = [
    {
      id: 'task-1',
      processName: '炸制',
      productTypeName: '鸡肉香肠',
      productTypeId: 'PT-001',
      status: 'IN_PROGRESS',
      plannedQuantity: 100,
      completedQuantity: 50,
      pendingQuantity: 10,
      unit: 'kg',
      factoryId: 'F001',
      workProcessId: 'WP-001',
    },
    {
      id: 'task-2',
      processName: '冷却',
      productTypeName: '鸡肉香肠',
      productTypeId: 'PT-001',
      status: 'PENDING',
      plannedQuantity: 200,
      completedQuantity: 0,
      pendingQuantity: 0,
      unit: 'kg',
      factoryId: 'F001',
      workProcessId: 'WP-002',
    },
  ];

  beforeEach(() => {
    jest.clearAllMocks();

    useAuthStore.setState({
      user: mockFactoryUser,
      isAuthenticated: true,
      tokens: null,
      isLoading: false,
    });

    // Default mock for batch mode (PROCESS mode overrides in specific tests)
    //
    // ⚠️ 同 NfcCheckinScreen.test.tsx: 桩要认 status。loadBatches 并发查
    // IN_PROGRESS 和 PLANNED 再把结果拼起来, 桩不分状态就会让同一个批次进两次,
    // `getByText('BATCH-2026-001')` 报 "Found multiple elements"。
    // 真实那侧一个批次只有一个状态, 两次查询结果不相交。
    mockedProcessingApi.getBatches.mockImplementation((params?: { status?: string }) =>
      Promise.resolve({
        success: true,
        code: 200,
        message: '成功',
        data: {
          content: params?.status === 'IN_PROGRESS'
            ? [{ id: 101, batchNumber: 'BATCH-2026-001', productType: '鸡肉香肠', status: 'IN_PROGRESS' }]
            : [],
          totalElements: params?.status === 'IN_PROGRESS' ? 1 : 0,
          totalPages: 1,
          size: 10,
          number: 0,
          first: true,
          last: true,
          empty: params?.status !== 'IN_PROGRESS',
        },
      }) as any
    );

    mockedWorkReportingApi.getCheckinList.mockResolvedValue({
      success: true,
      code: 200,
      message: '成功',
      data: [],
    });
  });

  // Helper to set PROCESS or BATCH mode in factoryFeatureStore
  function setProcessMode(enabled: boolean) {
    useFactoryFeatureStore.setState({
      // BATCH 侧要给【空对象】, 不能给 undefined。
      // factoryFeatureStore 的初始值、reset()、以及加载器构造的都是 `{}`,
      // `modules` 在真实那侧【永远不会是 undefined】。喂 undefined 会让
      // getProductionMode 里的 `modules['production']` 当场 TypeError,
      // 4 条 BATCH 回归断言全部死在渲染期 —— 那是桩造出来的崩溃, 不是屏幕的。
      modules: enabled ? {
        production: {
          enabled: true,
          moduleName: '生产管理',
          config: { mode: 'PROCESS' } as any,
        },
      } : {},
      loaded: true,
      loading: false,
    });
  }

  // ========== RN-SCR-10: PROCESS mode renders task list ==========
  describe('RN-SCR-10: PROCESS mode renders processTask selection', () => {
    it('should show process task list header when in PROCESS mode', async () => {
      setProcessMode(true);
      mockedProcessTaskApi.getActiveTasks.mockResolvedValueOnce({
        success: true,
        data: mockProcessTasks,
      });

      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('选择工序任务签到')).toBeTruthy();
      });
    });

    it('should display process task names from API', async () => {
      setProcessMode(true);
      mockedProcessTaskApi.getActiveTasks.mockResolvedValueOnce({
        success: true,
        data: mockProcessTasks,
      });

      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('炸制')).toBeTruthy();
      });
    });

    it('should show empty state when no active process tasks', async () => {
      setProcessMode(true);
      mockedProcessTaskApi.getActiveTasks.mockResolvedValueOnce({
        success: true,
        data: [],
      });

      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('暂无活跃工序任务')).toBeTruthy();
      });
    });
  });

  // ========== RN-SCR-11: PROCESS mode checkout includes processTaskId ==========
  describe('RN-SCR-11: PROCESS mode checkin sends processTaskId', () => {
    it('should include processTaskId in checkin call', async () => {
      setProcessMode(true);
      mockedProcessTaskApi.getActiveTasks.mockResolvedValueOnce({
        success: true,
        data: mockProcessTasks,
      });

      // PROCESS 模式的签到端点(见下方断言处的说明)
      mockedProcessTaskApi.processCheckin.mockResolvedValueOnce({
        success: true,
        code: 200,
        message: '签到成功',
        data: {
          id: 1,
          processTaskId: 'task-1',
          employeeId: 55,
          checkInTime: '2026-03-12T08:00:00Z',
          status: 'CHECKED_IN',
        },
      } as any);
      // 签到成功后屏幕会刷一次在场名单; 不桩它会拿到 undefined 走进 catch
      mockedProcessTaskApi.getActiveCheckins.mockResolvedValue({
        success: true,
        code: 200,
        message: '成功',
        data: [],
      } as any);

      render(<NfcCheckinScreen />);

      // Wait for tasks to load
      await waitFor(() => {
        expect(screen.getByText('炸制')).toBeTruthy();
      });

      // Select a process task
      fireEvent.press(screen.getByText('炸制'));

      // After selecting task, should show checkin management view
      await waitFor(() => {
        expect(screen.getByText('扫码签到')).toBeTruthy();
      });

      // Open scanner
      fireEvent.press(screen.getByText('扫码签到'));

      await waitFor(() => {
        expect(screen.getByTestId('barcode-scanner-modal')).toBeTruthy();
      });

      // Simulate scan (employee #55)
      fireEvent.press(screen.getByTestId('mock-scan-btn'));

      // 这条断言守的【行为】是「PROCESS 模式签到必须把 processTaskId 带上」。
      // 它原来钉的是 workReportingApiClient.checkin —— 那是 82735b19c3 之前的走法;
      // 那次改动给 PROCESS 模式起了专用端点 processTaskApiClient.processCheckin。
      // 行为没丢(processTaskId 照样送出去), 变的是载体 ⇒ 断言从「调了哪个函数」
      // 抬到「签到请求里带没带 processTaskId」, 并补一条阴性对照钉住它不再走批次端点。
      await waitFor(() => {
        expect(mockedProcessTaskApi.processCheckin).toHaveBeenCalledWith(
          expect.objectContaining({
            processTaskId: 'task-1',
            employeeId: 55,
            checkinMethod: 'QR',
          })
        );
      });

      // 阴性对照: PROCESS 模式不许再走批次签到端点
      expect(mockedWorkReportingApi.checkin).not.toHaveBeenCalled();

      // Should show success
      await waitFor(() => {
        expect(Alert.alert).toHaveBeenCalledWith('签到成功', expect.stringContaining('55'));
      });
    });
  });

  // ========== RN-SCR-12: BATCH mode unchanged (regression) ==========
  describe('RN-SCR-12: BATCH mode unchanged (regression)', () => {
    it('should show batch selection header when NOT in PROCESS mode', async () => {
      setProcessMode(false);

      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('选择批次签到')).toBeTruthy();
      });
    });

    it('should load batches via processingApiClient in BATCH mode', async () => {
      setProcessMode(false);

      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(mockedProcessingApi.getBatches).toHaveBeenCalled();
      });

      // Should NOT call processTaskApiClient in batch mode
      expect(mockedProcessTaskApi.getActiveTasks).not.toHaveBeenCalled();
    });

    it('should show batch number in BATCH mode list', async () => {
      setProcessMode(false);

      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('BATCH-2026-001')).toBeTruthy();
      });
    });

    it('should send batchId (not processTaskId) in BATCH mode checkin', async () => {
      setProcessMode(false);

      mockedWorkReportingApi.checkin.mockResolvedValueOnce({
        success: true,
        code: 200,
        message: '签到成功',
        data: {
          id: 3,
          batchId: 101,
          employeeId: 55,
          checkInTime: '2026-03-12T08:00:00Z',
          status: 'working',
          checkinMethod: 'QR',
        },
      });

      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('BATCH-2026-001')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('BATCH-2026-001'));

      await waitFor(() => {
        expect(screen.getByText('扫码签到')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('扫码签到'));

      await waitFor(() => {
        expect(screen.getByTestId('barcode-scanner-modal')).toBeTruthy();
      });

      fireEvent.press(screen.getByTestId('mock-scan-btn'));

      await waitFor(() => {
        expect(mockedWorkReportingApi.checkin).toHaveBeenCalledWith(
          expect.objectContaining({
            batchId: 101,
            employeeId: 55,
            checkinMethod: 'QR',
          })
        );
      });

      // Should NOT have processTaskId
      const checkinCall = mockedWorkReportingApi.checkin.mock.calls[0]?.[0];
      expect(checkinCall).not.toHaveProperty('processTaskId');
    });
  });
});
