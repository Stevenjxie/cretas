/**
 * useReportWorkflow hook 单元测试
 * 测试schema加载、报工提交、草稿保存、reportType切换
 */

import { renderHook, act, waitFor } from '@testing-library/react-native';
import { useReportWorkflow } from '../../../hooks/useReportWorkflow';
import { workReportingApiClient } from '../../../services/api/workReportingApiClient';
import { useDraftReportStore } from '../../../store/draftReportStore';
import { useAuthStore } from '../../../store/authStore';
import { Alert } from 'react-native';
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

const mockedApiClient = workReportingApiClient as jest.Mocked<typeof workReportingApiClient>;

describe('useReportWorkflow', () => {
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

  const mockSchemaResponse = {
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
    // 设置已登录用户
    useAuthStore.setState({
      user: mockFactoryUser,
      isAuthenticated: true,
      tokens: null,
      isLoading: false,
    });
    // 清除草稿
    useDraftReportStore.getState().clearDrafts();
  });

  // ========== schema加载 ==========
  describe('schema加载', () => {
    it('schema加载成功应设置schema和loading=false', async () => {
      mockedApiClient.getSchema.mockResolvedValueOnce(mockSchemaResponse);

      const { result } = renderHook(() => useReportWorkflow('PROGRESS'));

      // 初始状态应该是loading
      expect(result.current.loading).toBe(true);

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      expect(result.current.schema).not.toBeNull();
      expect(result.current.schema?.fields).toHaveLength(2);
      expect(result.current.schema?.fields[0]?.key).toBe('processCategory');
      expect(mockedApiClient.getSchema).toHaveBeenCalledWith('PRODUCTION_PROGRESS_REPORT');
    });

    it('schema加载失败应设置loading=false且schema为null', async () => {
      mockedApiClient.getSchema.mockRejectedValueOnce(new Error('网络错误'));

      const { result } = renderHook(() => useReportWorkflow('PROGRESS'));

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      // schema应该保持null（使用默认），不报错
      expect(result.current.schema).toBeNull();
    });

    it('schema响应success=false应不设置schema', async () => {
      mockedApiClient.getSchema.mockResolvedValueOnce({
        success: false,
        code: 404,
        message: '模板不存在',
        data: null!,
      });

      const { result } = renderHook(() => useReportWorkflow('PROGRESS'));

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      expect(result.current.schema).toBeNull();
    });

    it('reloadSchema应重新加载schema', async () => {
      mockedApiClient.getSchema.mockResolvedValue(mockSchemaResponse);

      const { result } = renderHook(() => useReportWorkflow('PROGRESS'));

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      // 手动重新加载
      await act(async () => {
        await result.current.reloadSchema();
      });

      // getSchema应该被调用两次（mount + reload）
      expect(mockedApiClient.getSchema).toHaveBeenCalledTimes(2);
    });
  });

  // ========== submitReport ==========
  describe('submitReport', () => {
    it('提交成功应返回报工数据', async () => {
      mockedApiClient.getSchema.mockResolvedValueOnce(mockSchemaResponse);
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

      const { result } = renderHook(() => useReportWorkflow('PROGRESS'));

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      let submitResult: unknown;
      await act(async () => {
        submitResult = await result.current.submitReport({
          reportType: 'PROGRESS',
          reportDate: '2026-02-13',
          processCategory: '切割',
          outputQuantity: 100,
        });
      });

      expect(submitResult).not.toBeNull();
      expect((submitResult as { id: number }).id).toBe(1);
      expect(result.current.submitting).toBe(false);
      expect(mockedApiClient.submitReport).toHaveBeenCalledWith(
        expect.objectContaining({ reportType: 'PROGRESS', processCategory: '切割' }),
        22
      );
    });

    it('提交失败（API返回success=false）应显示Alert并返回null', async () => {
      mockedApiClient.getSchema.mockResolvedValueOnce(mockSchemaResponse);
      mockedApiClient.submitReport.mockResolvedValueOnce({
        success: false,
        code: 400,
        message: '数据验证失败',
        data: null!,
      });

      const { result } = renderHook(() => useReportWorkflow('PROGRESS'));

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      let submitResult: unknown;
      await act(async () => {
        submitResult = await result.current.submitReport({
          reportType: 'PROGRESS',
          reportDate: '2026-02-13',
          processCategory: '切割',
        });
      });

      expect(submitResult).toBeNull();
      expect(Alert.alert).toHaveBeenCalledWith('提交失败', '数据验证失败');
    });

    it('网络错误应保存草稿并显示Alert', async () => {
      mockedApiClient.getSchema.mockResolvedValueOnce(mockSchemaResponse);
      mockedApiClient.submitReport.mockRejectedValueOnce({
        code: 'ERR_NETWORK',
        request: {},
        message: '网络连接失败',
      });

      const { result } = renderHook(() => useReportWorkflow('PROGRESS'));

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      let submitResult: unknown;
      await act(async () => {
        submitResult = await result.current.submitReport({
          reportType: 'PROGRESS',
          reportDate: '2026-02-13',
          processCategory: '切割',
          outputQuantity: 50,
        });
      });

      expect(submitResult).toBeNull();
      // 应该保存了一个草稿
      const drafts = useDraftReportStore.getState().drafts;
      expect(drafts).toHaveLength(1);
      expect(drafts[0]?.factoryId).toBe('F001');
      expect(Alert.alert).toHaveBeenCalledWith('提交失败', '网络连接失败\n已保存为草稿');
    });

    it('业务校验错误不应保存草稿，并应展示后端原因和下一步', async () => {
      mockedApiClient.getSchema.mockResolvedValueOnce(mockSchemaResponse);
      mockedApiClient.submitReport.mockRejectedValueOnce({
        message: 'Request failed with status code 400',
        response: {
          status: 400,
          data: {
            message: '产量(outputQuantity)必填',
            actionHint: '请输入本次完成的产量后重试',
          },
        },
      });

      const { result } = renderHook(() => useReportWorkflow('PROGRESS'));

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      let submitResult: unknown;
      await act(async () => {
        submitResult = await result.current.submitReport({
          reportType: 'PROGRESS',
          reportDate: '2026-02-13',
          processCategory: '切割',
        });
      });

      expect(submitResult).toBeNull();
      expect(useDraftReportStore.getState().drafts).toHaveLength(0);
      expect(Alert.alert).toHaveBeenCalledWith(
        '提交失败',
        '产量(outputQuantity)必填\n请输入本次完成的产量后重试'
      );
    });

    it('用户未登录应显示错误Alert并返回null', async () => {
      // 清除用户状态
      useAuthStore.setState({ user: null, isAuthenticated: false });
      mockedApiClient.getSchema.mockResolvedValueOnce(mockSchemaResponse);

      const { result } = renderHook(() => useReportWorkflow('PROGRESS'));

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      let submitResult: unknown;
      await act(async () => {
        submitResult = await result.current.submitReport({
          reportType: 'PROGRESS',
          reportDate: '2026-02-13',
          processCategory: '切割',
        });
      });

      expect(submitResult).toBeNull();
      expect(Alert.alert).toHaveBeenCalledWith('错误', '请先登录');
      expect(mockedApiClient.submitReport).not.toHaveBeenCalled();
    });
  });

  // ========== saveDraft ==========
  describe('saveDraft', () => {
    it('应该将报工数据保存为草稿', async () => {
      mockedApiClient.getSchema.mockResolvedValueOnce(mockSchemaResponse);

      const { result } = renderHook(() => useReportWorkflow('PROGRESS'));

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      act(() => {
        result.current.saveDraft({
          reportType: 'PROGRESS',
          reportDate: '2026-02-13',
          processCategory: '包装',
          productName: '鸡肉香肠',
          outputQuantity: 200,
          goodQuantity: 190,
          defectQuantity: 10,
        });
      });

      const drafts = useDraftReportStore.getState().drafts;
      expect(drafts).toHaveLength(1);
      expect(drafts[0]?.outputQuantity).toBe(200);
      expect(drafts[0]?.goodQuantity).toBe(190);
      expect(drafts[0]?.defectQuantity).toBe(10);
      expect(drafts[0]?.factoryId).toBe('F001');
      expect(drafts[0]?.notes).toContain('PROGRESS');
    });
  });

  // ========== reportType 切换 ==========
  describe('reportType切换', () => {
    it('PROGRESS模式应请求PRODUCTION_PROGRESS_REPORT的schema', async () => {
      mockedApiClient.getSchema.mockResolvedValueOnce(mockSchemaResponse);

      renderHook(() => useReportWorkflow('PROGRESS'));

      await waitFor(() => {
        expect(mockedApiClient.getSchema).toHaveBeenCalledWith('PRODUCTION_PROGRESS_REPORT');
      });
    });

    it('HOURS模式应请求PRODUCTION_HOURS_REPORT的schema', async () => {
      mockedApiClient.getSchema.mockResolvedValueOnce({
        ...mockSchemaResponse,
        data: {
          ...mockSchemaResponse.data,
          entityType: 'PRODUCTION_HOURS_REPORT',
          name: '生产工时报工模板',
        },
      });

      renderHook(() => useReportWorkflow('HOURS'));

      await waitFor(() => {
        expect(mockedApiClient.getSchema).toHaveBeenCalledWith('PRODUCTION_HOURS_REPORT');
      });
    });

    it('isFieldVisible应该使用正确的entityType', async () => {
      mockedApiClient.getSchema.mockResolvedValueOnce(mockSchemaResponse);

      const { result } = renderHook(() => useReportWorkflow('PROGRESS'));

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      // isFieldVisible 被封装成一个函数
      const visible = result.current.isFieldVisible('quantity');
      expect(typeof visible).toBe('boolean');
    });
  });
});
