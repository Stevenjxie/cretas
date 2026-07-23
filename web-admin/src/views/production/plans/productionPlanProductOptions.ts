/**
 * Production plans create finished goods. `productCategory` is the backend
 * ProductCategory contract; never infer a category from a name, code, or unit.
 */
export type ProductionPlanProductOption = {
  productCategory?: unknown;
  isActive?: unknown;
};

export const FINISHED_PRODUCT_CATEGORY = 'FINISHED_PRODUCT';

export function isFinishedGoodPlanOption(option: ProductionPlanProductOption): boolean {
  return option.productCategory === FINISHED_PRODUCT_CATEGORY && option.isActive !== false;
}

export function finishedGoodPlanOptions<T extends ProductionPlanProductOption>(
  options: readonly T[],
): T[] {
  return options.filter(isFinishedGoodPlanOption);
}
