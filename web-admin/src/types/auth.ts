/**
 * 认证类型定义
 * 与 React Native 移动端保持同步
 * @version 2.0.0 - 14角色系统
 */

// 平台角色 (PlatformAdmin表)
export const PLATFORM_ROLES = {
  PLATFORM_ADMIN: 'platform_admin'
} as const;

// 工厂角色 (User表) - 15角色系统
export const FACTORY_ROLES = {
  // Level 0 - 工厂最高管理
  FACTORY_SUPER_ADMIN: 'factory_super_admin',

  // Level 10 - 职能部门经理
  HR_ADMIN: 'hr_admin',
  PROCUREMENT_MANAGER: 'procurement_manager',
  SALES_MANAGER: 'sales_manager',
  DISPATCHER: 'dispatcher',  // 调度 - 生产调度、数据分析、趋势监控
  WAREHOUSE_MANAGER: 'warehouse_manager',
  EQUIPMENT_ADMIN: 'equipment_admin',
  QUALITY_MANAGER: 'quality_manager',
  FINANCE_MANAGER: 'finance_manager',

  // Level 20 - 车间管理
  WORKSHOP_SUPERVISOR: 'workshop_supervisor',

  // Level 25/28 - 组长
  TEAM_LEADER: 'team_leader',
  GROUP_LEADER: 'group_leader',

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
  DEPARTMENT_ADMIN: 'department_admin',
  PRODUCTION_MANAGER: 'production_manager'  // 已重命名为 dispatcher
} as const;

// 角色元数据
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
  dispatcher: { displayName: '调度', description: '生产调度、数据分析、趋势监控', level: 10, department: 'dispatch' },
  warehouse_manager: { displayName: '仓储主管', description: '库存、出入库、盘点', level: 10, department: 'warehouse' },
  equipment_admin: { displayName: '设备管理员', description: '设备维护、保养、告警', level: 10, department: 'equipment' },
  quality_manager: { displayName: '质量经理', description: '质量体系、质检审核', level: 10, department: 'quality' },
  finance_manager: { displayName: '财务主管', description: '成本核算、费用、报表', level: 10, department: 'finance' },

  // Level 20 - 车间管理
  workshop_supervisor: { displayName: '车间主任', description: '车间日常、人员调度', level: 20, department: 'workshop' },
  team_leader: { displayName: '大组长', description: '大组带班、现场协调', level: 25, department: 'workshop' },
  group_leader: { displayName: '小组长', description: '小组带班、任务分派', level: 28, department: 'workshop' },

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
  production_manager: { displayName: '调度', description: '生产调度、数据分析、趋势监控', level: 10, department: 'dispatch', isDeprecated: true },

  // 平台角色
  platform_admin: { displayName: '平台管理员', description: '平台最高权限', level: 0, department: 'platform' }
};

export type PlatformRole = typeof PLATFORM_ROLES[keyof typeof PLATFORM_ROLES];
export type FactoryRole = typeof FACTORY_ROLES[keyof typeof FACTORY_ROLES];
export type UserRole = PlatformRole | FactoryRole;

// 部门定义
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
  id: number;
  username: string;
  email: string;
  phone?: string;
  fullName?: string;
  avatar?: string;
  lastLoginAt?: string;
  createdAt: string;
  updatedAt: string;
  isActive: boolean;
  factoryId?: string;
  department?: Department;
  position?: string;
  roleCode?: FactoryRole | PlatformRole;
  role?: string;
}

// 平台用户接口
export interface PlatformUser extends BaseUser {
  userType: 'platform';
  platformUser: {
    role: PlatformRole;
    permissions: string[];
  };
  factoryUser?: undefined;
}

// 工厂类型
export type FactoryType = 'FACTORY' | 'RESTAURANT' | 'HEADQUARTERS' | 'BRANCH' | 'CENTRAL_KITCHEN';
export type BusinessDomain = 'FACTORY' | 'RESTAURANT';

// 工厂用户接口
export interface FactoryUser extends BaseUser {
  userType: 'factory';
  factoryUser: {
    role: FactoryRole;
    factoryId: string;
    factoryName?: string;
    factoryLogoUrl?: string;
    factoryType?: FactoryType;
    businessDomain?: BusinessDomain;
    department?: Department;
    position?: string;
    permissions: string[];
  };
  platformUser?: undefined;
}

// 统一用户类型
export type User = PlatformUser | FactoryUser;

// 认证令牌接口
export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  tokenType: string;
}

// 登录请求接口
export interface LoginRequest {
  username: string;
  password: string;
  rememberMe?: boolean;
}

// 登录响应接口
export interface LoginResponse {
  success: boolean;
  message: string;
  user?: User;
  tokens?: AuthTokens;
}

// 类型守卫函数
export function isPlatformUser(user: User | null | undefined): user is PlatformUser {
  return user?.userType === 'platform' && !!user.platformUser;
}

export function isFactoryUser(user: User | null | undefined): user is FactoryUser {
  return user?.userType === 'factory' && !!user.factoryUser;
}

export function getUserRole(user: User | null | undefined): string {
  if (!user) return 'viewer';
  if (isPlatformUser(user)) return user.platformUser.role || 'viewer';
  if (isFactoryUser(user)) return user.factoryUser.role || 'viewer';
  return 'viewer';
}

export function getFactoryId(user: User | null | undefined): string {
  if (!user) return '';
  if (isFactoryUser(user)) return user.factoryUser.factoryId || '';
  return '';
}
