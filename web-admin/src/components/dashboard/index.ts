/**
 * Dashboard 组件导出
 * 根据用户角色加载不同的 Dashboard 组件
 */

export { default as DashboardAdmin } from './DashboardAdmin.vue';
export { default as DashboardHR } from './DashboardHR.vue';
export { default as DashboardProduction } from './DashboardProduction.vue';
export { default as DashboardWarehouse } from './DashboardWarehouse.vue';
export { default as DashboardFinance } from './DashboardFinance.vue';
export { default as DashboardDefault } from './DashboardDefault.vue';
export { default as DashboardRestaurant } from './DashboardRestaurant.vue';

/**
 * 角色到 Dashboard 组件的映射
 * 参考原型文档: docs/prd/prototype/index.html - 14角色权限矩阵
 */
export const ROLE_DASHBOARD_MAP: Record<string, string> = {
  // Level 0 - 平台管理员
  super_admin: 'DashboardAdmin',

  // Level 10 - 工厂高级管理
  factory_super_admin: 'DashboardAdmin',

  // Level 20 - 调度员
  dispatcher: 'DashboardProduction',

  // Level 20 - 部门经理
  production_manager: 'DashboardProduction',
  quality_manager: 'DashboardDefault',          // 质量经理使用通用版
  warehouse_manager: 'DashboardWarehouse',
  procurement_manager: 'DashboardDefault',      // 采购经理使用通用版
  sales_manager: 'DashboardDefault',            // 销售经理使用通用版
  hr_admin: 'DashboardHR',
  equipment_admin: 'DashboardDefault',          // 设备经理使用通用版
  finance_manager: 'DashboardFinance',

  // Level 20 - 车间主管
  workshop_supervisor: 'DashboardProduction',

  // Level 30 - 一线员工 (Mobile专属，不应该登录Web)
  // operator: 'BLOCKED',
  // quality_inspector: 'BLOCKED',
  // warehouse_worker: 'BLOCKED',

  // Level 20 - 餐饮经理
  restaurant_manager: 'DashboardRestaurant',

  // Level 50 - 只读用户
  viewer: 'DashboardDefault',

  // 默认
  default: 'DashboardDefault'
};

/**
 * 工厂类型 Dashboard 覆盖 (按 factoryType 而非 factoryId)
 * 餐饮类型租户的老板、店长、人事和受控管理员应看餐饮 Dashboard。
 * 人事进入后由角色体验和权限过滤，只展示预测排班/人力相关入口；工厂类型
 * hr_admin 仍走 DashboardHR，不扩大 FACTORY 租户的餐饮权限。
 */
const FACTORY_TYPE_DASHBOARD_OVERRIDE: Record<string, string> = {
  'RESTAURANT': 'DashboardRestaurant',
};

/** 餐饮 Dashboard 仅对这些角色开启 override, 其余角色保留 ROLE_DASHBOARD_MAP 分派 */
const RESTAURANT_DASHBOARD_ELIGIBLE_ROLES = new Set([
  'super_admin',
  'factory_super_admin',
  'restaurant_owner',
  'restaurant_manager',
  'hr_admin',
]);

/**
 * 根据角色获取对应的 Dashboard 组件名称
 * @param role - 用户角色
 * @param factoryType - 工厂类型，用于业务类型覆盖
 */
export function getDashboardComponent(role: string, factoryType?: string): string {
  if (factoryType && FACTORY_TYPE_DASHBOARD_OVERRIDE[factoryType]
      && RESTAURANT_DASHBOARD_ELIGIBLE_ROLES.has(role)) {
    return FACTORY_TYPE_DASHBOARD_OVERRIDE[factoryType];
  }
  return ROLE_DASHBOARD_MAP[role] || ROLE_DASHBOARD_MAP['default'];
}
