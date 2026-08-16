/**
 * NfcCheckinScreen 集成测试 (RNTL)
 * 测试签到页面的渲染、批次选择、扫码签到、签退流程
 */

import React from 'react';
import { render, fireEvent, waitFor, screen, act } from '@testing-library/react-native';
import { Alert } from 'react-native';
import NfcCheckinScreen from '../../../screens/processing/NfcCheckinScreen';
import { workReportingApiClient } from '../../../services/api/workReportingApiClient';
import { processingApiClient } from '../../../services/api/processingApiClient';
import { useAuthStore } from '../../../store/authStore';
import type { FactoryUser } from '../../../types/auth';

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

// Mock BarcodeScannerModal
jest.mock('../../../components/processing/BarcodeScannerModal', () => {
  const { View, Text, TouchableOpacity } = require('react-native');
  return {
    __esModule: true,
    default: ({ visible, onClose, onScan }: { visible: boolean; onClose: () => void; onScan: (code: string) => void }) => {
      if (!visible) return null;
      return (
        <View testID="barcode-scanner-modal">
          <Text>扫码模态框</Text>
          <TouchableOpacity testID="mock-scan-btn" onPress={() => onScan('33')}>
            <Text>模拟扫码</Text>
          </TouchableOpacity>
          <TouchableOpacity testID="mock-close-btn" onPress={onClose}>
            <Text>关闭</Text>
          </TouchableOpacity>
        </View>
      );
    },
    BarcodeScannerModal: ({ visible, onClose, onScan }: { visible: boolean; onClose: () => void; onScan: (code: string) => void }) => {
      if (!visible) return null;
      return (
        <View testID="barcode-scanner-modal">
          <TouchableOpacity testID="mock-scan-btn" onPress={() => onScan('33')}>
            <Text>模拟扫码</Text>
          </TouchableOpacity>
        </View>
      );
    },
  };
});

const mockGoBack = jest.fn();
jest.mock('@react-navigation/native', () => ({
  ...jest.requireActual('@react-navigation/native'),
  useNavigation: () => ({
    navigate: jest.fn(),
    goBack: mockGoBack,
    setOptions: jest.fn(),
    addListener: jest.fn(),
  }),
  useRoute: () => ({
    params: {},
  }),
  useFocusEffect: jest.fn(),
  // 2026-08-16: 补 useNavigationState。屏幕后来用它算 canGoBack, 而这份局部 mock
  // 【覆盖】了 setup.ts 的全局 mock, 所以必须在这里也补, 否则渲染期抛
  //   "Couldn't get the navigation state. Is your component inside a navigator?"
  // 返回单路由栈 = 「当前在栈底」, canGoBack 为 false, 不凭空造出返回按钮。
  useNavigationState: (selector: (state: unknown) => unknown) =>
    selector({ index: 0, routes: [{ key: 'test-0', name: 'Test' }] }),
}));

const mockedProcessingApi = processingApiClient as jest.Mocked<typeof processingApiClient>;
const mockedWorkReportingApi = workReportingApiClient as jest.Mocked<typeof workReportingApiClient>;

describe('NfcCheckinScreen', () => {
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

  const mockBatches = [
    { id: 101, batchNumber: 'BATCH-2026-001', productName: '鸡肉香肠', status: 'IN_PROGRESS' },
    { id: 102, batchNumber: 'BATCH-2026-002', productName: '牛肉干', status: 'IN_PROGRESS' },
    { id: 103, batchNumber: 'BATCH-2026-003', productName: '猪肉脯', status: 'IN_PROGRESS' },
  ];

  const mockCheckins: import('../../../types/workReporting').CheckinWorkerDTO[] = [
    {
      sessionId: 1,
      batchId: 101,
      employeeId: 33,
      fullName: null,
      position: null,
      hireType: null,
      hireTypeLabel: null,
      checkinMethod: 'QR',
      checkInTime: '2026-02-13T08:00:00Z',
      checkOutTime: null,
      status: 'working',
    },
    {
      sessionId: 2,
      batchId: 101,
      employeeId: 34,
      fullName: null,
      position: null,
      hireType: null,
      hireTypeLabel: null,
      checkinMethod: 'QR',
      checkInTime: '2026-02-13T08:05:00Z',
      checkOutTime: '2026-02-13T17:00:00Z',
      status: 'completed',
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

    // 默认返回批次列表
    mockedProcessingApi.getBatches.mockResolvedValue({
      success: true,
      code: 200,
      message: '成功',
      data: {
        content: mockBatches,
        totalElements: 3,
        totalPages: 1,
        size: 10,
        number: 0,
        first: true,
        last: true,
        empty: false,
      },
    } as any);

    // 默认返回签到列表
    mockedWorkReportingApi.getCheckinList.mockResolvedValue({
      success: true,
      code: 200,
      message: '成功',
      data: mockCheckins,
    });
  });

  // ========== 批次列表阶段 ==========
  describe('批次列表渲染', () => {
    it('应该显示标题和批次列表', async () => {
      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('选择批次签到')).toBeTruthy();
      });

      // 应该显示所有批次
      expect(screen.getByText('BATCH-2026-001')).toBeTruthy();
      expect(screen.getByText('BATCH-2026-002')).toBeTruthy();
      expect(screen.getByText('BATCH-2026-003')).toBeTruthy();
    });

    it('应该显示批次产品名', async () => {
      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('鸡肉香肠')).toBeTruthy();
      });

      expect(screen.getByText('牛肉干')).toBeTruthy();
      expect(screen.getByText('猪肉脯')).toBeTruthy();
    });

    it('无批次时应该显示空状态', async () => {
      mockedProcessingApi.getBatches.mockResolvedValueOnce({
        success: true,
        code: 200,
        message: '成功',
        data: {
          content: [],
          totalElements: 0,
          totalPages: 0,
          size: 10,
          number: 0,
          first: true,
          last: true,
          empty: true,
        },
      } as any);

      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('暂无进行中的批次')).toBeTruthy();
      });
    });

    it('加载失败应该显示空列表', async () => {
      mockedProcessingApi.getBatches.mockRejectedValueOnce(new Error('网络错误'));

      render(<NfcCheckinScreen />);

      await waitFor(() => {
        // 加载失败后不应崩溃，应显示空状态
        expect(screen.getByText('暂无进行中的批次')).toBeTruthy();
      });
    });
  });

  // ========== 选择批次后 ==========
  describe('选择批次', () => {
    it('点击批次应进入签到管理界面', async () => {
      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('BATCH-2026-001')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('BATCH-2026-001'));

      // 应该显示该批次号作为标题
      await waitFor(() => {
        expect(screen.getByText('BATCH-2026-001')).toBeTruthy();
      });

      // 应该显示签到统计
      await waitFor(() => {
        expect(screen.getByText('已签到')).toBeTruthy();
        expect(screen.getByText('工作中')).toBeTruthy();
        expect(screen.getByText('已签退')).toBeTruthy();
      });
    });

    it('选择批次后应显示签到人数', async () => {
      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('BATCH-2026-001')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('BATCH-2026-001'));

      // 2个签到记录
      await waitFor(() => {
        expect(screen.getByText('2')).toBeTruthy(); // 已签到=2
      });
    });

    it('应该显示扫码签到按钮', async () => {
      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('BATCH-2026-001')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('BATCH-2026-001'));

      await waitFor(() => {
        expect(screen.getByText('扫码签到')).toBeTruthy();
      });
    });
  });

  // ========== 签到记录显示 ==========
  describe('签到记录', () => {
    it('应该显示员工签到记录', async () => {
      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('BATCH-2026-001')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('BATCH-2026-001'));

      await waitFor(() => {
        // 员工33工作中
        expect(screen.getByText('员工 #33')).toBeTruthy();
        expect(screen.getByText('工作中')).toBeTruthy();
      });

      // 员工34已签退
      expect(screen.getByText('员工 #34')).toBeTruthy();
      expect(screen.getByText('已签退')).toBeTruthy();
    });

    it('工作中的员工应该显示签退按钮', async () => {
      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('BATCH-2026-001')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('BATCH-2026-001'));

      await waitFor(() => {
        expect(screen.getByText('签退')).toBeTruthy();
      });
    });

    it('无签到记录应显示提示文字', async () => {
      mockedWorkReportingApi.getCheckinList.mockResolvedValueOnce({
        success: true,
        code: 200,
        message: '成功',
        data: [],
      });

      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('BATCH-2026-001')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('BATCH-2026-001'));

      await waitFor(() => {
        expect(screen.getByText('暂无签到记录，请扫码签到')).toBeTruthy();
      });
    });
  });

  // ========== 扫码签到流程 ==========
  describe('扫码签到', () => {
    it('点击扫码签到应打开扫码模态框', async () => {
      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('BATCH-2026-001')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('BATCH-2026-001'));

      await waitFor(() => {
        expect(screen.getByText('扫码签到')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('扫码签到'));

      // 应该显示扫码模态框
      await waitFor(() => {
        expect(screen.getByTestId('barcode-scanner-modal')).toBeTruthy();
      });
    });

    it('扫码成功后应调用checkin API', async () => {
      mockedWorkReportingApi.checkin.mockResolvedValueOnce({
        success: true,
        code: 200,
        message: '签到成功',
        data: {
          id: 3,
          batchId: 101,
          employeeId: 33,
          checkInTime: '2026-02-13T08:30:00Z',
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

      // 打开扫码
      fireEvent.press(screen.getByText('扫码签到'));

      await waitFor(() => {
        expect(screen.getByTestId('mock-scan-btn')).toBeTruthy();
      });

      // 模拟扫码（扫到employeeId=33）
      fireEvent.press(screen.getByTestId('mock-scan-btn'));

      await waitFor(() => {
        expect(mockedWorkReportingApi.checkin).toHaveBeenCalledWith(
          expect.objectContaining({
            batchId: 101,
            employeeId: 33,
            checkinMethod: 'QR',
          })
        );
      });

      // 应该显示成功提示
      await waitFor(() => {
        expect(Alert.alert).toHaveBeenCalledWith('签到成功', expect.stringContaining('33'));
      });
    });

    it('扫码失败应显示错误', async () => {
      mockedWorkReportingApi.checkin.mockResolvedValueOnce({
        success: false,
        code: 400,
        message: '员工已签到',
        data: null!,
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
        expect(screen.getByTestId('mock-scan-btn')).toBeTruthy();
      });

      fireEvent.press(screen.getByTestId('mock-scan-btn'));

      await waitFor(() => {
        expect(Alert.alert).toHaveBeenCalledWith('签到失败', '员工已签到');
      });
    });
  });

  // ========== 签退流程 ==========
  describe('签退', () => {
    it('点击签退按钮应调用checkout API', async () => {
      mockedWorkReportingApi.checkout.mockResolvedValueOnce({
        success: true,
        code: 200,
        message: '签退成功',
        data: {
          id: 1,
          batchId: 101,
          employeeId: 33,
          checkInTime: '2026-02-13T08:00:00Z',
          checkOutTime: '2026-02-13T17:00:00Z',
          workMinutes: 540,
          status: 'completed',
        },
      });

      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('BATCH-2026-001')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('BATCH-2026-001'));

      await waitFor(() => {
        expect(screen.getByText('签退')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('签退'));

      await waitFor(() => {
        expect(mockedWorkReportingApi.checkout).toHaveBeenCalledWith(
          expect.objectContaining({
            batchId: 101,
            employeeId: 33,
          })
        );
      });

      await waitFor(() => {
        expect(Alert.alert).toHaveBeenCalledWith('签退成功');
      });
    });

    it('签退失败应显示错误', async () => {
      mockedWorkReportingApi.checkout.mockResolvedValueOnce({
        success: false,
        code: 400,
        message: '签退失败：工作时间不足',
        data: null!,
      });

      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('BATCH-2026-001')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('BATCH-2026-001'));

      await waitFor(() => {
        expect(screen.getByText('签退')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('签退'));

      await waitFor(() => {
        expect(Alert.alert).toHaveBeenCalledWith('签退失败', '签退失败：工作时间不足');
      });
    });

    it('签退网络异常应显示错误', async () => {
      mockedWorkReportingApi.checkout.mockRejectedValueOnce(new Error('连接超时'));

      render(<NfcCheckinScreen />);

      await waitFor(() => {
        expect(screen.getByText('BATCH-2026-001')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('BATCH-2026-001'));

      await waitFor(() => {
        expect(screen.getByText('签退')).toBeTruthy();
      });

      fireEvent.press(screen.getByText('签退'));

      await waitFor(() => {
        expect(Alert.alert).toHaveBeenCalledWith('签退失败', '连接超时');
      });
    });
  });
});
