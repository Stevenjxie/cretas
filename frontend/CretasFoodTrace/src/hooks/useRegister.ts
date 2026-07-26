/**
 * useRegister Hook - 用户注册逻辑封装
 * 支持两个步骤：
 * 1. 手机验证（获取tempToken）
 * 2. 完整信息填写（使用register API）
 */

import { useState } from 'react';
import { Alert } from 'react-native';
import { AuthServiceInstance as AuthService } from '../services/serviceFactory';
import {
  RegisterPhaseTwoRequest,
  RegistrationInvitation,
} from '../types/auth';

interface UseRegisterOptions {
  maxRetries?: number;
}

interface UseRegisterReturn {
  // 第一步：手机验证（不需要验证码）
  verifyPhoneNumber: (phoneNumber: string) => Promise<{
    success: boolean;
    tempToken?: string;
    invitation?: RegistrationInvitation;
  }>;

  // 第二步：完整注册
  register: (request: RegisterPhaseTwoRequest) => Promise<boolean>;

  // 状态管理
  isLoading: boolean;
  error: string | null;
  currentStep: 'phone' | 'info';  // 当前步骤
  tempToken: string | null;
  invitation: RegistrationInvitation | null;

  // 工具方法
  clearError: () => void;
  resetForm: () => void;
}

export function useRegister(options?: UseRegisterOptions): UseRegisterReturn {
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [currentStep, setCurrentStep] = useState<'phone' | 'info'>('phone');
  const [tempToken, setTempToken] = useState<string | null>(null);
  const [invitation, setInvitation] = useState<RegistrationInvitation | null>(null);

  /**
   * 第一步：验证手机号码（不需要验证码，直接查询白名单）
   */
  const verifyPhoneNumber = async (
    phoneNumber: string
  ): Promise<{
    success: boolean;
    tempToken?: string;
    invitation?: RegistrationInvitation;
  }> => {
    if (!phoneNumber.trim()) {
      setError('请输入手机号码');
      return { success: false };
    }

    setIsLoading(true);
    setError(null);

    try {
      // 直接调用 register-phase-one API，只需要手机号
      const result = await AuthService.registerPhaseOne({
        phoneNumber: phoneNumber.trim()
      });

      if (result.success && result.tempToken && result.factoryId) {
        const nextInvitation: RegistrationInvitation = {
          phoneNumber: result.phoneNumber || phoneNumber.trim(),
          loginAccount: result.loginAccount || result.phoneNumber || phoneNumber.trim(),
          factoryId: result.factoryId,
          invitedName: result.invitedName,
          invitedRole: result.invitedRole,
          invitedRoleName: result.invitedRoleName,
        };
        setTempToken(result.tempToken);
        setInvitation(nextInvitation);
        setCurrentStep('info');
        Alert.alert('验证通过', '已找到管理员邀请，请设置登录密码');
        return {
          success: true,
          tempToken: result.tempToken,
          invitation: nextInvitation,
        };
      } else {
        throw new Error(result.message || '未找到有效的工厂邀请');
      }
    } catch (err: any) {
      console.error('手机验证失败:', err);
      const errorMessage = err.message || err.response?.data?.message || '手机验证失败，请重试';
      setError(errorMessage);
      Alert.alert('错误', errorMessage);
      return { success: false };
    } finally {
      setIsLoading(false);
    }
  };

  /**
   * 第三步：完成注册（使用新的register API）
   */
  const register = async (request: RegisterPhaseTwoRequest): Promise<boolean> => {
    // 验证必需字段
    if (!request.tempToken || !request.username || !request.password || !request.realName || !request.factoryId) {
      setError('缺少必需字段');
      Alert.alert('错误', '请填写所有必需字段');
      return false;
    }

    if (request.password.length < 6) {
      setError('密码长度必须至少6个字符');
      Alert.alert('错误', '密码长度必须至少6个字符');
      return false;
    }

    setIsLoading(true);
    setError(null);

    try {
      const result = await AuthService.registerPhaseTwo(request);

      if (result.success) {
        Alert.alert('开户成功', result.message || '账户已创建，请使用手机号登录');
        resetForm();
        return true;
      } else {
        throw new Error(result.message || '注册失败');
      }
    } catch (err: any) {
      console.error('注册失败:', err);
      const errorMessage = err.message || '注册失败，请重试';
      setError(errorMessage);
      Alert.alert('注册失败', errorMessage);
      return false;
    } finally {
      setIsLoading(false);
    }
  };

  const clearError = () => {
    setError(null);
  };

  const resetForm = () => {
    setError(null);
    setCurrentStep('phone');
    setTempToken(null);
    setInvitation(null);
  };

  return {
    verifyPhoneNumber,
    register,
    isLoading,
    error,
    currentStep,
    tempToken,
    invitation,
    clearError,
    resetForm,
  };
}
