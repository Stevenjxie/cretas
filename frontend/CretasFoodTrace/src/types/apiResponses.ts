import { User, AuthTokens, UserDTO, FactoryRole } from './auth';

/**
 * API响应类型定义
 * 用于替换authService.ts中的 as any 类型断言
 */

// 基础API响应格式
export interface BaseApiResponse<T = any> {
  code: number;
  success: boolean;
  message: string;
  data?: T;
  timestamp?: string;
}

// 统一登录API响应数据
export interface UnifiedLoginResponseData {
  userId: number;  // Backend uses Long, always returns number
  username: string;
  role: string;
  roleCode?: string;
  token?: string;
  accessToken?: string;
  refreshToken: string;
  expiresIn?: number;
  tokenType?: string;
  factoryId?: string;
  factoryName?: string;
  factoryType?: string;
  profile?: {
    name?: string;
    email?: string;
    phoneNumber?: string;
    avatar?: string;
    department?: string;
    position?: string;
  };
  permissions?: {
    modules?: Record<string, boolean>;
    features?: string[];
    role?: string;
    roleLevel?: number;
  };
  lastLoginTime?: string;
  createdAt?: string;
  updatedAt?: string;
}

// 统一登录API响应
export type UnifiedLoginApiResponse = BaseApiResponse<UnifiedLoginResponseData>;

// 注册第一阶段响应数据
export interface RegisterPhaseOneResponseData {
  tempToken: string;
  factoryId?: string;
  phoneNumber: string;
  loginAccount?: string;
  invitedName?: string;
  invitedRole?: FactoryRole;
  invitedRoleName?: string;
  expiresAt: number;
  isNewUser: boolean;
  message?: string;
}

// 注册第一阶段API响应
export type RegisterPhaseOneApiResponse = BaseApiResponse<RegisterPhaseOneResponseData>;

export interface RegisterPhaseTwoResponseData {
  success: boolean;
  userId: number;
  username: string;
  role: FactoryRole;
  message: string;
  registeredAt: string;
}

export type RegisterPhaseTwoApiResponse = BaseApiResponse<RegisterPhaseTwoResponseData>;

// 用户注册API响应数据
export interface RegisterApiResponseData {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserDTO;
  message: string;
}

// 用户注册API响应
export type RegisterApiResponse = BaseApiResponse<RegisterApiResponseData>;

// 登出API响应
export interface LogoutApiResponse {
  code: number;
  message: string;
  timestamp: string;
}

// 重置密码API响应
export interface ResetPasswordApiResponse {
  code: number;
  success: boolean;
  message: string;
  timestamp?: string;
}

// 修改密码API响应
export interface ChangePasswordApiResponse {
  code: number;
  success: boolean;
  message: string;
  timestamp?: string;
}

// 错误响应接口 (用于错误处理)
export interface ApiErrorResponse {
  response?: {
    status?: number;
    data?: {
      message?: string;
      error?: string;
      code?: number;
    };
  };
  message?: string;
  code?: string; // 错误代码，如 ECONNREFUSED, ENOTFOUND, ETIMEDOUT 等
}
