import { get, post, put } from '@/api/request';
import type { ProductProcessWorkflowDefinition } from './types';

const workflowConflictConfig = {
  _handledErrorCodes: [
    'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
    'OPTIMISTIC_LOCK_CONFLICT',
  ],
};

export function getProductProcessWorkflow(factoryId: string, productTypeId: string) {
  return get<ProductProcessWorkflowDefinition | null>(
    `/${factoryId}/product-process-workflows/${productTypeId}`,
  );
}

export function saveProductProcessWorkflowDraft(
  factoryId: string,
  productTypeId: string,
  definition: ProductProcessWorkflowDefinition,
) {
  return put<ProductProcessWorkflowDefinition>(
    `/${factoryId}/product-process-workflows/${productTypeId}/draft`,
    definition,
    workflowConflictConfig,
  );
}

export function publishProductProcessWorkflow(
  factoryId: string,
  productTypeId: string,
  lockVersion: number,
) {
  return post<ProductProcessWorkflowDefinition>(
    `/${factoryId}/product-process-workflows/${productTypeId}/publish`,
    { lockVersion },
    workflowConflictConfig,
  );
}
