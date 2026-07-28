import { del, get, post, put } from '@/api/request';
import type {
  ProductProcessWorkflowActivation,
  ProductProcessWorkflowDefinition,
  WorkflowBomSyncPreflight,
  WorkflowPublishAndActivateRequest,
  WorkflowPublishAndActivateResponse,
} from './types';

const workflowConflictConfig = {
  _handledErrorCodes: [
    'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
    'OPTIMISTIC_LOCK_CONFLICT',
  ],
};

const workflowPublishConfig = {
  _handledErrorCodes: [
    ...workflowConflictConfig._handledErrorCodes,
    'WORKFLOW_ACTIVE_BOM_REVISION_MISMATCH',
    'WORKFLOW_ACTIVE_BOM_FAMILY_INCOMPLETE',
    'WORKFLOW_ACTIVE_BOM_REQUIRED',
    'BOM_WORKFLOW_UPGRADE_SLOT_AMBIGUOUS',
    'BOM_WORKFLOW_UPGRADE_MATERIAL_AMBIGUOUS',
    'BOM_WORKFLOW_INPUT_ITEM_MISSING',
    'BOM_WORKFLOW_UPGRADE_UNIT_INCOMPATIBLE',
    'PRODUCT_PROCESS_WORKFLOW_DRAFT_MISSING',
    'PRODUCT_PROCESS_WORKFLOW_REVISION_INCOMPLETE',
    'WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_REQUIRED',
    'WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_INVALID',
    'WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_CONFLICT',
    'WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_MISMATCH',
    'WORKFLOW_PUBLISH_REVISION_IDENTITY_CONFLICT',
    'WORKFLOW_PUBLISH_REPLAY_CONFLICT',
    'WORKFLOW_BOM_SYNC_USER_INPUT_REQUIRED',
    'WORKFLOW_BOM_SYNC_CONFLICT',
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
    workflowPublishConfig,
  );
}

export function getWorkflowBomSyncPreflight(
  factoryId: string,
  productTypeId: string,
) {
  return get<WorkflowBomSyncPreflight>(
    `/${factoryId}/product-process-workflows/${productTypeId}/bom-sync-preflight`,
  );
}

export function publishAndActivateProductProcessWorkflow(
  factoryId: string,
  productTypeId: string,
  request: WorkflowPublishAndActivateRequest,
) {
  return post<WorkflowPublishAndActivateResponse>(
    `/${factoryId}/product-process-workflows/${productTypeId}/publish-and-activate`,
    request,
    workflowPublishConfig,
  );
}

export function snapshotProductProcessWorkflow(
  factoryId: string,
  productTypeId: string,
  lockVersion: number,
) {
  return post<ProductProcessWorkflowDefinition>(
    `/${factoryId}/product-process-workflows/${productTypeId}/snapshot`,
    { lockVersion },
    workflowConflictConfig,
  );
}

// #12: 版本历史 (只读浏览之前版本)。发布和手动快照都会保留独立历史行。
export interface WorkflowVersionSummary {
  definitionVersion: number;
  status: 'DRAFT' | 'SNAPSHOT' | 'PUBLISHED';
  updatedAt: string | null;
  active: boolean;
}

export function listProductProcessWorkflowVersions(factoryId: string, productTypeId: string) {
  return get<WorkflowVersionSummary[]>(
    `/${factoryId}/product-process-workflows/${productTypeId}/versions`,
  );
}

export function getProductProcessWorkflowVersion(
  factoryId: string,
  productTypeId: string,
  version: number,
) {
  return get<ProductProcessWorkflowDefinition>(
    `/${factoryId}/product-process-workflows/${productTypeId}/versions/${version}`,
  );
}

export function getProductProcessWorkflowActivation(
  factoryId: string,
  productTypeId: string,
) {
  return get<ProductProcessWorkflowActivation | null>(
    `/${factoryId}/product-process-workflows/${productTypeId}/activation`,
  );
}

export function activateProductProcessWorkflow(factoryId: string, workflowId: number) {
  return put<ProductProcessWorkflowActivation>(
    `/${factoryId}/product-process-workflows/${workflowId}/activation`,
  );
}

export function deactivateProductProcessWorkflow(
  factoryId: string,
  productTypeId: string,
  lockVersion: number,
) {
  const params = new URLSearchParams({
    productTypeId,
    lockVersion: String(lockVersion),
  });
  return del<ProductProcessWorkflowActivation>(
    `/${factoryId}/product-process-workflows/activation?${params.toString()}`,
  );
}
