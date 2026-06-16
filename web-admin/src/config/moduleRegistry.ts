export type PermissionLevel = 'hidden' | 'read' | 'write';

export interface ModuleDefinition {
  moduleCode: string;
  displayName: string;
  parentCode: string;
  parentName: string;
  routePath: string;
  sortOrder: number;
  writeSupported: boolean;
}

export interface ModuleRegistryItem extends ModuleDefinition {
  category: string;
  permissionModule: string;
  legacyModule?: string;
}

function defineModule(definition: ModuleDefinition, legacyModule?: string): ModuleRegistryItem {
  return {
    ...definition,
    category: definition.parentName,
    permissionModule: definition.parentCode,
    ...(legacyModule ? { legacyModule } : {}),
  };
}

export const PRODUCTION_MODULE_REGISTRY: ModuleRegistryItem[] = [
  defineModule({
    moduleCode: 'production_plan',
    displayName: '生产计划',
    parentCode: 'production',
    parentName: '生产管理',
    routePath: '/production/plans',
    sortOrder: 10,
    writeSupported: true,
  }, 'production'),
  defineModule({
    moduleCode: 'production_report',
    displayName: '生产报工',
    parentCode: 'production',
    parentName: '生产管理',
    routePath: '/production/approval',
    sortOrder: 20,
    writeSupported: true,
  }),
  defineModule({
    moduleCode: 'work_process',
    displayName: '工序执行',
    parentCode: 'production',
    parentName: '生产管理',
    routePath: '/production/process-io',
    sortOrder: 30,
    writeSupported: true,
  }),
  defineModule({
    moduleCode: 'bom',
    displayName: 'BOM/配方',
    parentCode: 'production',
    parentName: '生产管理',
    routePath: '/production/bom',
    sortOrder: 40,
    writeSupported: true,
  }),
  defineModule({
    moduleCode: 'scheduling',
    displayName: '智能排程',
    parentCode: 'scheduling',
    parentName: '智能调度',
    routePath: '/scheduling/plans',
    sortOrder: 50,
    writeSupported: true,
  }),
  defineModule({
    moduleCode: 'warehouse',
    displayName: '仓储',
    parentCode: 'warehouse',
    parentName: '仓储管理',
    routePath: '/warehouse/materials',
    sortOrder: 60,
    writeSupported: true,
  }),
  defineModule({
    moduleCode: 'quality_inspection',
    displayName: '质量检验',
    parentCode: 'quality',
    parentName: '质量管理',
    routePath: '/quality/inspections',
    sortOrder: 70,
    writeSupported: true,
  }, 'quality'),
  defineModule({
    moduleCode: 'purchase_order',
    displayName: '采购订单',
    parentCode: 'procurement',
    parentName: '采购管理',
    routePath: '/procurement/orders',
    sortOrder: 80,
    writeSupported: true,
  }, 'procurement'),
  defineModule({
    moduleCode: 'sales_order',
    displayName: '销售订单',
    parentCode: 'sales',
    parentName: '销售管理',
    routePath: '/sales/orders',
    sortOrder: 90,
    writeSupported: true,
  }, 'sales'),
  defineModule({
    moduleCode: 'equipment',
    displayName: '设备',
    parentCode: 'equipment',
    parentName: '设备管理',
    routePath: '/equipment/list',
    sortOrder: 100,
    writeSupported: true,
  }),
  defineModule({
    moduleCode: 'finance_ap',
    displayName: '应付财务',
    parentCode: 'finance',
    parentName: '财务管理',
    routePath: '/finance/ar-ap',
    sortOrder: 110,
    writeSupported: true,
  }, 'finance'),
  defineModule({
    moduleCode: 'finance_ar',
    displayName: '应收财务',
    parentCode: 'finance',
    parentName: '财务管理',
    routePath: '/finance/ar-ap',
    sortOrder: 120,
    writeSupported: true,
  }),
  defineModule({
    moduleCode: 'hr_employee',
    displayName: '员工/人事',
    parentCode: 'hr',
    parentName: '人事管理',
    routePath: '/hr/employees',
    sortOrder: 130,
    writeSupported: true,
  }, 'hr'),
  defineModule({
    moduleCode: 'restaurant',
    displayName: '餐饮经营',
    parentCode: 'restaurant',
    parentName: '餐饮运营',
    routePath: '/restaurant',
    sortOrder: 140,
    writeSupported: true,
  }),
  defineModule({
    moduleCode: 'production',
    displayName: '生产管理',
    parentCode: 'production',
    parentName: '生产管理',
    routePath: '/production',
    sortOrder: 150,
    writeSupported: true,
  }),
];

export const PERMISSION_MODULE_REGISTRY: ModuleRegistryItem[] = [
  ...PRODUCTION_MODULE_REGISTRY,
  defineModule({
    moduleCode: 'permission_employee_management',
    displayName: '员工管理',
    parentCode: 'permission_settings',
    parentName: '权限设置',
    routePath: '/permissions/employees',
    sortOrder: 10,
    writeSupported: true,
  }),
  defineModule({
    moduleCode: 'permission_role_templates',
    displayName: '角色权限模板',
    parentCode: 'permission_settings',
    parentName: '权限设置',
    routePath: '/permissions/role-templates',
    sortOrder: 20,
    writeSupported: true,
  }),
  defineModule({
    moduleCode: 'permission_employee_overrides',
    displayName: '员工权限',
    parentCode: 'permission_settings',
    parentName: '权限设置',
    routePath: '/permissions/employee-permissions',
    sortOrder: 30,
    writeSupported: true,
  }),
  defineModule({
    moduleCode: 'permission_preview',
    displayName: '权限预览',
    parentCode: 'permission_settings',
    parentName: '权限设置',
    routePath: '/permissions/preview',
    sortOrder: 40,
    writeSupported: false,
  }),
];

export function resolveModuleRegistryItem(module: string): ModuleRegistryItem | undefined {
  return PERMISSION_MODULE_REGISTRY.find(item =>
    item.moduleCode === module || item.legacyModule === module,
  );
}

export function resolveModuleRegistryItemByRoute(path: string): ModuleRegistryItem | undefined {
  if (!path) return undefined;
  return [...PERMISSION_MODULE_REGISTRY]
    .sort((a, b) => b.routePath.length - a.routePath.length)
    .find(item => path === item.routePath || path.startsWith(`${item.routePath}/`));
}
