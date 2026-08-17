/**
 * DynamicReportScreen 集成测试 (RNTL)
 * 测试动态报工页面的渲染、表单交互、提交、草稿保存
 */

import React from 'react';
import { render, fireEvent, waitFor, screen, within } from '@testing-library/react-native';
import { Alert } from 'react-native';
import DynamicReportScreen from '../../../screens/processing/DynamicReportScreen';
import { workReportingApiClient } from '../../../services/api/workReportingApiClient';
import { useDraftReportStore } from '../../../store/draftReportStore';
import { useAuthStore } from '../../../store/authStore';
import type { FactoryUser } from '../../../types/auth';

// Mock workReportingApiClient
jest.mock('../../../services/api/workReportingApiClient', () => ({
  workReportingApiClient: {
    getSchema: jest.fn(),
    submitReport: jest.fn(),
  },
}));

// Mock fieldVisibilityStore
jest.mock('../../../store/fieldVisibilityStore', () => ({
  useFieldVisibilityStore: jest.fn(() => ({
    isFieldVisible: jest.fn(() => true),
  })),
}));

// Mock formatters
jest.mock('../../../utils/formatters', () => ({
  formatDate: jest.fn((input: string) => input || '2026-02-13'),
  formatDateTime: jest.fn(() => '2026-02-13 10:00'),
  formatNumberWithCommas: jest.fn((v: number) => String(v)),
}));

// Override the navigation mock to support reportType param
const mockGoBack = jest.fn();
const mockNavigate = jest.fn();

jest.mock('@react-navigation/native', () => ({
  ...jest.requireActual('@react-navigation/native'),
  useNavigation: () => ({
    navigate: mockNavigate,
    goBack: mockGoBack,
    setOptions: jest.fn(),
    addListener: jest.fn(),
  }),
  useRoute: () => ({
    params: { reportType: 'PROGRESS' },
  }),
}));

const mockedApiClient = workReportingApiClient as jest.Mocked<typeof workReportingApiClient>;

describe('DynamicReportScreen', () => {
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

  const mockSchema = {
    success: true,
    code: 200,
    message: '成功',
    data: {
      id: 'tpl_1',
      name: '生产进度报工模板',
      entityType: 'PRODUCTION_PROGRESS_REPORT',
      schemaJson: JSON.stringify({
        fields: [
          { key: 'processCategory', label: '生产类目', type: 'text', required: true },
          { key: 'outputQuantity', label: '产品数量', type: 'integer', required: false },
        ],
      }),
      isActive: true,
      version: 1,
    },
  };

  beforeEach(() => {
    jest.clearAllMocks();
    useAuthStore.setState({
      user: mockFactoryUser,
      isAuthenticated: true,
      tokens: null,
      isLoading: false,
    });
    useDraftReportStore.getState().clearDrafts();
    mockedApiClient.getSchema.mockResolvedValue(mockSchema);
  });

  /**
   * 在 SearchableDropdown 里选一个值。
   *
   * ⚠️ 「生产类目/工序」和「商品名称」原来是自由文本 TextInput
   * (placeholder「输入生产类目或工序」/「输入商品名称」), 8dd41974a2 之后换成了
   * SearchableDropdown —— 这正是 fool-proof-design Rule 3「自由文本改约束选择」。
   * 屏幕是【有意】改的, 所以老断言里那两个 placeholder 找不到属于断言过期。
   *
   * 交互路径: 点选择器 -> 弹窗打开 -> 在「搜索...」里输入 -> 点「使用 "xxx"」。
   */
  async function pickFromDropdown(testID: string, value: string) {
    fireEvent.press(screen.getByTestId(testID));
    const search = await screen.findByPlaceholderText('搜索...');
    fireEvent.changeText(search, value);
    fireEvent.press(await screen.findByText(`使用 "${value}"`));
  }

  // ========== 渲染 ==========
  describe('渲染', () => {
    it('应该显示加载指示器，然后渲染表单', async () => {
      render(<DynamicReportScreen />);

      // Schema加载完成后应该看到标题
      await waitFor(() => {
        expect(screen.getByText('实时生产进度上报')).toBeTruthy();
      });
    });

    it('PROGRESS模式应该显示生产类目/工序字段', async () => {
      render(<DynamicReportScreen />);

      await waitFor(() => {
        expect(screen.getByText(/生产类目\/工序/)).toBeTruthy();
      });

      // 应该显示良品数和不良品数
      expect(screen.getByPlaceholderText('良品')).toBeTruthy();
      expect(screen.getByPlaceholderText('不良')).toBeTruthy();
    });

    it('应该显示报工日期（只读）', async () => {
      render(<DynamicReportScreen />);

      await waitFor(() => {
        expect(screen.getByText('报工日期')).toBeTruthy();
      });
    });

    it('应该显示备注字段', async () => {
      render(<DynamicReportScreen />);

      await waitFor(() => {
        expect(screen.getByPlaceholderText('备注信息（选填）')).toBeTruthy();
      });
    });

    it('应该显示提交按钮', async () => {
      render(<DynamicReportScreen />);

      await waitFor(() => {
        expect(screen.getByText('提交报工')).toBeTruthy();
      });
    });
  });

  // ========== 表单填写 ==========
  describe('表单填写', () => {
    it('应该能选择生产类目', async () => {
      render(<DynamicReportScreen />);

      const selector = await screen.findByTestId('process-category-dropdown');
      await pickFromDropdown('process-category-dropdown', '切割工序');

      // 选完之后选择器上显示的就是选中的值
      expect(within(selector).getByText('切割工序')).toBeTruthy();
    });

    it('应该能输入数量字段', async () => {
      render(<DynamicReportScreen />);

      await waitFor(() => {
        expect(screen.getByPlaceholderText('输入数量')).toBeTruthy();
      });

      fireEvent.changeText(screen.getByPlaceholderText('输入数量'), '100');
      fireEvent.changeText(screen.getByPlaceholderText('良品'), '95');
      fireEvent.changeText(screen.getByPlaceholderText('不良'), '5');

      expect(screen.getByPlaceholderText('输入数量').props.value).toBe('100');
      expect(screen.getByPlaceholderText('良品').props.value).toBe('95');
      expect(screen.getByPlaceholderText('不良').props.value).toBe('5');
    });

    it('应该能输入备注', async () => {
      render(<DynamicReportScreen />);

      await waitFor(() => {
        expect(screen.getByPlaceholderText('备注信息（选填）')).toBeTruthy();
      });

      fireEvent.changeText(screen.getByPlaceholderText('备注信息（选填）'), '今日产量正常');
      expect(screen.getByPlaceholderText('备注信息（选填）').props.value).toBe('今日产量正常');
    });
  });

  // ========== 提交 ==========
  describe('提交', () => {
    it('生产类目为空时应阻止提交并显示提示', async () => {
      render(<DynamicReportScreen />);

      await waitFor(() => {
        expect(screen.getByText('提交报工')).toBeTruthy();
      });

      // 不填写生产类目，直接提交
      fireEvent.press(screen.getByText('提交报工'));

      expect(Alert.alert).toHaveBeenCalledWith('提示', '请填写生产类目/工序');
      expect(mockedApiClient.submitReport).not.toHaveBeenCalled();
    });

    it('填写完成后应成功提交', async () => {
      mockedApiClient.submitReport.mockResolvedValueOnce({
        success: true,
        code: 200,
        message: '成功',
        data: {
          id: 1,
          factoryId: 'F001',
          workerId: 22,
          reportType: 'PROGRESS' as const,
          reportDate: '2026-02-13',
          status: 'SUBMITTED' as const,
          syncedToSmartbi: false,
          createdAt: '2026-02-13T10:00:00Z',
          updatedAt: '2026-02-13T10:00:00Z',
        },
      });

      render(<DynamicReportScreen />);

      await screen.findByTestId('process-category-dropdown');

      await pickFromDropdown('process-category-dropdown', '切割');
      fireEvent.changeText(screen.getByPlaceholderText('输入数量'), '100');
      fireEvent.changeText(screen.getByPlaceholderText('良品'), '95');
      fireEvent.changeText(screen.getByPlaceholderText('不良'), '5');

      fireEvent.press(screen.getByText('提交报工'));

      await waitFor(() => {
        expect(mockedApiClient.submitReport).toHaveBeenCalled();
      });

      // 成功后应该显示成功Alert
      await waitFor(() => {
        expect(Alert.alert).toHaveBeenCalledWith(
          '成功',
          '报工提交成功',
          expect.any(Array)
        );
      });
    });

    it('网络错误应保存草稿', async () => {
      // ⚠️ 必须是【真的网络错误形状】。存草稿这条路被 isNetworkError(error) 守着:
      //   code ∈ {ERR_NETWORK, ECONNABORTED, ETIMEDOUT}, 或者有 request 而没有 response。
      // 光 `new Error('网络错误')` 两条都不满足 —— 它走的是「非网络错误」分支,
      // 于是只弹 toast 不存草稿。读数长得像「自动存草稿没接上」, 其实是桩喂了一个
      // axios 在网络失败时【永远不会产出】的形状。
      const networkError = Object.assign(new Error('Network Error'), {
        code: 'ERR_NETWORK',
        request: {},
        isAxiosError: true,
      });
      mockedApiClient.submitReport.mockRejectedValueOnce(networkError);

      render(<DynamicReportScreen />);

      await screen.findByTestId('process-category-dropdown');

      await pickFromDropdown('process-category-dropdown', '包装');
      // 产量必须 > 0 才提交得出去(Rule 1: 提交前就把边界说清楚)。
      // 老断言只填了工序就点提交, 于是被前置校验拦下, 拿到的是「请输入大于 0 的产量」
      // 而不是网络失败 —— 那条路径根本没走到发请求, 更不会存草稿。
      fireEvent.changeText(screen.getByPlaceholderText('输入数量'), '50');
      fireEvent.press(screen.getByText('提交报工'));

      await waitFor(() => {
        expect(Alert.alert).toHaveBeenCalledWith('提交失败', expect.any(String));
      });

      // 应该保存了一个草稿
      const drafts = useDraftReportStore.getState().drafts;
      expect(drafts).toHaveLength(1);
    });
  });

  // ========== HOURS模式差异 ==========
  describe('HOURS模式表单差异', () => {
    beforeEach(() => {
      // 切换到HOURS模式
      jest.spyOn(require('@react-navigation/native'), 'useRoute').mockReturnValue({
        params: { reportType: 'HOURS' },
      });

      mockedApiClient.getSchema.mockResolvedValue({
        ...mockSchema,
        data: {
          ...mockSchema.data,
          entityType: 'PRODUCTION_HOURS_REPORT',
          name: '工时报工模板',
        },
      });
    });

    it('HOURS模式应该显示商品名称字段', async () => {
      render(<DynamicReportScreen />);

      // 用正则: SearchableDropdown 的 label 行是「商品名称 *」(必填星号是嵌套 Text),
      // 精确匹配 '商品名称' 匹配不上这种由多个子节点拼出来的文本。
      await waitFor(() => {
        expect(screen.getByText(/商品名称/)).toBeTruthy();
      });

      // 同上: 已由自由文本改成 SearchableDropdown, 断言改钉选择器本身
      expect(screen.getByTestId('product-name-dropdown')).toBeTruthy();
    });

    it('HOURS模式工时明细默认折叠, 展开后才分正式工/小时工/日结工', async () => {
      render(<DynamicReportScreen />);

      await waitFor(() => {
        expect(screen.getByText('工时明细')).toBeTruthy();
      });

      // 默认【折叠】: 只问总人数/总工时(给低技术素养用户的简化路径)。
      // 老断言直接找「正式工」, 那是展开态才有的 —— 断言过期, 不是字段丢了。
      expect(screen.getByText('总人数')).toBeTruthy();
      expect(screen.getByText('总工时')).toBeTruthy();
      expect(screen.queryByText('正式工')).toBeNull();

      fireEvent.press(screen.getByText('展开详情'));

      expect(screen.getByText('正式工')).toBeTruthy();
      expect(screen.getByText('小时工')).toBeTruthy();
      expect(screen.getByText('日结工')).toBeTruthy();
    });

    it('HOURS模式应该显示时间范围字段', async () => {
      render(<DynamicReportScreen />);

      await waitFor(() => {
        expect(screen.getByText('开始时间')).toBeTruthy();
      });

      expect(screen.getByText('结束时间')).toBeTruthy();
    });

    it('HOURS模式应该显示操作量字段', async () => {
      render(<DynamicReportScreen />);

      await waitFor(() => {
        expect(screen.getByText('操作量')).toBeTruthy();
      });

      expect(screen.getByPlaceholderText('输入操作量')).toBeTruthy();
    });

    it('HOURS模式不填商品名称应阻止提交', async () => {
      render(<DynamicReportScreen />);

      await waitFor(() => {
        expect(screen.getByText('提交报工')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('提交报工'));

      expect(Alert.alert).toHaveBeenCalledWith('提示', '请填写商品名称');
    });
  });
});
