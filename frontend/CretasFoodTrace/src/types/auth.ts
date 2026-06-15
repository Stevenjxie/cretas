// 用户角色定义 - 完全匹配后端数据库
// 平台角色 (PlatformAdmin表)
export const PLATFORM_ROLES = {
  PLATFORM_ADMIN: 'platform_admin'  // 统一的平台管理员角色
} as const;

// 工厂角色 (User表) - 14角色系统
export const FACTORY_ROLES = {
  // Level 0 - 工厂最高管理
  FACTORY_SUPER_ADMIN: 'factory_super_admin',

  // Level 10 - 职能部门经理
  HR_ADMIN: 'hr_admin',
  PROCUREMENT_MANAGER: 'procurement_manager',
  SALES_MANAGER: 'sales_manager',
  PRODUCTION_MANAGER: 'production_manager',
  WAREHOUSE_MANAGER: 'warehouse_manager',
  EQUIPMENT_ADMIN: 'equipment_admin',
  QUALITY_MANAGER: 'quality_manager',
  FINANCE_MANAGER: 'finance_manager',
  CASHIER: 'cashier',

  // Level 15 - 调度管理
  DISPATCHER: 'dispatcher',

  // Level 20 - 车间管理
  WORKSHOP_SUPERVISOR: 'workshop_supervisor',

  // Level 30 - 一线员工
  QUALITY_INSPECTOR: 'quality_inspector',
  OPERATOR: 'operator',
  WAREHOUSE_WORKER: 'warehouse_worker',

  // Level 50 - 查看者
  VIEWER: 'viewer',

  // Level 99 - 未激活
  UNACTIVATED: 'unactivated',

  // 已废弃 (向后兼容)
  PERMISSION_ADMIN: 'permission_admin',
  DEPARTMENT_ADMIN: 'department_admin'
} as const;

// 角色元数据 - 与后端 FactoryUserRole 保持同步
export interface RoleMetadata {
  displayName: string;
  description: string;
  level: number;
  department: string;
  isDeprecated?: boolean;
}

export const ROLE_METADATA: Record<string, RoleMetadata> = {
  // Level 0
  factory_super_admin: { displayName: '工厂总监', description: '拥有工厂所有权限', level: 0, department: 'all' },

  // Level 10 - 职能部门经理
  hr_admin: { displayName: 'HR管理员', description: '人事管理、考勤、薪资', level: 10, department: 'hr' },
  procurement_manager: { displayName: '采购主管', description: '供应商、采购、成本', level: 10, department: 'procurement' },
  sales_manager: { displayName: '销售主管', description: '客户、订单、出货', level: 10, department: 'sales' },
  production_manager: { displayName: '生产经理', description: '车间统管、生产计划', level: 10, department: 'production' },
  warehouse_manager: { displayName: '仓储主管', description: '库存、出入库、盘点', level: 10, department: 'warehouse' },
  equipment_admin: { displayName: '设备管理员', description: '设备维护、保养、告警', level: 10, department: 'equipment' },
  quality_manager: { displayName: '质量经理', description: '质量体系、质检审核', level: 10, department: 'quality' },
  finance_manager: { displayName: '财务主管', description: '成本核算、费用、报表', level: 10, department: 'finance' },
  cashier: { displayName: '出纳', description: '付款确认、收付款执行', level: 10, department: 'finance' },

  // Level 15 - 调度管理
  dispatcher: { displayName: '调度员', description: 'AI智能调度、人员分配、生产排程', level: 15, department: 'scheduling' },

  // Level 20 - 车间管理
  workshop_supervisor: { displayName: '车间主任', description: '车间日常、人员调度', level: 20, department: 'workshop' },

  // Level 30 - 一线员工
  quality_inspector: { displayName: '质检员', description: '执行质检、提交报告', level: 30, department: 'quality' },
  operator: { displayName: '操作员', description: '生产执行、打卡记录', level: 30, department: 'production' },
  warehouse_worker: { displayName: '仓库员', description: '出入库操作、盘点', level: 30, department: 'warehouse' },

  // Level 50 - 查看者
  viewer: { displayName: '查看者', description: '只读访问', level: 50, department: 'none' },

  // Level 99 - 未激活
  unactivated: { displayName: '未激活', description: '账户未激活', level: 99, department: 'none' },

  // 已废弃角色 (向后兼容)
  permission_admin: { displayName: '权限管理员', description: '管理用户权限和角色', level: 10, department: 'system', isDeprecated: true },
  department_admin: { displayName: '部门管理员', description: '管理部门相关业务', level: 15, department: 'department', isDeprecated: true },

  // 平台角色
  platform_admin: { displayName: '平台管理员', description: '平台最高权限', level: 0, department: 'platform' }
};

// 统一角色定义 (兼容旧代码)
export const USER_ROLES = {
  // 平台角色
  PLATFORM_ADMIN: 'platform_admin',
  // 工厂角色 - 新增
  FACTORY_SUPER_ADMIN: 'factory_super_admin',
  HR_ADMIN: 'hr_admin',
  PROCUREMENT_MANAGER: 'procurement_manager',
  SALES_MANAGER: 'sales_manager',
  PRODUCTION_MANAGER: 'production_manager',
  WAREHOUSE_MANAGER: 'warehouse_manager',
  EQUIPMENT_ADMIN: 'equipment_admin',
  QUALITY_MANAGER: 'quality_manager',
  FINANCE_MANAGER: 'finance_manager',
  CASHIER: 'cashier',
  DISPATCHER: 'dispatcher',
  WORKSHOP_SUPERVISOR: 'workshop_supervisor',
  QUALITY_INSPECTOR: 'quality_inspector',
  OPERATOR: 'operator',
  WAREHOUSE_WORKER: 'warehouse_worker',
  VIEWER: 'viewer',
  // 已废弃 (向后兼容)
  PERMISSION_ADMIN: 'permission_admin',
  DEPARTMENT_ADMIN: 'department_admin'
} as const;

export type PlatformRole = typeof PLATFORM_ROLES[keyof typeof PLATFORM_ROLES];
export type FactoryRole = typeof FACTORY_ROLES[keyof typeof FACTORY_ROLES];
export type UserRole = PlatformRole | FactoryRole;

// 用户权限接口
export interface UserPermissions {
  modules: {
    farming_access: boolean;
    processing_access: boolean;
    logistics_access: boolean;
    trace_access: boolean;
    admin_access: boolean;
    platform_access: boolean;
    debug_access?: boolean;
    system_config?: boolean;
  };
  features: string[];
  role: string;
  userType: 'platform' | 'factory';
  level?: number;
  departments?: string[];
}

// 部门定义 - 匹配后端数据库
export const DEPARTMENTS = {
  FARMING: 'farming',
  PROCESSING: 'processing',
  LOGISTICS: 'logistics',
  QUALITY: 'quality',
  MANAGEMENT: 'management'
} as const;

export type Department = typeof DEPARTMENTS[keyof typeof DEPARTMENTS];

// 基础用户接口
interface BaseUser {
  id: number;  // Backend uses Long, which serializes as number
  username: string;
  email: string;
  phone?: string;
  fullName?: string;
  avatar?: string;
  lastLoginAt?: string;
  createdAt: string;
  updatedAt: string;
  isActive: boolean;
  // Common shortcut properties (for easier access without type guards)
  factoryId?: string;
  department?: Department;
  position?: string;
  roleCode?: FactoryRole | PlatformRole;
  role?: string;
}

// 平台用户接口 (PlatformAdmin表)
export interface PlatformUser extends BaseUser {
  userType: 'platform';
  platformUser: {
    role: PlatformRole;
    permissions: string[];
  };
  // Optional factory properties (always undefined for platform users)
  factoryUser?: undefined;
}

// 工厂类型
export type FactoryType = 'FACTORY' | 'RESTAURANT' | 'HEADQUARTERS' | 'BRANCH' | 'CENTRAL_KITCHEN';

// 工厂用户接口 (User表)
export interface FactoryUser extends BaseUser {
  userType: 'factory';
  factoryUser: {
    role: FactoryRole;
    factoryId: string;
    factoryType?: FactoryType;
    department?: Department;
    position?: string;
    permissions: string[];
  };
  // Optional platform properties (always undefined for factory users)
  platformUser?: undefined;
}

// 统一用户类型
export type User = PlatformUser | FactoryUser;

// 认证令牌接口
export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  tempToken?: string;
  expiresIn: number;
  tokenType: string;
}

// 登录请求接口
export interface LoginRequest {
  username: string;
  password: string;
  deviceInfo?: {
    deviceId: string;
    deviceModel: string;
    osVersion: string;
    appVersion: string;
    platform: 'ios' | 'android';
  };
  factoryId?: string;
  rememberMe?: boolean;
  biometricEnabled?: boolean;
}

// 登录响应接口
export interface LoginResponse {
  success: boolean;
  message: string;
  user?: User;
  tokens?: AuthTokens;
  requiresTwoFactor?: boolean;
  tempToken?: string;
}

// 修改密码请求接口
export interface ChangePasswordRequest {
  oldPassword: string;
  newPassword: string;
}

// 修改密码响应接口
export interface ChangePasswordResponse {
  success: boolean;
  message: string;
  timestamp?: string;
}

// 注册请求接口 (第一阶段 - 手机验证)
export interface RegisterPhaseOneRequest {
  phoneNumber: string;
  factoryId?: string; // 可选，如不提供则通过手机号从白名单推断
}

// 注册请求接口 (第二阶段 - 完整资料)
export interface RegisterPhaseTwoRequest {
  phoneNumber: string;
  verificationCode: string;
  username: string;
  password: string;
  realName: string;
  email?: string;
  departmentId?: string;
  role?: UserRole;
  deviceInfo: {
    deviceId: string;
    deviceModel: string;
    osVersion: string;
    appVersion: string;
    platform: 'ios' | 'android';
  };
}

// 注册响应接口
export interface RegisterResponse {
  success: boolean;
  message: string;
  tempToken?: string;
  expiresAt?: number; // 临时令牌过期时间（时间戳）
  phoneNumber?: string;
  factoryId?: string;
  isNewUser?: boolean; // 是否是新用户
  user?: User;
  tokens?: AuthTokens;
  nextStep?: 'phone_verification' | 'complete_profile' | 'done';
}

// 认证状态接口
export interface AuthState {
  isAuthenticated: boolean;
  isLoading: boolean;
  user: User | null;
  tokens: AuthTokens | null;
  error: string | null;
  deviceBound: boolean;
  biometricEnabled: boolean;
  lastLoginMethod: 'password' | 'biometric' | 'device' | null;
}

// 权限检查接口
export interface PermissionCheckOptions {
  requireAll?: boolean; // 是否需要所有权限都满足
  checkLevel?: boolean; // 是否检查权限级别
  checkDepartment?: boolean; // 是否检查部门权限
}

// 生物识别认证选项
export interface BiometricAuthOptions {
  promptMessage?: string;
  cancelButtonText?: string;
  fallbackToDevicePasscode?: boolean;
  disableDeviceFallback?: boolean;
}

// 设备绑定信息
export interface DeviceBindingInfo {
  deviceId: string;
  deviceName: string;
  bindingDate: string;
  lastUsed: string;
  isActive: boolean;
  platform: 'ios' | 'android';
  appVersion: string;
}

// 用户注册请求接口 (新的 /api/auth/register)
export interface RegisterRequest {
  tempToken: string;           // 临时令牌（验证手机后获得）
  username: string;            // 用户名
  password: string;            // 密码
  realName: string;            // 真实姓名
  factoryId: string;           // 工厂ID
  department?: string;         // 部门（可选）
  position?: string;           // 职位（可选）
  email?: string;              // 邮箱（可选）
}

// 用户注册响应接口 (新的 /api/auth/register)
export interface RegisterResponseData {
  accessToken: string;         // 访问令牌
  refreshToken: string;        // 刷新令牌
  tokenType: string;           // 令牌类型
  expiresIn: number;           // 令牌过期时间（秒）
  user: UserDTO;               // 用户信息
  message: string;             // 提示消息
}

// UserDTO - 用户数据传输对象
export interface UserDTO {
  id: number;
  username: string;
  email?: string;
  phone?: string;
  fullName: string;
  isActive: boolean;
  roleCode: FactoryRole | PlatformRole;
  roleDisplayName: string;
  factoryId?: string;
  factoryType?: FactoryType;
  department?: Department;
  departmentDisplayName?: string;
  position?: string;
  ccrRate?: number;
  monthlySalary?: number;
  expectedWorkMinutes?: number;
  createdAt: string;
  updatedAt: string;
  lastLogin?: string;
}

// API 错误响应
export interface AuthError {
  code: string;
  message: string;
  details?: any;
  timestamp: string;
}

// ==================== 类型守卫函数 ====================
// ✅ P1-2: 添加类型守卫函数，避免使用 as any

/**
 * 检查用户是否为平台用户
 */
export function isPlatformUser(user: User | null | undefined): user is PlatformUser {
  return user?.userType === 'platform' && !!user.platformUser;
}

/**
 * 检查用户是否为工厂用户
 */
export function isFactoryUser(user: User | null | undefined): user is FactoryUser {
  return user?.userType === 'factory' && !!user.factoryUser;
}

/**
 * 安全获取用户角色
 */
export function getUserRole(user: User | null | undefined): string {
  if (!user) return 'viewer';

  if (isPlatformUser(user)) {
    return user.platformUser.role || 'viewer';
  }

  if (isFactoryUser(user)) {
    return user.factoryUser.role || 'viewer';
  }

  return 'viewer';
}

/**
 * 安全获取工厂ID
 */
export function getFactoryId(user: User | null | undefined): string {
  if (!user) return '';

  if (isPlatformUser(user)) {
    // 平台用户可能没有factoryId
    return '';
  }

  if (isFactoryUser(user)) {
    return user.factoryUser.factoryId || '';
  }

  return '';
}

/**
 * 安全获取部门
 */
export function getDepartment(user: User | null | undefined): Department | undefined {
  if (!user) return undefined;

  if (isFactoryUser(user)) {
    return user.factoryUser.department;
  }

  return undefined;
}

/**
 * 安全获取用户权限数组
 */
export function getUserPermissions(user: User | null | undefined): string[] {
  if (!user) return [];

  if (isPlatformUser(user)) {
    return user.platformUser.permissions || [];
  }

  if (isFactoryUser(user)) {
    return user.factoryUser.permissions || [];
  }

  return [];
}

/**
 * 根据角色获取默认权限
 * 权限格式: module:action (如 production:read, quality:write)
 */
function getDefaultPermissionsForRole(role: string): string[] {
  const rolePermissions: Record<string, string[]> = {
    // 平台角色
    platform_admin: ['platform_access', 'admin_access', 'processing_access', 'farming_access', 'logistics_access', 'trace_access'],

    // Level 0 - 工厂总监 (所有权限)
    factory_super_admin: [
      'admin_access', 'processing_access', 'farming_access', 'logistics_access', 'trace_access',
      'dashboard:read', 'dashboard:write',
      'production:read', 'production:write',
      'warehouse:read', 'warehouse:write',
      'quality:read', 'quality:write',
      'procurement:read', 'procurement:write',
      'sales:read', 'sales:write',
      'hr:read', 'hr:write',
      'equipment:read', 'equipment:write',
      'finance:read', 'finance:write',
      'system:read', 'system:write'
    ],

    // Level 10 - 职能部门经理
    hr_admin: ['hr:read', 'hr:write', 'dashboard:read'],
    procurement_manager: ['procurement:read', 'procurement:write', 'warehouse:read', 'dashboard:read'],
    sales_manager: ['sales:read', 'sales:write', 'warehouse:read', 'dashboard:read'],
    production_manager: [
      'production:read', 'production:write',
      'warehouse:read', 'quality:read', 'procurement:read',
      'hr:read', 'equipment:read', 'system:read',
      'dashboard:read', 'dashboard:write',
      'processing_access', 'admin_access'
    ],
    warehouse_manager: ['warehouse:read', 'warehouse:write', 'production:read', 'dashboard:read', 'dashboard:write'],
    equipment_admin: ['equipment:read', 'equipment:write', 'dashboard:read'],
    quality_manager: ['quality:read', 'quality:write', 'production:read', 'dashboard:read'],
    finance_manager: [
      'finance:read', 'finance:write',
      'production:read', 'procurement:read', 'sales:read',
      'dashboard:read'
    ],
    cashier: [
      'finance:read', 'finance:write',
      'procurement:read', 'sales:read',
      'dashboard:read'
    ],

    // Level 15 - 调度管理
    dispatcher: [
      'scheduling:read', 'scheduling:write',
      'production:read', 'production:write',
      'hr:read', 'hr:write',
      'equipment:read', 'warehouse:read',
      'quality:read', 'dashboard:read', 'dashboard:write',
      'processing_access', 'admin_access'
    ],

    // Level 20 - 车间管理
    workshop_supervisor: [
      'production:read', 'production:write',
      'warehouse:read', 'quality:write',
      'hr:read', 'equipment:read',
      'dashboard:read',
      'processing_access'
    ],

    // Level 30 - 一线员工
    quality_inspector: ['quality:write', 'production:read', 'dashboard:read', 'processing_access'],
    operator: ['production:write', 'dashboard:read', 'processing_access'],
    warehouse_worker: ['warehouse:write', 'dashboard:read'],

    // Level 50 - 查看者
    viewer: [
      'dashboard:read', 'production:read', 'warehouse:read',
      'quality:read', 'procurement:read', 'sales:read',
      'hr:read', 'equipment:read',
      'trace_access'
    ],

    // 已废弃角色 (向后兼容)
    permission_admin: ['admin_access', 'system:read', 'system:write'],
    department_admin: ['processing_access', 'production:read', 'production:write'],
  };
  return rolePermissions[role] || [];
}

/**
 * 检查用户是否有某个权限
 * @param user 用户对象
 * @param permission 权限字符串
 */
export function hasPermission(
  user: User | null | undefined,
  permission: string
): boolean {
  if (!user) return false;

  // 获取用户角色
  const role = isFactoryUser(user)
    ? user.factoryUser.role
    : isPlatformUser(user)
      ? user.platformUser.role
      : '';

  // 获取用户权限数组
  const permissions = getUserPermissions(user);

  // 如果权限数组为空，根据角色自动授权
  if (permissions.length === 0 && role) {
    const defaultPermissions = getDefaultPermissionsForRole(role);
    if (defaultPermissions.includes(permission)) {
      return true;
    }
  }

  // 部门管理员特殊处理：自动授予所在部门的访问权限
  if (isFactoryUser(user) && user.factoryUser.role === 'department_admin') {
    const department = user.factoryUser.department;
    const departmentPermissionMap: Record<string, string> = {
      'processing': 'processing_access',
      'farming': 'farming_access',
      'logistics': 'logistics_access',
      'quality': 'quality_access',
    };

    if (department && departmentPermissionMap[department] === permission) {
      return true;
    }
  }

  // 检查权限数组
  return permissions.includes(permission);
}

/**
 * 安全获取角色代码 (roleCode)
 */
export function getRoleCode(user: User | null | undefined): string | undefined {
  if (!user) return undefined;

  if (isPlatformUser(user)) {
    return user.platformUser.role;
  }

  if (isFactoryUser(user)) {
    return user.factoryUser.role;
  }

  return undefined;
}
