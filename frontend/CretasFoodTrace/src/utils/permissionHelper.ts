import { User, FACTORY_ROLES, PLATFORM_ROLES, ROLE_METADATA, RoleMetadata } from '../types/auth';

/**
 * 权限检查工具函数
 * 用于统一管理屏幕的权限验证逻辑
 *
 * 权限格式: module:action (如 production:read, quality:write)
 * 模块: dashboard, production, warehouse, quality, procurement, sales, hr, equipment, finance, system
 * 动作: read, write, read_write
 *
 * @version 2.0.0 - 支持14角色系统
 */

// ==================== 角色元数据访问 ====================

/**
 * 获取角色元数据
 */
export function getRoleMetadata(roleCode: string): RoleMetadata | undefined {
  return ROLE_METADATA[roleCode];
}

/**
 * 获取角色等级
 */
export function getRoleLevel(roleCode: string): number {
  return ROLE_METADATA[roleCode]?.level ?? 99;
}

/**
 * 获取角色部门
 */
export function getRoleDepartment(roleCode: string): string {
  return ROLE_METADATA[roleCode]?.department ?? 'none';
}

// ==================== 用户角色获取 ====================

/**
 * 获取用户的标准化角色代码
 */
export function getRoleCode(user: User | null | undefined): string {
  if (!user) return 'viewer';

  // 平台管理员
  if (user.userType === 'platform') {
    return user.platformUser?.role || PLATFORM_ROLES.PLATFORM_ADMIN;
  }

  // 工厂用户
  const rawRole = user.factoryUser?.role;
  const position = user.factoryUser?.position;

  // 向后兼容：将旧的position值映射到新角色
  if (position === 'proc_admin') {
    return FACTORY_ROLES.PRODUCTION_MANAGER;
  }

  return rawRole || FACTORY_ROLES.VIEWER;
}

/**
 * 获取用户权限等级
 */
export function getUserLevel(user: User | null | undefined): number {
  if (!user) return 99;
  const roleCode = getRoleCode(user);
  return getRoleLevel(roleCode);
}

// ==================== 角色类型检查 ====================

/**
 * 检查用户是否为平台管理员
 */
export function isPlatformAdmin(user: User | null | undefined): boolean {
  if (!user) return false;
  return user.userType === 'platform';
}

/**
 * 检查用户是否为工厂超级管理员
 */
export function isSuperAdmin(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.FACTORY_SUPER_ADMIN;
}

/**
 * 检查用户是否为管理层 (Level <= 10)
 */
export function isManager(user: User | null | undefined): boolean {
  if (!user) return false;
  const level = getUserLevel(user);
  return level <= 10;
}

/**
 * 检查用户是否为一线员工 (Level 30)
 */
export function isWorker(user: User | null | undefined): boolean {
  if (!user) return false;
  const level = getUserLevel(user);
  return level === 30;
}

/**
 * 检查用户是否可以管理目标角色
 */
export function canManageRole(user: User | null | undefined, targetRoleCode: string): boolean {
  if (!user) return false;
  const userLevel = getUserLevel(user);
  const targetLevel = getRoleLevel(targetRoleCode);
  return userLevel < targetLevel;
}

// ==================== 新角色检查函数 ====================

/**
 * 检查用户是否为生产经理
 */
export function isProductionManager(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.PRODUCTION_MANAGER;
}

/**
 * 检查用户是否为质量经理
 */
export function isQualityManager(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.QUALITY_MANAGER;
}

/**
 * 检查用户是否为车间主任
 */
export function isWorkshopSupervisor(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.WORKSHOP_SUPERVISOR;
}

/**
 * 检查用户是否为质检员
 */
export function isQualityInspector(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.QUALITY_INSPECTOR;
}

/**
 * 检查用户是否为操作员
 */
export function isOperator(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.OPERATOR;
}

/**
 * 检查用户是否为仓储相关角色 (仓储主管 或 仓库员)
 */
export function isWarehouseRole(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.WAREHOUSE_MANAGER ||
         roleCode === FACTORY_ROLES.WAREHOUSE_WORKER;
}

/**
 * 检查用户是否为HR管理员
 */
export function isHrAdmin(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.HR_ADMIN;
}

/**
 * 检查用户是否为设备管理员
 */
export function isEquipmentAdmin(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.EQUIPMENT_ADMIN;
}

// ==================== 向后兼容函数 (已废弃角色) ====================

/**
 * 检查用户是否为权限管理员 (已废弃，保留向后兼容)
 * @deprecated 使用 isSuperAdmin 或 isManager 替代
 */
export function isPermissionAdmin(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.PERMISSION_ADMIN ||
         roleCode === FACTORY_ROLES.FACTORY_SUPER_ADMIN;
}

/**
 * 检查用户是否为部门管理员 (已废弃，保留向后兼容)
 * @deprecated 使用具体的经理角色检查函数替代
 */
export function isDepartmentAdmin(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.DEPARTMENT_ADMIN ||
         roleCode === FACTORY_ROLES.PRODUCTION_MANAGER;
}

// ==================== 模块权限检查 ====================

/**
 * 权限矩阵 - 与后端 PermissionServiceImpl 保持同步
 */
const PERMISSION_MATRIX: Record<string, Record<string, string>> = {
  factory_super_admin: {
    dashboard: 'read_write', production: 'read_write', warehouse: 'read_write',
    quality: 'read_write', procurement: 'read_write', sales: 'read_write',
    hr: 'read_write', equipment: 'read_write', finance: 'read_write', system: 'read_write'
  },
  production_manager: {
    dashboard: 'read_write', production: 'read_write', warehouse: 'read',
    quality: 'read', procurement: 'read', hr: 'read', equipment: 'read', system: 'read'
  },
  quality_manager: { dashboard: 'read', production: 'read', quality: 'read_write' },
  workshop_supervisor: {
    dashboard: 'read', production: 'read_write', warehouse: 'read',
    quality: 'write', hr: 'read', equipment: 'read'
  },
  quality_inspector: { dashboard: 'read', production: 'read', quality: 'write' },
  operator: { dashboard: 'read', production: 'write' },
  warehouse_manager: { dashboard: 'read_write', warehouse: 'read_write', production: 'read' },
  warehouse_worker: { dashboard: 'read', warehouse: 'write' },
  hr_admin: { dashboard: 'read', hr: 'read_write' },
  equipment_admin: { dashboard: 'read', equipment: 'read_write' },
  procurement_manager: { dashboard: 'read', procurement: 'read_write', warehouse: 'read' },
  sales_manager: { dashboard: 'read', sales: 'read_write', warehouse: 'read' },
  finance_manager: {
    dashboard: 'read', finance: 'read_write',
    production: 'read', procurement: 'read', sales: 'read'
  },
  cashier: {
    dashboard: 'read', finance: 'read_write',
    procurement: 'read', sales: 'read'
  },
  viewer: {
    dashboard: 'read', production: 'read', warehouse: 'read', quality: 'read',
    procurement: 'read', sales: 'read', hr: 'read', equipment: 'read'
  },
  // 向后兼容
  permission_admin: {
    dashboard: 'read_write', production: 'read_write', warehouse: 'read_write',
    quality: 'read_write', procurement: 'read_write', sales: 'read_write',
    hr: 'read_write', equipment: 'read_write', finance: 'read_write', system: 'read_write'
  },
  department_admin: {
    dashboard: 'read_write', production: 'read_write', warehouse: 'read',
    quality: 'read', procurement: 'read', hr: 'read', equipment: 'read', system: 'read'
  }
};

/**
 * 检查用户是否有指定模块的权限
 * @param user 用户对象
 * @param module 模块名称 (dashboard, production, warehouse, quality, etc.)
 * @param action 动作类型 (read, write)
 */
export function hasModulePermission(
  user: User | null | undefined,
  module: string,
  action: 'read' | 'write' = 'read'
): boolean {
  if (!user) return false;

  // 平台管理员拥有所有权限
  if (isPlatformAdmin(user)) return true;

  const roleCode = getRoleCode(user);
  const rolePerms = PERMISSION_MATRIX[roleCode];

  if (!rolePerms) return false;

  const permType = rolePerms[module];
  if (!permType || permType === 'none') return false;

  if (action === 'read') {
    return permType.includes('read');
  }
  if (action === 'write') {
    return permType.includes('write');
  }

  return false;
}

/**
 * 检查用户是否可以访问指定模块
 */
export function canAccessModule(user: User | null | undefined, module: string): boolean {
  return hasModulePermission(user, module, 'read') || hasModulePermission(user, module, 'write');
}

// ==================== 功能权限检查 ====================

/**
 * 检查用户是否可以管理基础数据
 * 权限：平台管理员、工厂超管、管理层 (Level <= 10)
 */
export function canManageBasicData(user: User | null | undefined): boolean {
  if (!user) return false;
  return isPlatformAdmin(user) || isSuperAdmin(user) || isManager(user);
}

/**
 * 检查用户是否可以管理用户
 * 权限：平台管理员、工厂超管、HR管理员
 */
export function canManageUsers(user: User | null | undefined): boolean {
  if (!user) return false;
  return isPlatformAdmin(user) || isSuperAdmin(user) || isHrAdmin(user);
}

/**
 * 检查用户是否可以管理部门
 * 权限：平台管理员、工厂超管
 */
export function canManageDepartments(user: User | null | undefined): boolean {
  if (!user) return false;
  return isPlatformAdmin(user) || isSuperAdmin(user);
}

/**
 * 检查用户是否可以管理权限
 * 权限：平台管理员、工厂超管
 */
export function canManagePermissions(user: User | null | undefined): boolean {
  if (!user) return false;
  return isPlatformAdmin(user) || isSuperAdmin(user);
}

/**
 * 检查用户是否可以查看报表
 * 权限：平台管理员、工厂超管、所有管理层
 */
export function canViewReports(user: User | null | undefined): boolean {
  if (!user) return false;
  return isPlatformAdmin(user) || isSuperAdmin(user) || isManager(user);
}

/**
 * 检查用户是否可以管理生产
 */
export function canManageProduction(user: User | null | undefined): boolean {
  return hasModulePermission(user, 'production', 'write');
}

/**
 * 检查用户是否可以管理仓库
 */
export function canManageWarehouse(user: User | null | undefined): boolean {
  return hasModulePermission(user, 'warehouse', 'write');
}

/**
 * 检查用户是否可以管理质量
 */
export function canManageQuality(user: User | null | undefined): boolean {
  return hasModulePermission(user, 'quality', 'write');
}

/**
 * 检查用户是否可以管理设备
 */
export function canManageEquipment(user: User | null | undefined): boolean {
  return hasModulePermission(user, 'equipment', 'write');
}

/**
 * 检查用户是否可以管理HR
 */
export function canManageHR(user: User | null | undefined): boolean {
  return hasModulePermission(user, 'hr', 'write');
}

// ==================== 工具函数 ====================

/**
 * 获取工厂ID（如果是工厂用户）
 */
export function getFactoryId(user: User | null | undefined): string | undefined {
  if (!user || user.userType !== 'factory') return undefined;
  return user.factoryUser?.factoryId;
}

/**
 * 获取用户角色的中文名称
 */
export function getRoleName(user: User | null | undefined): string {
  if (!user) return '访客';

  if (user.userType === 'platform') {
    return '平台管理员';
  }

  const roleCode = getRoleCode(user);
  const metadata = ROLE_METADATA[roleCode];

  return metadata?.displayName || '未知角色';
}

/**
 * 获取用户可访问的模块列表
 */
export function getAccessibleModules(user: User | null | undefined): string[] {
  if (!user) return [];

  if (isPlatformAdmin(user)) {
    return ['dashboard', 'production', 'warehouse', 'quality', 'procurement',
            'sales', 'hr', 'equipment', 'finance', 'system'];
  }

  const roleCode = getRoleCode(user);
  const rolePerms = PERMISSION_MATRIX[roleCode];

  if (!rolePerms) return [];

  return Object.entries(rolePerms)
    .filter(([, permType]) => permType && permType !== 'none')
    .map(([module]) => module);
}

/**
 * 权限日志记录辅助函数
 * 用于调试权限问题
 */
export function getPermissionDebugInfo(user: User | null | undefined) {
  if (!user) {
    return {
      userType: 'none',
      roleCode: 'viewer',
      roleLevel: 99,
      permissions: {
        canManageBasicData: false,
        canManageUsers: false,
        canManageDepartments: false,
        canManagePermissions: false,
        canViewReports: false,
        canManageProduction: false,
        canManageWarehouse: false,
        canManageQuality: false,
      },
      accessibleModules: [],
    };
  }

  const roleCode = getRoleCode(user);

  return {
    userType: user.userType,
    roleCode,
    roleName: getRoleName(user),
    roleLevel: getUserLevel(user),
    roleDepartment: getRoleDepartment(roleCode),
    factoryId: getFactoryId(user),
    rawRole: user.userType === 'factory' ? user.factoryUser?.role : user.platformUser?.role,
    position: user.userType === 'factory' ? user.factoryUser?.position : undefined,
    permissions: {
      canManageBasicData: canManageBasicData(user),
      canManageUsers: canManageUsers(user),
      canManageDepartments: canManageDepartments(user),
      canManagePermissions: canManagePermissions(user),
      canViewReports: canViewReports(user),
      canManageProduction: canManageProduction(user),
      canManageWarehouse: canManageWarehouse(user),
      canManageQuality: canManageQuality(user),
    },
    accessibleModules: getAccessibleModules(user),
  };
}

// ==================== SmartBI 权限检查 ====================

/**
 * 检查用户是否可以访问 SmartBI 模块
 */
export function canAccessSmartBI(user: User | null): boolean {
  if (!user) return false;
  return hasModulePermission(user, 'analytics', 'read') ||
         hasModulePermission(user, 'sales', 'read') ||
         hasModulePermission(user, 'finance', 'read');
}

/**
 * 检查用户是否可以上传 Excel
 */
export function canUploadExcel(user: User | null): boolean {
  if (!user) return false;
  return hasModulePermission(user, 'analytics', 'write');
}

/**
 * 检查用户是否可以访问销售分析
 */
export function canAccessSalesAnalysis(user: User | null): boolean {
  if (!user) return false;
  return hasModulePermission(user, 'sales', 'read') ||
         hasModulePermission(user, 'analytics', 'read');
}

/**
 * 检查用户是否可以访问财务分析
 */
export function canAccessFinanceAnalysis(user: User | null): boolean {
  if (!user) return false;
  return hasModulePermission(user, 'finance', 'read') ||
         hasModulePermission(user, 'analytics', 'read');
}

/**
 * 检查用户是否可以访问经营驾驶舱
 */
export function canAccessExecutiveDashboard(user: User | null): boolean {
  if (!user) return false;
  return hasModulePermission(user, 'analytics', 'read');
}

/**
 * 检查用户是否可以使用 AI 问答
 */
export function canUseAIQuery(user: User | null): boolean {
  if (!user) return false;
  return hasModulePermission(user, 'analytics', 'read');
}

/**
 * 检查用户是否可以查看预警和建议
 */
export function canViewAlerts(user: User | null): boolean {
  if (!user) return false;
  return hasModulePermission(user, 'analytics', 'read') ||
         hasModulePermission(user, 'sales', 'read') ||
         hasModulePermission(user, 'finance', 'read');
}
