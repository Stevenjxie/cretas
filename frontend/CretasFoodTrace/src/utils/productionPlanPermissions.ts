/** 生产计划属于 PC 端复杂规划；RN 对所有角色都只展示计划与现场进度。 */
export function canCreateProductionPlan(_roleCode: string, _isReadOnly: boolean): boolean {
  return false;
}

/** 结单不是现场移动动作，必须回 PC 完成。 */
export function canCompleteProductionPlan(_roleCode: string, _isReadOnly: boolean): boolean {
  return false;
}
