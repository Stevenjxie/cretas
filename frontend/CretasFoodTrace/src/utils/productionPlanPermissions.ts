const PLAN_CREATOR_ROLES = new Set([
  'factory_super_admin',
  'department_admin',
  'production_manager',
]);

export function canCreateProductionPlan(roleCode: string, isReadOnly: boolean): boolean {
  return !isReadOnly && PLAN_CREATOR_ROLES.has(roleCode);
}
