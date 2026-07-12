export type WorkflowOutputMaterialKind = 'SEMI_FINISHED' | 'FINISHED_GOOD';

const FINISHED_OUTPUT_CATEGORIES = new Set([
  'FINISHED_PRODUCT',
  'CONTRACT_MANUFACTURING',
  'CUSTOMER_MATERIAL',
  'DISH',
  'COMBO',
]);

export function classifyOutputSkuCategory(
  productCategory?: string | null,
): WorkflowOutputMaterialKind | null {
  if (productCategory === 'SEMI_FINISHED') return 'SEMI_FINISHED';
  if (productCategory && FINISHED_OUTPUT_CATEGORIES.has(productCategory)) return 'FINISHED_GOOD';
  return null;
}
