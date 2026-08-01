/**
 * 统一的错误处理工具函数
 *
 * 使用场景：
 * 1. 统一处理API错误
 * 2. 显示用户友好的错误提示
 * 3. 记录错误日志
 */

import { Alert } from 'react-native';
import Toast from 'react-native-toast-message';
import {
  ApiError,
  ApiErrorType,
  isApiError,
  BusinessError,
  NotImplementedError,
  SecurityError,
} from '../errors';
import { getErrorMessage } from '../config/errorMessages';

/**
 * 错误处理选项
 */
export interface ErrorHandlerOptions {
  /** 是否显示用户提示（默认true） */
  showAlert?: boolean;

  /** 自定义错误标题 */
  title?: string;

  /** 是否显示重试按钮（默认false） */
  showRetry?: boolean;

  /** 重试回调函数 */
  onRetry?: () => void;

  /** 是否记录错误日志（默认true） */
  logError?: boolean;

  /** 自定义错误消息（覆盖默认消息） */
  customMessage?: string;

  /** 导航到登录页的回调（用于认证错误） */
  navigateToLogin?: () => void;
}

/**
 * 统一的错误处理函数
 * @param error 错误对象
 * @param options 处理选项
 */
export function handleError(error: unknown, options: ErrorHandlerOptions = {}): void {
  const {
    showAlert = true,
    title = '错误',
    showRetry = false,
    onRetry,
    logError = true,
    customMessage,
    navigateToLogin,
  } = options;

  // 记录错误日志
  if (logError) {
    logErrorToConsole(error);
  }

  // 获取用户友好的错误消息
  const userMessage = customMessage || getUserFriendlyMessage(error);

  // 显示错误提示
  if (showAlert) {
    showErrorAlert(title, userMessage, showRetry, onRetry);
  }

  // 处理认证错误
  if (isAuthenticationError(error) && navigateToLogin) {
    setTimeout(() => {
      navigateToLogin();
    }, 1500); // 延迟跳转，让用户看到错误提示
  }
}

/**
 * 获取用户友好的错误消息
 */
export function getUserFriendlyMessage(error: unknown): string {
  // ApiError
  if (isApiError(error)) {
    return error.getUserMessage();
  }

  // BusinessError
  if (BusinessError.isBusinessError(error)) {
    return error.message;
  }

  // NotImplementedError
  if (error instanceof NotImplementedError) {
    return error.getUserMessage();
  }

  // SecurityError
  if (error instanceof SecurityError) {
    return error.message;
  }

  // 标准Error
  if (error instanceof Error) {
    // 网络错误
    if (error.message.includes('Network') || error.message.includes('Failed to fetch')) {
      return getErrorMessage('CONNECTION_FAILED');
    }

    // 超时错误
    if (error.message.includes('timeout') || error.message.includes('timed out')) {
      return getErrorMessage('TIMEOUT');
    }

    return error.message || getErrorMessage('UNKNOWN');
  }

  // 字符串错误
  if (typeof error === 'string') {
    return error;
  }

  // 未知错误
  return getErrorMessage('UNKNOWN');
}

/**
 * 显示错误Alert
 */
function showErrorAlert(
  title: string,
  message: string,
  showRetry: boolean,
  onRetry?: () => void
): void {
  const buttons: Array<{ text: string; onPress?: () => void; style?: 'cancel' | 'default' | 'destructive' }> = [
    { text: '确定', style: 'cancel' },
  ];

  if (showRetry && onRetry) {
    buttons.unshift({
      text: '重试',
      onPress: onRetry,
    });
  }

  Alert.alert(title, message, buttons);
}

/**
 * 记录错误日志到控制台
 */
function logErrorToConsole(error: unknown): void {
  console.error('==================== Error Details ====================');

  if (isApiError(error)) {
    console.error('Error Type: ApiError');
    console.error('API Error Type:', error.type);
    console.error('Status Code:', error.statusCode);
    console.error('Message:', error.message);
    console.error('Request URL:', error.requestUrl);
    console.error('Can Retry:', error.canRetry);
    if (error.originalError) {
      console.error('Original Error:', error.originalError);
    }
  } else if (BusinessError.isBusinessError(error)) {
    console.error('Error Type: BusinessError');
    console.error('Business Error Code:', error.code);
    console.error('Message:', error.message);
    console.error('Data:', error.data);
    console.error('Timestamp:', error.timestamp);
  } else if (error instanceof NotImplementedError) {
    console.error('Error Type: NotImplementedError');
    console.error('Feature Name:', error.featureName);
    console.error('Planned Version:', error.plannedVersion);
    console.error('Metadata:', error.metadata);
    console.error('Message:', error.message);
  } else if (error instanceof SecurityError) {
    console.error('Error Type: SecurityError');
    console.error('Security Error Code:', error.code);
    console.error('Severity:', error.severity);
    console.error('Message:', error.message);
  } else if (error instanceof Error) {
    console.error('Error Type: Error');
    console.error('Name:', error.name);
    console.error('Message:', error.message);
    console.error('Stack:', error.stack);
  } else {
    console.error('Unknown Error Type:', typeof error);
    console.error('Error:', error);
  }

  console.error('======================================================');
}

/**
 * 判断是否为认证错误
 */
function isAuthenticationError(error: unknown): boolean {
  if (isApiError(error)) {
    return error.type === ApiErrorType.AUTH_ERROR;
  }

  if (BusinessError.isBusinessError(error)) {
    return error.code === 401;
  }

  return false;
}

/**
 * 处理API错误的快捷函数
 */
export async function handleApiCall<T>(
  apiCall: () => Promise<T>,
  options: ErrorHandlerOptions = {}
): Promise<T | null> {
  try {
    return await apiCall();
  } catch (error) {
    handleError(error, options);
    return null;
  }
}

/**
 * 包装异步函数，自动处理错误
 */
export function withErrorHandler<T extends (...args: any[]) => Promise<any>>(
  fn: T,
  options: ErrorHandlerOptions = {}
): T {
  return (async (...args: Parameters<T>): Promise<ReturnType<T> | null> => {
    try {
      return await fn(...args);
    } catch (error) {
      handleError(error, options);
      return null;
    }
  }) as T;
}

/**
 * Toast提示 - ✅ 已集成 react-native-toast-message (2025-11-20)
 */
export function showToast(message: string, duration: 'short' | 'long' = 'short'): void {
  Toast.show({
    type: 'info',
    text1: message,
    visibilityTime: duration === 'short' ? 2000 : 4000,
    position: 'bottom',
  });
}

/**
 * 显示成功提示 - ✅ 使用Toast (2025-11-20)
 */
export function showSuccess(message: string): void {
  Toast.show({
    type: 'success',
    text1: '成功',
    text2: message,
    visibilityTime: 3000,
    position: 'top',
  });
}

/**
 * 显示警告提示 - ✅ 使用Toast (2025-11-20)
 */
export function showWarning(message: string): void {
  Toast.show({
    type: 'error',
    text1: '警告',
    text2: message,
    visibilityTime: 3000,
    position: 'top',
  });
}

/**
 * 显示确认对话框
 */
export function showConfirm(
  title: string,
  message: string,
  onConfirm: () => void,
  onCancel?: () => void
): void {
  Alert.alert(
    title,
    message,
    [
      {
        text: '取消',
        style: 'cancel',
        onPress: onCancel,
      },
      {
        text: '确定',
        onPress: onConfirm,
      },
    ]
  );
}

/**
 * 从 unknown 类型的 error 中提取错误消息
 * 用于 catch 块中安全地访问 error.message
 * @param error catch 块捕获的 unknown 类型错误
 * @returns 错误消息字符串
 */
export function getErrorMsg(error: unknown): string {
  if (typeof error === 'string') {
    return error;
  }

  if (error && typeof error === 'object') {
    const response = 'response' in error ? error.response : undefined;
    if (response && typeof response === 'object' && 'data' in response) {
      const data = response.data;
      if (data && typeof data === 'object') {
        const message = 'message' in data && typeof data.message === 'string'
          ? data.message.trim()
          : '';
        const actionHint = 'actionHint' in data && typeof data.actionHint === 'string'
          ? data.actionHint.trim()
          : '';
        if (message && actionHint && !message.includes(actionHint)) {
          return `${message}\n${actionHint}`;
        }
        if (message || actionHint) {
          return message || actionHint;
        }
      }
    }

    if ('message' in error && typeof error.message === 'string' && error.message.trim()) {
      return error.message;
    }
  }
  return '未知错误';
}

/** 仅网络不可达/超时才允许自动保存离线草稿；业务 4xx 不应伪装成网络失败。 */
export function isNetworkError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;
  const code = 'code' in error && typeof error.code === 'string' ? error.code : '';
  if (['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT'].includes(code)) return true;
  return 'request' in error && !('response' in error && error.response);
}
