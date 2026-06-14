export interface ModuleRegistryItem {
  moduleCode: string;
  displayName: string;
  category: string;
  permissionModule: string;
  legacyModule?: string;
}

export const PRODUCTION_MODULE_REGISTRY: ModuleRegistryItem[] = [
  { moduleCode: 'production_plan', displayName: '生产计划', category: '生产', permissionModule: 'production', legacyModule: 'production' },
  { moduleCode: 'production_report', displayName: '生产报工', category: '生产', permissionModule: 'production' },
  { moduleCode: 'work_process', displayName: '工序执行', category: '生产', permissionModule: 'production' },
  { moduleCode: 'bom', displayName: 'BOM/配方', category: '生产', permissionModule: 'production' },
  { moduleCode: 'scheduling', displayName: '智能排程', category: '生产', permissionModule: 'scheduling' },
  { moduleCode: 'warehouse', displayName: '仓储', category: '仓储', permissionModule: 'warehouse' },
  { moduleCode: 'quality_inspection', displayName: '质量检验', category: '质量', permissionModule: 'quality', legacyModule: 'quality' },
  { moduleCode: 'purchase_order', displayName: '采购订单', category: '采购', permissionModule: 'procurement', legacyModule: 'procurement' },
  { moduleCode: 'sales_order', displayName: '销售订单', category: '销售', permissionModule: 'sales', legacyModule: 'sales' },
  { moduleCode: 'equipment', displayName: '设备', category: '设备', permissionModule: 'equipment' },
  { moduleCode: 'finance_ap', displayName: '应付财务', category: '财务', permissionModule: 'finance', legacyModule: 'finance' },
  { moduleCode: 'finance_ar', displayName: '应收财务', category: '财务', permissionModule: 'finance' },
  { moduleCode: 'hr_employee', displayName: '员工/人事', category: '人事', permissionModule: 'hr', legacyModule: 'hr' },
  { moduleCode: 'restaurant', displayName: '餐饮经营', category: '餐饮', permissionModule: 'restaurant' },
  { moduleCode: 'production', displayName: '生产管理', category: '兼容', permissionModule: 'production' },
];

export function resolveModuleRegistryItem(module: string): ModuleRegistryItem | undefined {
  return PRODUCTION_MODULE_REGISTRY.find(item =>
    item.moduleCode === module || item.legacyModule === module,
  );
}
