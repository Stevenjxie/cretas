import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ProductProcessWorkflowDefinition } from '../types';

const requestMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('@/api/request', () => requestMocks);

import {
  getWorkflowBomSyncPreflight,
  publishAndActivateProductProcessWorkflow,
  publishProductProcessWorkflow,
  saveProductProcessWorkflowDraft,
} from '../workflowApi';

describe('workflowApi conflict ownership', () => {
  beforeEach(() => vi.clearAllMocks());

  it('keeps save conflicts local and lets the editor own publish-time BOM remediation', () => {
    const definition = { schemaVersion: 1, nodes: [], edges: [] } as unknown as ProductProcessWorkflowDefinition;

    saveProductProcessWorkflowDraft('F006', 'PT-A', definition);
    publishProductProcessWorkflow('F006', 'PT-A', 3);

    const saveConfig = {
      _handledErrorCodes: [
        'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
        'OPTIMISTIC_LOCK_CONFLICT',
      ],
    };
    expect(requestMocks.put).toHaveBeenCalledWith(
      '/F006/product-process-workflows/PT-A/draft',
      definition,
      saveConfig,
    );
    expect(requestMocks.post).toHaveBeenCalledWith(
      '/F006/product-process-workflows/PT-A/publish',
      { lockVersion: 3 },
      {
        _handledErrorCodes: [
          'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
          'OPTIMISTIC_LOCK_CONFLICT',
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
      },
    );
  });

  it('calls the BOM sync preflight endpoint for the selected product', () => {
    getWorkflowBomSyncPreflight('F006', 'PT-A');

    expect(requestMocks.get).toHaveBeenCalledWith(
      '/F006/product-process-workflows/PT-A/bom-sync-preflight',
    );
  });

  it('publishes and activates atomically with the caller idempotency key', () => {
    const request = {
      lockVersion: 7,
      idempotencyKey: 'workflow-publish-7',
      revisionId: 222,
      revisionHash: 'revision-222',
      definitionVersion: 4,
    };

    publishAndActivateProductProcessWorkflow('F006', 'PT-A', request);

    expect(requestMocks.post).toHaveBeenCalledWith(
      '/F006/product-process-workflows/PT-A/publish-and-activate',
      request,
      {
        _handledErrorCodes: [
          'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
          'OPTIMISTIC_LOCK_CONFLICT',
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
      },
    );
  });
});
