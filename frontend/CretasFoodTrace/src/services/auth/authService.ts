import { isAxiosError } from 'axios';
import { apiClient } from '../api/apiClient';
import { StorageService } from '../storage/storageService';
import { TokenManager } from '../tokenManager';
import { BiometricManager } from '../biometricManager';
import { NetworkManager } from '../networkManager';
import {
  LoginRequest,
  LoginResponse,
  RegisterPhaseOneRequest,
  RegisterPhaseTwoRequest,
  RegisterResponse,
  RegisterRequest,
  RegisterResponseData,
  ChangePasswordRequest,
  ChangePasswordResponse,
  User,
  FactoryUser,
  PlatformUser,
  AuthTokens,
  BiometricAuthOptions,
  UserRole,
  FactoryRole,
  PlatformRole,
  Department,
  USER_ROLES,
  PLATFORM_ROLES,
  FACTORY_ROLES,
  UserDTO,
  UserPermissions
} from '../../types/auth';
import {
  UnifiedLoginApiResponse,
  RegisterPhaseOneApiResponse,
  RegisterPhaseTwoApiResponse,
  RegisterApiResponse,
  LogoutApiResponse,
  ResetPasswordApiResponse,
  ChangePasswordApiResponse,
  ApiErrorResponse
} from '../../types/apiResponses';
import { transformBackendUser, getUserRole } from '../../utils/roleMapping';
import { logger } from '../../utils/logger';

// 创建AuthService专用logger
const authLogger = logger.createContextLogger('AuthService');

export class AuthService {
  // 登录方法 - 支持新的 /api/auth/login 端点
  static async login(credentials: LoginRequest): Promise<LoginResponse> {
    try {
      authLogger.info('开始登录流程', { username: credentials.username });

      // Network pre-check skipped — let HTTP request determine connectivity

      // 调用新的API端点 - 统一登录接口（支持工厂用户和平台管理员）
      // 注意: unified-login 会自动识别用户类型（平台管理员 or 工厂用户）
      // 所以不需要显式传递 factoryId - 后端会根据username判断
      const loginPayload: LoginRequest = {
        username: credentials.username,
        password: credentials.password,
        deviceInfo: credentials.deviceInfo
      };

      // 仅当需要明确指定工厂用户时才传递 factoryId（目前不需要）
      // 因为后端会通过用户名自动识别是平台用户还是工厂用户

      authLogger.debug('发送登录请求', loginPayload);

      const rawResponse = await NetworkManager.executeWithRetry(
        () => apiClient.post<UnifiedLoginApiResponse>('/api/mobile/auth/unified-login', loginPayload),
        // Apr 19 2026 稳定性修: NetworkManager default timeout 30s 不够 cover OkHttp cold-start
        // (Windows emulator + Play Store 后台下载时常见), 改 90s (与 axios 120s 留 30s 余量).
        // 真机用户网络正常,不受影响 (retry 很少触发).
        { maxRetries: 2, baseDelay: 1000, timeout: 90000 }
      );

      authLogger.debug('Raw API Response', rawResponse);

      // 转换新API的响应格式为内部格式
      const response = this.adaptNewApiResponse(rawResponse);

      if (response.success && response.user && response.tokens) {
        // 调试日志: 打印转换后的用户数据（敏感字段会被自动脱敏）
        authLogger.debug('Transformed User Data', response.user);

        // 使用TokenManager保存认证信息
        const tokenData = {
          accessToken: response.tokens.accessToken,
          refreshToken: response.tokens.refreshToken,
          tempToken: response.tokens.tempToken,
          expiresAt: Date.now() + (response.tokens.expiresIn ?? 86400) * 1000, // 默认24小时
          tokenType: response.tokens.tokenType ?? 'Bearer'
        };

        await TokenManager.storeTokens(tokenData);
        await this.saveUserInfo(response.user);

        // 如果启用了生物识别且设备支持，询问是否保存凭据
        if (credentials.biometricEnabled) {
          const canUseBiometric = await BiometricManager.isAvailable();
          if (canUseBiometric) {
            await BiometricManager.saveBiometricCredentials({
              username: credentials.username,
              encryptedToken: response.tokens.accessToken,
              deviceInfo: credentials.deviceInfo
            });
          }
        }

        authLogger.info('登录成功', {
          userId: response.user.id,
          role: getUserRole(response.user),
          userType: response.user.userType
        });
      }

      return response;
    } catch (error) {
      authLogger.error('登录失败', error);
      throw this.handleAuthError(error);
    }
  }

  // 适配新API响应格式 - 处理后端统一登录返回
  private static adaptNewApiResponse(rawResponse: UnifiedLoginApiResponse): LoginResponse {
    try {
      // 后端unified-login实际返回格式:
      // {
      //   code: 200,
      //   success: true,
      //   message: "操作成功",
      //   data: {
      //     userId, username, role, token, refreshToken, profile, permissions, ...
      //   }
      // }

      if (!rawResponse.success || rawResponse.code !== 200) {
        return {
          success: false,
          message: rawResponse.message || '登录失败'
        };
      }

      // 检查是否有data字段
      if (!rawResponse.data) {
        return {
          success: false,
          message: '登录响应中缺少数据'
        };
      }

      const data = rawResponse.data;

      // 检查是否有必需字段 (token/accessToken/userId)
      // 后端现在同时返回 token 和 accessToken 两个字段（值相同）
      const tokenValue = data.token ?? data.accessToken;
      if (!tokenValue || !data.userId) {
        return {
          success: false,
          message: '登录响应中缺少用户信息或Token'
        };
      }

      // 构建用户对象 - 从data中提取信息
      const backendUser = {
        id: data.userId,
        username: data.username,
        email: data.profile?.email || '',
        phone: data.profile?.phoneNumber || '',
        fullName: data.profile?.name || data.username,
        avatar: data.profile?.avatar,
        lastLogin: data.lastLoginTime,
        lastLoginAt: data.lastLoginTime,
        createdAt: data.createdAt,
        updatedAt: data.updatedAt,
        isActive: true,
        role: data.role,
        roleCode: data.role,
        factoryId: data.factoryId,
        factoryName: data.factoryName,
        department: data.profile?.department,
        position: data.profile?.position,
        permissions: data.permissions,
        factoryType: data.factoryType
      };

      // 构建tokens对象 - 从data中提取token信息
      // 使用 tokenValue 变量，兼容 token 或 accessToken 字段
      const backendTokens = {
        token: tokenValue,
        accessToken: tokenValue,
        refreshToken: data.refreshToken,
        expiresIn: data.expiresIn,
        tokenType: 'Bearer'
      };

      // 确定userType - 如果有factoryId则是factory用户，否则是platform用户
      const userType = backendUser.factoryId ? 'factory' : 'platform';

      let user: User;

      if (userType === 'factory') {
        user = {
          id: typeof backendUser.id === 'string' ? parseInt(backendUser.id, 10) : backendUser.id,
          username: backendUser.username,
          email: backendUser.email || '',
          phone: backendUser.phone,
          fullName: backendUser.fullName,
          avatar: backendUser.avatar,
          lastLoginAt: backendUser.lastLoginAt || backendUser.lastLogin,
          createdAt: backendUser.createdAt,
          updatedAt: backendUser.updatedAt,
          isActive: backendUser.isActive,
          factoryId: backendUser.factoryId,
          department: backendUser.department as Department,
          position: backendUser.position,
          roleCode: backendUser.roleCode as FactoryRole,
          userType: 'factory',
          factoryUser: {
            role: backendUser.roleCode as FactoryRole,
            factoryId: backendUser.factoryId!,
            factoryType: (backendUser.factoryType || 'FACTORY').toUpperCase(),
            department: backendUser.department as Department,
            position: backendUser.position,
            permissions: backendUser.permissions?.features ?? []
          }
        } as FactoryUser;
      } else {
        user = {
          id: typeof backendUser.id === 'string' ? parseInt(backendUser.id, 10) : backendUser.id,
          username: backendUser.username,
          email: backendUser.email || '',
          phone: backendUser.phone,
          fullName: backendUser.fullName,
          avatar: backendUser.avatar,
          lastLoginAt: backendUser.lastLoginAt || backendUser.lastLogin,
          createdAt: backendUser.createdAt,
          updatedAt: backendUser.updatedAt,
          isActive: backendUser.isActive,
          roleCode: backendUser.role as PlatformRole,
          userType: 'platform',
          platformUser: {
            role: backendUser.role as PlatformRole,
            permissions: backendUser.permissions?.features ?? []
          }
        } as PlatformUser;
      }

      // 添加权限信息到user对象顶级属性（用于后续权限检查）
      const permissionsData = backendUser.permissions ?? {};
      const userWithPermissions = user as User & { permissions: UserPermissions };
      userWithPermissions.permissions = {
        modules: {
          farming_access: permissionsData.modules?.farming_access ?? false,
          processing_access: permissionsData.modules?.processing_access ?? false,
          logistics_access: permissionsData.modules?.logistics_access ?? false,
          trace_access: permissionsData.modules?.trace_access ?? false,
          admin_access: permissionsData.modules?.admin_access ?? false,
          platform_access: permissionsData.modules?.platform_access ?? false,
          debug_access: permissionsData.modules?.debug_access,
          system_config: permissionsData.modules?.system_config,
        },
        features: permissionsData.features ?? [],
        role: permissionsData.role ?? backendUser.roleCode ?? backendUser.role ?? '',
        userType: user.userType,
        level: permissionsData.roleLevel ?? 0,
        departments: user.userType === 'factory' && (user as FactoryUser).factoryUser.department
          ? [(user as FactoryUser).factoryUser.department] as string[]
          : undefined
      };

      // 转换用户数据（如果需要进一步处理）
      const transformedUser = transformBackendUser(user);

      // 构建tokens对象 - 后端返回 token 字段，需要映射为 accessToken
      const tokens: AuthTokens = {
        accessToken: backendTokens.token ?? backendTokens.accessToken,
        refreshToken: backendTokens.refreshToken,
        tempToken: undefined,
        expiresIn: backendTokens.expiresIn ?? 86400,
        tokenType: backendTokens.tokenType ?? 'Bearer'
      };

      authLogger.debug('API响应适配成功', {
        userId: user.id,
        username: user.username,
        userType: user.userType,
        hasToken: !!tokens.accessToken
      });

      return {
        success: true,
        message: rawResponse.message || '登录成功',
        user: transformedUser,
        tokens: tokens
      };
    } catch (error) {
      authLogger.error('适配API响应失败', error);
      return {
        success: false,
        message: '登录响应处理失败'
      };
    }
  }

  // 发送验证码
  static async sendVerificationCode(phoneNumber: string): Promise<{ success: boolean; message?: string }> {
    try {
      const response = await apiClient.post<{ success: boolean; message?: string }>('/api/mobile/auth/send-code', {
        phoneNumber
      });
      return response;
    } catch (error) {
      authLogger.error('发送验证码失败', error);
      throw this.handleAuthError(error);
    }
  }

  // 验证手机号和验证码
  static async verifyPhoneNumber(request: {
    phoneNumber: string;
    verificationCode: string;
    verificationType: 'registration' | 'reset';
  }): Promise<{
    success: boolean;
    message?: string;
    tempToken?: string;
    factoryId?: string;
    whitelistInfo?: any;
  }> {
    try {
      const response = await apiClient.post<{
        success: boolean;
        message?: string;
        tempToken?: string;
        factoryId?: string;
        whitelistInfo?: any;
      }>('/api/mobile/auth/verify-code', {
        phoneNumber: request.phoneNumber,
        code: request.verificationCode
      });

      if (response.tempToken) {
        await StorageService.setSecureItem('temp_token', response.tempToken);
      }

      return response;
    } catch (error) {
      authLogger.error('验证手机号失败', error);
      throw this.handleAuthError(error);
    }
  }

  // 注册第一阶段 - 手机验证
  static async registerPhaseOne(request: RegisterPhaseOneRequest): Promise<RegisterResponse> {
    try {
      authLogger.debug('发送注册第一阶段请求', request);

      // 后端返回格式: ApiResponse<RegisterPhaseOneResponse>
      const rawResponse = await apiClient.post<RegisterPhaseOneApiResponse>('/api/mobile/auth/register-phase-one', request);

      authLogger.debug('注册第一阶段响应', rawResponse);

      // 适配后端响应格式
      let response: RegisterResponse;

      // 检查是否是 ApiResponse 格式 (有 code, data, success, message)
      if (rawResponse.success !== undefined && rawResponse.data) {
        const data = rawResponse.data;
        response = {
          success: rawResponse.success || rawResponse.code === 200,
          message: rawResponse.message || data.message || '验证成功',
          tempToken: data.tempToken,
          factoryId: data.factoryId,
          phoneNumber: data.phoneNumber,
          loginAccount: data.loginAccount || data.phoneNumber,
          invitedName: data.invitedName,
          invitedRole: data.invitedRole,
          invitedRoleName: data.invitedRoleName,
          expiresAt: data.expiresAt,
          isNewUser: data.isNewUser
        };
      } else {
        // 直接返回格式
        response = rawResponse as RegisterResponse;
      }

      if (response.success && response.tempToken) {
        await StorageService.setSecureItem('temp_token', response.tempToken);
        authLogger.debug('临时Token已保存');
      }

      return response;
    } catch (error) {
      authLogger.error('注册第一阶段失败', error);
      // 处理错误响应
      const apiError = error as ApiErrorResponse;
      if (apiError.response?.data) {
        const errorData = apiError.response.data;
        throw new Error(errorData.message || errorData.error || '手机验证失败');
      }
      throw this.handleAuthError(error);
    }
  }

  // 注册第二阶段 - 完整资料
  static async registerPhaseTwo(request: RegisterPhaseTwoRequest): Promise<RegisterResponse> {
    try {
      const rawResponse = await apiClient.post<RegisterPhaseTwoApiResponse>(
        '/api/mobile/auth/register-phase-two',
        request
      );
      const data = rawResponse.data;
      const response: RegisterResponse = {
        success: Boolean(rawResponse.success && data?.success !== false),
        message: data?.message || rawResponse.message || '开户成功',
        userId: data?.userId,
        username: data?.username,
        role: data?.role,
        registeredAt: data?.registeredAt,
      };

      if (response.success) {
        await StorageService.removeSecureItem('temp_token');
      }

      return response;
    } catch (error) {
      authLogger.error('注册第二阶段失败', error);
      throw this.handleAuthError(error);
    }
  }

  // 用户注册 - 支持新的 /api/auth/register 端点
  static async register(request: RegisterRequest): Promise<LoginResponse> {
    try {
      authLogger.info('开始用户注册流程', { username: request.username });

      // 前端验证
      if (!request.tempToken || !request.username || !request.password || !request.realName || !request.factoryId) {
        throw new Error('缺少必需字段');
      }

      if (request.password.length < 6) {
        throw new Error('密码长度必须至少6个字符');
      }

      // Network pre-check skipped — let HTTP request determine connectivity

      // 调用API端点
      const rawResponse = await NetworkManager.executeWithRetry(
        () => apiClient.post<RegisterApiResponse>('/api/auth/register', {
          tempToken: request.tempToken,
          username: request.username,
          password: request.password,
          realName: request.realName,
          factoryId: request.factoryId,
          department: request.department,
          position: request.position,
          email: request.email
        }),
        { maxRetries: 2, baseDelay: 1000 }
      );

      authLogger.debug('Raw Register API Response', rawResponse);

      // 转换API响应为内部格式
      const response = this.adaptRegisterResponse(rawResponse);

      if (response.success && response.user && response.tokens) {
        // 调试日志: 打印转换后的用户数据（敏感字段会被自动脱敏）
        authLogger.debug('Transformed User Data', response.user);

        // 使用TokenManager保存认证信息
        const tokenData = {
          accessToken: response.tokens.accessToken,
          refreshToken: response.tokens.refreshToken,
          tempToken: response.tokens.tempToken,
          expiresAt: Date.now() + (response.tokens.expiresIn ?? 86400) * 1000, // 默认24小时
          tokenType: response.tokens.tokenType ?? 'Bearer'
        };

        await TokenManager.storeTokens(tokenData);
        await this.saveUserInfo(response.user);

        // 清除临时token
        await StorageService.removeSecureItem('temp_token');

        authLogger.info('用户注册成功', {
          userId: response.user.id,
          role: getUserRole(response.user),
          userType: response.user.userType
        });
      }

      return response;
    } catch (error) {
      authLogger.error('用户注册失败', error);
      throw this.handleAuthError(error);
    }
  }

  // 适配用户注册API响应格式
  private static adaptRegisterResponse(rawResponse: RegisterApiResponse): LoginResponse {
    try {
      // 实际API返回格式: { code, data, message, success, timestamp }
      // data 包含: { accessToken, refreshToken, tokenType, expiresIn, user, message }
      // 需要转换为内部格式: { success, message, user, tokens }

      if (!rawResponse.success || !rawResponse.data) {
        return {
          success: false,
          message: rawResponse.message || '注册失败'
        };
      }

      const data = rawResponse.data;

      // 后端已经返回了完整的user对象，直接使用
      if (data.user) {
        const backendUser = data.user as UserDTO;

        // 确定userType - 如果有factoryId则是factory用户，否则是platform用户
        const userType = backendUser.factoryId ? 'factory' : 'platform';

        let user: User;

        if (userType === 'factory') {
          user = {
            id: backendUser.id,
            username: backendUser.username,
            email: backendUser.email || '',
            phone: backendUser.phone,
            fullName: backendUser.fullName,
            avatar: undefined,
            lastLoginAt: backendUser.lastLogin,
            createdAt: backendUser.createdAt,
            updatedAt: backendUser.updatedAt,
            isActive: backendUser.isActive,
            factoryId: backendUser.factoryId,
            department: backendUser.department as Department,
            position: backendUser.position,
            roleCode: backendUser.roleCode as FactoryRole,
            userType: 'factory',
            factoryUser: {
              role: backendUser.roleCode as FactoryRole,
              factoryId: backendUser.factoryId || '',
              factoryType: (backendUser.factoryType || 'FACTORY').toUpperCase(),
              department: backendUser.department as Department,
              position: backendUser.position,
              permissions: []
            }
          } as FactoryUser;
        } else {
          user = {
            id: backendUser.id,
            username: backendUser.username,
            email: backendUser.email || '',
            phone: backendUser.phone,
            fullName: backendUser.fullName,
            avatar: undefined,
            lastLoginAt: backendUser.lastLogin,
            createdAt: backendUser.createdAt,
            updatedAt: backendUser.updatedAt,
            isActive: backendUser.isActive,
            roleCode: backendUser.roleCode as PlatformRole,
            userType: 'platform',
            platformUser: {
              role: backendUser.roleCode as PlatformRole,
              permissions: []
            }
          } as PlatformUser;
        }

        // 添加权限信息到user对象顶级属性（用于后续权限检查）
        const userWithPermissions = user as User & { permissions: UserPermissions };
        userWithPermissions.permissions = {
          modules: {
            farming_access: false,
            processing_access: false,
            logistics_access: false,
            trace_access: false,
            admin_access: false,
            platform_access: false,
          },
          features: [],
          role: backendUser.roleCode ?? '',
          userType: user.userType,
          level: 0,
          departments: user.userType === 'factory' && (user as FactoryUser).factoryUser.department
            ? [(user as FactoryUser).factoryUser.department] as string[]
            : undefined
        };

        // 转换用户数据（如果需要进一步处理）
        const transformedUser = transformBackendUser(user);

        // 构建tokens对象
        const tokens: AuthTokens = {
          accessToken: data.accessToken,
          refreshToken: data.refreshToken,
          tempToken: undefined,
          expiresIn: data.expiresIn ?? 86400,
          tokenType: data.tokenType ?? 'Bearer'
        };

        return {
          success: true,
          message: data.message || rawResponse.message || '注册成功',
          user: transformedUser,
          tokens: tokens
        };
      }

      // 如果没有user对象，返回错误
      return {
        success: false,
        message: '注册响应中缺少用户信息'
      };
    } catch (error) {
      authLogger.error('适配注册API响应失败', error);
      return {
        success: false,
        message: '注册响应处理失败'
      };
    }
  }

  // 生物识别登录
  static async biometricLogin(options: BiometricAuthOptions = {}): Promise<LoginResponse> {
    try {
      // 检查生物识别是否启用
      const isBiometricEnabled = await BiometricManager.isBiometricLoginEnabled();
      if (!isBiometricEnabled) {
        throw new Error('生物识别登录未启用');
      }

      // 执行生物识别认证
      const authenticated = await BiometricManager.authenticate(options);
      if (!authenticated) {
        throw new Error('生物识别认证失败');
      }

      // 获取已保存的凭据
      const savedCredentials = await BiometricManager.getBiometricCredentials();
      if (!savedCredentials) {
        throw new Error('未找到生物识别登录凭据');
      }

      // Network pre-check skipped — let HTTP request determine connectivity

      // 使用统一登录接口进行生物识别登录验证
      const response = await NetworkManager.executeWithRetry(
        () => apiClient.post<LoginResponse>('/api/mobile/auth/unified-login', {
          username: savedCredentials.username,
          biometricToken: savedCredentials.encryptedToken,
          deviceInfo: savedCredentials.deviceInfo,
          loginType: 'biometric'
        }),
        { maxRetries: 2, baseDelay: 1000 }
      );

      if (response.success && response.user && response.tokens) {
        // 转换后端用户数据为前端格式
        const transformedUser = transformBackendUser(response.user);
        
        // 使用TokenManager保存新的认证信息
        const tokenData = {
          accessToken: response.tokens.accessToken,
          refreshToken: response.tokens.refreshToken,
          tempToken: response.tokens.tempToken,
          expiresAt: Date.now() + response.tokens.expiresIn * 1000,
          tokenType: response.tokens.tokenType ?? 'Bearer'
        };
        
        await TokenManager.storeTokens(tokenData);
        await this.saveUserInfo(transformedUser);
        
        // 更新生物识别凭据
        await BiometricManager.saveBiometricCredentials({
          username: savedCredentials.username,
          encryptedToken: response.tokens.accessToken,
          deviceInfo: savedCredentials.deviceInfo
        });

        authLogger.info('生物识别登录成功', {
          userId: transformedUser.id,
          role: getUserRole(transformedUser),
          userType: transformedUser.userType
        });

        // 返回转换后的用户数据
        response.user = transformedUser;
      }

      return response;

    } catch (error) {
      authLogger.error('生物识别登录失败', error);
      throw this.handleAuthError(error);
    }
  }

  // 设备绑定登录 (一键登录)
  static async deviceLogin(): Promise<LoginResponse> {
    try {
      const deviceId = await StorageService.getSecureItem('device_id');
      const deviceToken = await StorageService.getSecureItem('device_token');
      
      if (!deviceId || !deviceToken) {
        throw new Error('设备未绑定，请先进行正常登录');
      }

      const response = await apiClient.post<LoginResponse>('/api/mobile/auth/device-login', {
        deviceId,
        deviceToken
      });

      if (response.success && response.user && response.tokens) {
        // 转换后端用户数据为前端格式
        const transformedUser = transformBackendUser(response.user);
        
        await this.saveAuthTokens(response.tokens);
        await this.saveUserInfo(transformedUser);
        
        // 返回转换后的用户数据
        response.user = transformedUser;
      }

      return response;
    } catch (error) {
      authLogger.error('设备登录失败', error);
      throw this.handleAuthError(error);
    }
  }

  // 登出
  static async logout(): Promise<void> {
    try {
      // 通知服务器登出 - 调用移动端API端点
      const response = await apiClient.post<LogoutApiResponse>('/api/mobile/auth/logout');

      authLogger.info('服务器登出成功', {
        code: response.code,
        message: response.message,
        timestamp: response.timestamp
      });
    } catch (error) {
      authLogger.warn('服务器登出失败', error);
      // 即使服务器登出失败，也继续清除本地数据
      // 保证用户可以成功退出应用
    } finally {
      // 清除本地认证信息
      await this.clearAuthData();

      authLogger.info('本地认证数据已清除，用户登出完成');
    }
  }

  // 重置密码（忘记密码流程）
  static async resetPassword(
    tempToken: string,
    newPassword: string
  ): Promise<{ success: boolean; message: string }> {
    try {
      // 前端验证
      if (!tempToken) {
        throw new Error('缺少临时令牌，请先验证手机号');
      }

      if (!newPassword) {
        throw new Error('请输入新密码');
      }

      if (newPassword.length < 6 || newPassword.length > 20) {
        throw new Error('新密码长度必须在6-20个字符之间');
      }

      authLogger.info('开始重置密码流程');

      // Network pre-check skipped — let HTTP request determine connectivity

      // 调用API
      const response = await NetworkManager.executeWithRetry(
        () => apiClient.post<ResetPasswordApiResponse>('/api/mobile/auth/reset-password', {
          tempToken,
          newPassword
        }),
        { maxRetries: 2, baseDelay: 1000 }
      );

      if (response.success || response.code === 200) {
        authLogger.info('密码重置成功');
        return {
          success: true,
          message: response.message || '密码重置成功，请使用新密码登录'
        };
      } else {
        throw new Error(response.message || '密码重置失败');
      }
    } catch (error) {
      authLogger.error('密码重置失败', error);
      throw this.handleAuthError(error);
    }
  }

  // 修改密码
  static async changePassword(
    oldPassword: string,
    newPassword: string
  ): Promise<ChangePasswordResponse> {
    try {
      // 前端验证
      if (!oldPassword || !newPassword) {
        throw new Error('原密码和新密码不能为空');
      }

      if (oldPassword === newPassword) {
        throw new Error('新密码不能与旧密码相同');
      }

      if (newPassword.length < 6 || newPassword.length > 20) {
        throw new Error('新密码长度必须在6-20个字符之间');
      }

      authLogger.info('开始修改密码');

      // Network pre-check skipped — let HTTP request determine connectivity

      // 调用API - 注意参数在query string中
      const response = await NetworkManager.executeWithRetry(
        () =>
          apiClient.post<ChangePasswordApiResponse>('/api/auth/change-password', null, {
            params: {
              oldPassword,
              newPassword
            }
          }),
        { maxRetries: 2, baseDelay: 1000 }
      );

      if (response.success) {
        authLogger.info('密码修改成功', {
          message: response.message,
          timestamp: response.timestamp
        });

        return {
          success: true,
          message: response.message || '密码修改成功',
          timestamp: response.timestamp
        };
      } else {
        throw new Error(response.message || '密码修改失败');
      }
    } catch (error) {
      authLogger.error('密码修改失败', error);
      throw this.handleAuthError(error);
    }
  }

  // 检查认证状态
  static async checkAuthStatus(): Promise<{ isAuthenticated: boolean; user: User | null }> {
    try {
      const accessToken = await TokenManager.getValidToken();
      const userInfo = await StorageService.getItem('user_info');

      if (!accessToken || !userInfo) {
        return { isAuthenticated: false, user: null };
      }

      const user = JSON.parse(userInfo) as User;

      // 验证token是否仍然有效
      try {
        await NetworkManager.executeWithRetry(
          () => apiClient.get('/api/auth/profile'),
          { maxRetries: 1, baseDelay: 1000 }
        );
        return { isAuthenticated: true, user };
      } catch (error) {
        // Token可能过期或无效(401错误是正常的),静默清除认证信息
        // 不打印错误日志,避免误导用户
        await this.clearAuthData();
        return { isAuthenticated: false, user: null };
      }
    } catch (error) {
      authLogger.error('检查认证状态失败', error);
      return { isAuthenticated: false, user: null };
    }
  }

  // 保存认证令牌 (已使用TokenManager替代)
  private static async saveAuthTokens(tokens: AuthTokens): Promise<void> {
    // This method is deprecated, use TokenManager.storeTokens instead
    authLogger.warn('saveAuthTokens is deprecated, use TokenManager.storeTokens instead');
  }

  // 保存用户信息
  private static async saveUserInfo(user: User): Promise<void> {
    await StorageService.setItem('user_info', JSON.stringify(user));

    // 只在有角色时保存，避免保存null值
    const role = getUserRole(user);
    if (role) {
      await StorageService.setItem('user_role', role);
    }

    await StorageService.setItem('last_login', new Date().toISOString());
  }

  // 获取生物识别凭据 (已使用BiometricManager替代)
  private static async getSavedBiometricCredentials(): Promise<{
    username: string;
    encryptedPassword: string;
    deviceInfo: any;
  } | null> {
    // This method is deprecated, use BiometricManager.getBiometricCredentials instead
    authLogger.warn('getSavedBiometricCredentials is deprecated, use BiometricManager.getBiometricCredentials instead');
    return null;
  }

  // 保存生物识别凭据 (已使用BiometricManager替代)
  static async saveBiometricCredentials(username: string, password: string, deviceInfo: any): Promise<void> {
    // This method is deprecated, use BiometricManager.saveBiometricCredentials instead
    authLogger.warn('saveBiometricCredentials is deprecated, use BiometricManager.saveBiometricCredentials instead');

    await BiometricManager.saveBiometricCredentials({
      username,
      encryptedToken: password, // This should be the encrypted token, not password
      deviceInfo
    });
  }

  // 清除认证数据
  private static async clearAuthData(): Promise<void> {
    await Promise.all([
      TokenManager.clearTokens(),
      StorageService.removeItem('user_info'),
      StorageService.removeItem('user_role'),
      StorageService.removeItem('last_login'),
      StorageService.removeSecureItem('device_token')
    ]);
  }

  // 权限检查
  static async hasPermission(permission: string): Promise<boolean> {
    try {
      const userInfo = await StorageService.getItem('user_info');
      if (!userInfo) return false;

      const user = JSON.parse(userInfo) as User;
      // 根据用户类型获取权限列表
      const permissions = user.userType === 'platform'
        ? user.platformUser.permissions
        : user.factoryUser.permissions;
      return permissions.includes(permission);
    } catch (error) {
      authLogger.error('权限检查失败', error);
      return false;
    }
  }

  // 角色检查
  static async hasRole(role: UserRole): Promise<boolean> {
    try {
      const user = await this.getCurrentUser();
      if (!user) return false;
      return getUserRole(user) === role;
    } catch (error) {
      authLogger.error('角色检查失败', error);
      return false;
    }
  }

  // 获取当前用户
  static async getCurrentUser(): Promise<User | null> {
    try {
      const userInfo = await StorageService.getItem('user_info');
      return userInfo ? JSON.parse(userInfo) : null;
    } catch (error) {
      authLogger.error('获取当前用户失败', error);
      return null;
    }
  }

  /**
   * 错误处理函数
   * 
   * ⚠️ 测试环境专用：此函数包含详细的网络错误处理逻辑，主要用于测试环境调试。
   * 生产环境应使用更简洁的错误处理，避免暴露过多技术细节。
   * 
   * 注意：Android 9+ 默认禁止 HTTP 流量，如果使用 HTTP 协议，需要：
   * 1. 在 app.json 中设置 "usesCleartextTraffic": true
   * 2. 在 AndroidManifest.xml 中设置 android:usesCleartextTraffic="true"
   * 3. 或配置网络安全策略允许特定域名的 HTTP 流量
   * 
   * @param error 错误对象
   * @returns 用户友好的错误消息
   */
  private static handleAuthError(error: unknown): Error {
    // 1. 优先处理后端返回的错误消息（有 HTTP 响应的情况）
    if (isAxiosError(error)) {
      if (error.response?.data?.message) {
        return new Error(error.response.data.message as string);
      }

      // 2. 处理 HTTP 状态码错误
      const status = error.response?.status;
      if (status) {
      switch (status) {
        case 401:
          return new Error('用户名或密码错误');
        case 403:
          return new Error('没有权限访问');
        case 404:
          return new Error('请求的接口不存在，请检查API地址配置');
        case 500:
        case 502:
        case 503:
          return new Error('服务器错误，请稍后重试');
        default:
          return new Error(`服务器返回错误 (${status})`);
      }
    }
    
      // 3. 处理网络错误（没有 HTTP 响应的情况）
      const errorMessage = error.message || '';
      const errorCode = error.code || '';

      // 连接被拒绝（服务器未启动、端口错误或防火墙阻止）
      if (errorCode === 'ECONNREFUSED' ||
          errorMessage.includes('ECONNREFUSED') ||
          errorMessage.includes('Connection refused')) {
        return new Error('无法连接到服务器，请检查：\n1. 服务器是否正在运行\n2. API地址和端口是否正确\n3. 网络连接是否正常');
      }

      // 域名解析失败（DNS 错误或地址错误）
      if (errorCode === 'ENOTFOUND' ||
          errorMessage.includes('ENOTFOUND') ||
          errorMessage.includes('getaddrinfo failed') ||
          errorMessage.includes('Network request failed')) {
        return new Error('无法解析服务器地址，请检查：\n1. API地址配置是否正确\n2. 网络连接是否正常\n3. DNS设置是否正确');
      }

      // 请求超时
      if (errorCode === 'ETIMEDOUT' ||
          errorMessage.includes('timeout') ||
          errorMessage.includes('timed out') ||
          errorMessage.includes('ETIMEDOUT')) {
        return new Error('请求超时，请检查：\n1. 网络连接是否稳定\n2. 服务器响应是否正常\n3. 防火墙是否阻止连接');
      }

      // Android 9+ HTTP 流量被禁止（Cleartext HTTP traffic not permitted）
      if (errorMessage.includes('Cleartext HTTP traffic') ||
          errorMessage.includes('cleartext') ||
          errorMessage.includes('HTTP traffic not permitted') ||
          (errorMessage.includes('Network') && errorMessage.includes('HTTP'))) {
        return new Error('Android 9+ 禁止 HTTP 流量，请检查：\n1. app.json 中是否设置了 "usesCleartextTraffic": true\n2. AndroidManifest.xml 中是否设置了 android:usesCleartextTraffic="true"\n3. 或使用 HTTPS 协议');
      }

      // 通用网络错误
      if (errorMessage.includes('Network Error') ||
          errorMessage.includes('Network request failed') ||
          errorMessage.includes('Failed to fetch') ||
          errorMessage.includes('Network')) {
        return new Error('网络连接失败，请检查：\n1. 网络连接是否正常\n2. API服务器是否可访问\n3. 防火墙或代理设置是否正确');
      }

      // 4. 如果有错误消息，尝试返回（可能是业务错误）
      if (errorMessage.includes('timeout') || errorMessage.includes('ECONN') || errorMessage.includes('ENOTFOUND')) {
        return new Error('网络连接失败，请检查网络设置');
      }
      return new Error(errorMessage);
    }

    // 4. 非 Axios 错误
    if (error instanceof Error) {
      return new Error(error.message);
    }
    
    // 5. 未知错误
    authLogger.error('未知的认证错误', { error, type: typeof error });
    return new Error('登录失败，请稍后重试');
  }
}
