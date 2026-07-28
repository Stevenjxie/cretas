import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ProductProcessWorkflowDefinition } from '../types';

const requestMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('@/api/request', () => requestMocks);

import {
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
        ],
      },
    );
  });
});
