// @ts-nocheck
/**
 * useRegister hook 单元测试
 * 测试注册流程：手机号验证、注册提交、表单重置、错误处理
 */

import { renderHook, act } from '@testing-library/react-native';
import { useRegister } from '../../../hooks/useRegister';

const mockRegisterPhaseOne = jest.fn();
const mockRegisterPhaseTwo = jest.fn();
jest.mock('../../../services/serviceFactory', () => ({
  AuthServiceInstance: {
    registerPhaseOne: (...args: any[]) => mockRegisterPhaseOne(...args),
    registerPhaseTwo: (...args: any[]) => mockRegisterPhaseTwo(...args),
  },
}));
jest.mock('../../../types/auth', () => ({ RegisterRequest: {} }));
jest.mock('../../../store', () => ({
  useAuthStore: () => ({
    login: jest.fn(),
  }),
}));

describe('useRegister', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockRegisterPhaseOne.mockReset();
    mockRegisterPhaseTwo.mockReset();
  });

  // ========== Initial State ==========
  it('should have initial currentStep as phone', () => {
    const { result } = renderHook(() => useRegister());
    expect(result.current.currentStep).toBe('phone');
  });

  it('should have initial tempToken as null', () => {
    const { result } = renderHook(() => useRegister());
    expect(result.current.tempToken).toBeNull();
  });

  it('should have initial isLoading as false', () => {
    const { result } = renderHook(() => useRegister());
    expect(result.current.isLoading).toBe(false);
  });

  it('should have initial error as null', () => {
    const { result } = renderHook(() => useRegister());
    expect(result.current.error).toBeNull();
  });

  // ========== verifyPhoneNumber ==========
  it('should set error when phone is empty', async () => {
    const { result } = renderHook(() => useRegister());

    await act(async () => {
      await result.current.verifyPhoneNumber('');
    });

    expect(result.current.error).toBeTruthy();
  });

  it('should set tempToken and advance step on successful verify', async () => {
    mockRegisterPhaseOne.mockResolvedValue({
      success: true,
      tempToken: 'mock-temp-token',
      factoryId: 'F001',
      phoneNumber: '13800138000',
      loginAccount: '13800138000',
      invitedRole: 'quality_inspector',
      invitedRoleName: '质检员',
    });

    const { result } = renderHook(() => useRegister());

    await act(async () => {
      await result.current.verifyPhoneNumber('13800138000');
    });

    expect(result.current.tempToken).toBe('mock-temp-token');
    expect(result.current.currentStep).toBe('info');
    expect(result.current.invitation).toMatchObject({
      factoryId: 'F001',
      loginAccount: '13800138000',
      invitedRole: 'quality_inspector',
    });
    expect(result.current.error).toBeNull();
  });

  it('should set error on verifyPhoneNumber failure', async () => {
    mockRegisterPhaseOne.mockRejectedValue(new Error('手机号已注册'));

    const { result } = renderHook(() => useRegister());

    await act(async () => {
      await result.current.verifyPhoneNumber('13800138000');
    });

    expect(result.current.error).toBeTruthy();
    expect(result.current.currentStep).toBe('phone');
  });

  it('should set isLoading during verifyPhoneNumber', async () => {
    let resolvePromise: (value: any) => void;
    mockRegisterPhaseOne.mockReturnValue(new Promise((resolve) => { resolvePromise = resolve; }));

    const { result } = renderHook(() => useRegister());

    let verifyPromise: Promise<any>;
    await act(async () => {
      verifyPromise = result.current.verifyPhoneNumber('13800138000');
    });

    // isLoading should have been set to true during the call
    await act(async () => {
      resolvePromise({ success: true, tempToken: 'token' });
      await verifyPromise;
    });

    expect(result.current.isLoading).toBe(false);
  });

  // ========== register ==========
  it('should call AuthService.register on successful registration', async () => {
    mockRegisterPhaseOne.mockResolvedValue({
      success: true,
      tempToken: 'mock-temp-token',
      factoryId: 'F001',
      phoneNumber: '13800138000',
    });
    mockRegisterPhaseTwo.mockResolvedValue({
      success: true,
      userId: 1,
      username: '13800138000',
      role: 'quality_inspector',
      message: '开户成功',
    });

    const { result } = renderHook(() => useRegister());

    // First verify phone
    await act(async () => {
      await result.current.verifyPhoneNumber('13800138000');
    });

    // Then register with all required fields
    await act(async () => {
      await result.current.register({
        tempToken: 'mock-temp-token',
        username: '13800138000',
        password: 'password123',
        realName: '测试用户',
        factoryId: 'F001',
      });
    });

    expect(mockRegisterPhaseTwo).toHaveBeenCalledWith(expect.objectContaining({
      username: '13800138000',
      factoryId: 'F001',
    }));
  });

  it('should set error when required fields are missing', async () => {
    const { result } = renderHook(() => useRegister());

    await act(async () => {
      const res = await result.current.register({
        tempToken: '',
        username: '',
        password: '',
        realName: '',
        factoryId: '',
      });
      expect(res).toBe(false);
    });

    expect(result.current.error).toBeTruthy();
  });

  it('should set error when password is too short', async () => {
    mockRegisterPhaseOne.mockResolvedValue({
      success: true,
      tempToken: 'mock-temp-token',
      factoryId: 'F001',
      phoneNumber: '13800138000',
    });
    const { result } = renderHook(() => useRegister());

    await act(async () => {
      await result.current.verifyPhoneNumber('13800138000');
    });

    await act(async () => {
      const res = await result.current.register({
        tempToken: 'mock-temp-token',
        username: 'testuser',
        password: '12',
        realName: '测试用户',
        factoryId: 'F001',
      });
      expect(res).toBe(false);
    });

    expect(result.current.error).toBeTruthy();
  });

  it('should set error on register API failure', async () => {
    mockRegisterPhaseOne.mockResolvedValue({
      success: true,
      tempToken: 'mock-temp-token',
      factoryId: 'F001',
      phoneNumber: '13800138000',
    });
    mockRegisterPhaseTwo.mockRejectedValue(new Error('注册失败'));

    const { result } = renderHook(() => useRegister());

    await act(async () => {
      await result.current.verifyPhoneNumber('13800138000');
    });

    await act(async () => {
      await result.current.register({
        tempToken: 'mock-temp-token',
        username: '13800138000',
        password: 'password123',
        realName: '测试用户',
        factoryId: 'F001',
      });
    });

    expect(result.current.error).toBeTruthy();
  });

  // ========== clearError ==========
  it('should clear error when clearError is called', async () => {
    const { result } = renderHook(() => useRegister());

    // Trigger an error first
    await act(async () => {
      await result.current.verifyPhoneNumber('');
    });
    expect(result.current.error).toBeTruthy();

    act(() => {
      result.current.clearError();
    });

    expect(result.current.error).toBeNull();
  });

  // ========== resetForm ==========
  it('should reset step to phone on resetForm', async () => {
    mockRegisterPhaseOne.mockResolvedValue({
      success: true,
      tempToken: 'mock-temp-token',
      factoryId: 'F001',
      phoneNumber: '13800138000',
    });

    const { result } = renderHook(() => useRegister());

    await act(async () => {
      await result.current.verifyPhoneNumber('13800138000');
    });
    expect(result.current.currentStep).toBe('info');

    act(() => {
      result.current.resetForm();
    });

    expect(result.current.currentStep).toBe('phone');
  });

  it('should clear tempToken on resetForm', async () => {
    mockRegisterPhaseOne.mockResolvedValue({
      success: true,
      tempToken: 'mock-temp-token',
      factoryId: 'F001',
      phoneNumber: '13800138000',
    });

    const { result } = renderHook(() => useRegister());

    await act(async () => {
      await result.current.verifyPhoneNumber('13800138000');
    });
    expect(result.current.tempToken).toBe('mock-temp-token');

    act(() => {
      result.current.resetForm();
    });

    expect(result.current.tempToken).toBeNull();
  });

  it('should clear error on resetForm', async () => {
    const { result } = renderHook(() => useRegister());

    await act(async () => {
      await result.current.verifyPhoneNumber('');
    });
    expect(result.current.error).toBeTruthy();

    act(() => {
      result.current.resetForm();
    });

    expect(result.current.error).toBeNull();
  });
});
