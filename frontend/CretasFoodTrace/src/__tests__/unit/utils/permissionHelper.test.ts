import {
  FACTORY_ROLES,
  PLATFORM_ROLES,
  type FactoryRole,
  type User,
} from '../../../types/auth';
import {
  canAccessExecutiveDashboard,
  canAccessFinanceAnalysis,
  canAccessModule,
  canAccessSalesAnalysis,
  canAccessSmartBI,
  canManageBasicData,
  canManageDepartments,
  canManageEquipment,
  canManageHR,
  canManagePermissions,
  canManageProduction,
  canManageQuality,
  canManageRole,
  canManageUsers,
  canManageWarehouse,
  canUploadExcel,
  canUseAIQuery,
  canViewAlerts,
  canViewReports,
  getAccessibleModules,
  getFactoryId,
  getPermissionDebugInfo,
  getRoleCode,
  getRoleDepartment,
  getRoleLevel,
  getRoleMetadata,
  getRoleName,
  getUserLevel,
  hasModulePermission,
  isDepartmentAdmin,
  isEquipmentAdmin,
  isHrAdmin,
  isManager,
  isOperator,
  isPermissionAdmin,
  isPlatformAdmin,
  isProductionManager,
  isQualityInspector,
  isQualityManager,
  isSuperAdmin,
  isWarehouseRole,
  isWorker,
  isWorkshopSupervisor,
} from '../../../utils/permissionHelper';

const baseUserFields = {
  id: 1,
  username: 'tester',
  email: 'tester@example.com',
  createdAt: '2026-06-14T00:00:00Z',
  updatedAt: '2026-06-14T00:00:00Z',
  isActive: true,
};

function factoryUser(role: FactoryRole, overrides: Partial<User['factoryUser']> = {}): User {
  return {
    ...baseUserFields,
    userType: 'factory',
    factoryUser: {
      role,
      factoryId: 'F006',
      factoryType: 'FACTORY',
      permissions: [],
      ...overrides,
    },
  };
}

function platformUser(): User {
  return {
    ...baseUserFields,
    userType: 'platform',
    platformUser: {
      role: PLATFORM_ROLES.PLATFORM_ADMIN,
      permissions: [],
    },
  };
}

describe('permissionHelper', () => {
  it('reads role metadata and normalizes role code and level', () => {
    const productionManager = factoryUser(FACTORY_ROLES.PRODUCTION_MANAGER);
    const legacyProductionManager = factoryUser(FACTORY_ROLES.VIEWER, {
      position: 'proc_admin',
    });

    expect(getRoleMetadata(FACTORY_ROLES.PRODUCTION_MANAGER)?.level).toBe(10);
    expect(getRoleMetadata('missing-role')).toBeUndefined();
    expect(getRoleLevel(FACTORY_ROLES.OPERATOR)).toBe(30);
    expect(getRoleLevel('missing-role')).toBe(99);
    expect(getRoleDepartment(FACTORY_ROLES.PRODUCTION_MANAGER)).toBe('production');
    expect(getRoleDepartment('missing-role')).toBe('none');
    expect(getRoleCode(null)).toBe(FACTORY_ROLES.VIEWER);
    expect(getRoleCode(platformUser())).toBe(PLATFORM_ROLES.PLATFORM_ADMIN);
    expect(getRoleCode(productionManager)).toBe(FACTORY_ROLES.PRODUCTION_MANAGER);
    expect(getRoleCode(legacyProductionManager)).toBe(FACTORY_ROLES.PRODUCTION_MANAGER);
    expect(getUserLevel(productionManager)).toBe(10);
    expect(getUserLevel(undefined)).toBe(99);
  });

  it('classifies platform, manager, worker, and department-specific roles', () => {
    const platform = platformUser();
    const superAdmin = factoryUser(FACTORY_ROLES.FACTORY_SUPER_ADMIN);
    const productionManager = factoryUser(FACTORY_ROLES.PRODUCTION_MANAGER);
    const qualityManager = factoryUser(FACTORY_ROLES.QUALITY_MANAGER);
    const workshopSupervisor = factoryUser(FACTORY_ROLES.WORKSHOP_SUPERVISOR);
    const qualityInspector = factoryUser(FACTORY_ROLES.QUALITY_INSPECTOR);
    const operator = factoryUser(FACTORY_ROLES.OPERATOR);
    const warehouseManager = factoryUser(FACTORY_ROLES.WAREHOUSE_MANAGER);
    const warehouseWorker = factoryUser(FACTORY_ROLES.WAREHOUSE_WORKER);
    const hrAdmin = factoryUser(FACTORY_ROLES.HR_ADMIN);
    const equipmentAdmin = factoryUser(FACTORY_ROLES.EQUIPMENT_ADMIN);

    expect(isPlatformAdmin(platform)).toBe(true);
    expect(isPlatformAdmin(null)).toBe(false);
    expect(isSuperAdmin(superAdmin)).toBe(true);
    expect(isSuperAdmin(platform)).toBe(false);
    expect(isManager(productionManager)).toBe(true);
    expect(isManager(operator)).toBe(false);
    expect(isWorker(operator)).toBe(true);
    expect(isWorker(productionManager)).toBe(false);
    expect(canManageRole(superAdmin, FACTORY_ROLES.PRODUCTION_MANAGER)).toBe(true);
    expect(canManageRole(operator, FACTORY_ROLES.PRODUCTION_MANAGER)).toBe(false);
    expect(isProductionManager(productionManager)).toBe(true);
    expect(isQualityManager(qualityManager)).toBe(true);
    expect(isWorkshopSupervisor(workshopSupervisor)).toBe(true);
    expect(isQualityInspector(qualityInspector)).toBe(true);
    expect(isOperator(operator)).toBe(true);
    expect(isWarehouseRole(warehouseManager)).toBe(true);
    expect(isWarehouseRole(warehouseWorker)).toBe(true);
    expect(isHrAdmin(hrAdmin)).toBe(true);
    expect(isEquipmentAdmin(equipmentAdmin)).toBe(true);
  });

  it('keeps deprecated role helpers compatible', () => {
    const permissionAdmin = factoryUser(FACTORY_ROLES.PERMISSION_ADMIN);
    const departmentAdmin = factoryUser(FACTORY_ROLES.DEPARTMENT_ADMIN);
    const productionManager = factoryUser(FACTORY_ROLES.PRODUCTION_MANAGER);
    const operator = factoryUser(FACTORY_ROLES.OPERATOR);

    expect(isPermissionAdmin(permissionAdmin)).toBe(true);
    expect(isPermissionAdmin(operator)).toBe(false);
    expect(isDepartmentAdmin(departmentAdmin)).toBe(true);
    expect(isDepartmentAdmin(productionManager)).toBe(true);
    expect(isDepartmentAdmin(operator)).toBe(false);
  });

  it('evaluates module and feature permissions by role matrix', () => {
    const platform = platformUser();
    const superAdmin = factoryUser(FACTORY_ROLES.FACTORY_SUPER_ADMIN);
    const productionManager = factoryUser(FACTORY_ROLES.PRODUCTION_MANAGER);
    const warehouseWorker = factoryUser(FACTORY_ROLES.WAREHOUSE_WORKER);
    const qualityInspector = factoryUser(FACTORY_ROLES.QUALITY_INSPECTOR);
    const hrAdmin = factoryUser(FACTORY_ROLES.HR_ADMIN);
    const equipmentAdmin = factoryUser(FACTORY_ROLES.EQUIPMENT_ADMIN);
    const viewer = factoryUser(FACTORY_ROLES.VIEWER);

    expect(hasModulePermission(platform, 'finance', 'write')).toBe(true);
    expect(hasModulePermission(null, 'production', 'read')).toBe(false);
    expect(hasModulePermission(factoryUser(FACTORY_ROLES.UNACTIVATED), 'production', 'read')).toBe(false);
    expect(hasModulePermission(viewer, 'production', 'write')).toBe(false);
    expect(hasModulePermission(viewer, 'production', 'read')).toBe(true);
    expect(canAccessModule(viewer, 'warehouse')).toBe(true);
    expect(canAccessModule(viewer, 'system')).toBe(false);
    expect(canManageBasicData(productionManager)).toBe(true);
    expect(canManageBasicData(viewer)).toBe(false);
    expect(canManageUsers(hrAdmin)).toBe(true);
    expect(canManageDepartments(superAdmin)).toBe(true);
    expect(canManagePermissions(platform)).toBe(true);
    expect(canViewReports(productionManager)).toBe(true);
    expect(canManageProduction(productionManager)).toBe(true);
    expect(canManageWarehouse(warehouseWorker)).toBe(true);
    expect(canManageQuality(qualityInspector)).toBe(true);
    expect(canManageEquipment(equipmentAdmin)).toBe(true);
    expect(canManageHR(hrAdmin)).toBe(true);
  });

  it('returns factory scope, accessible modules, debug info, and analytics access', () => {
    const platform = platformUser();
    const financeManager = factoryUser(FACTORY_ROLES.FINANCE_MANAGER);
    const salesManager = factoryUser(FACTORY_ROLES.SALES_MANAGER);
    const viewer = factoryUser(FACTORY_ROLES.VIEWER);

    expect(getFactoryId(financeManager)).toBe('F006');
    expect(getFactoryId(platform)).toBeUndefined();
    expect(getRoleName(null)).toBeTruthy();
    expect(getRoleName(platform)).toBeTruthy();
    expect(getRoleName(financeManager)).toBeTruthy();
    expect(getAccessibleModules(null)).toEqual([]);
    expect(getAccessibleModules(platform)).toContain('system');
    expect(getAccessibleModules(financeManager)).toContain('finance');
    expect(getAccessibleModules(factoryUser(FACTORY_ROLES.UNACTIVATED))).toEqual([]);
    expect(getPermissionDebugInfo(null)).toMatchObject({
      userType: 'none',
      roleCode: FACTORY_ROLES.VIEWER,
      accessibleModules: [],
    });
    expect(getPermissionDebugInfo(financeManager)).toMatchObject({
      userType: 'factory',
      roleCode: FACTORY_ROLES.FINANCE_MANAGER,
      factoryId: 'F006',
    });
    expect(canAccessSmartBI(financeManager)).toBe(true);
    expect(canAccessSmartBI(viewer)).toBe(true);
    expect(canUploadExcel(platform)).toBe(true);
    expect(canUploadExcel(financeManager)).toBe(false);
    expect(canAccessSalesAnalysis(salesManager)).toBe(true);
    expect(canAccessFinanceAnalysis(financeManager)).toBe(true);
    expect(canAccessExecutiveDashboard(platform)).toBe(true);
    expect(canAccessExecutiveDashboard(financeManager)).toBe(false);
    expect(canUseAIQuery(platform)).toBe(true);
    expect(canUseAIQuery(financeManager)).toBe(false);
    expect(canViewAlerts(financeManager)).toBe(true);
    expect(canViewAlerts(viewer)).toBe(true);
  });
});
