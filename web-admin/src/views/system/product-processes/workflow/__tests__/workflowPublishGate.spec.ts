import { describe, expect, it, vi } from 'vitest';
import type { WorkflowBomSyncPreflight } from '../types';
import {
  canPublishWorkflowWithBomSync,
  executeWorkflowPublishMutation,
  workflowBomSyncBlockingIssues,
  workflowBomSyncStatusTitle,
} from '../workflowPublishGate';

function preflight(
  overrides: Partial<WorkflowBomSyncPreflight> = {},
): WorkflowBomSyncPreflight {
  return {
    classification: 'READY',
    activeBomVersion: 3,
    syncDraftVersion: null,
    activeBomWorkflowRevisionId: 175,
    targetWorkflowRevisionId: 222,
    preservedItems: [],
    automaticMappings: [],
    missingItems: [],
    conflicts: [],
    canCompleteAutomatically: true,
    ...overrides,
  };
}

describe('workflowPublishGate', () => {
  it.each(['READY', 'AUTO_MIGRATABLE'] as const)(
    'allows one atomic mutation for %s',
    (classification) => {
      expect(canPublishWorkflowWithBomSync(preflight({ classification }))).toBe(true);
    },
  );

  it.each(['USER_INPUT_REQUIRED', 'CONFLICT'] as const)(
    'blocks the mutation for %s and exposes each issue',
    (classification) => {
      const result = preflight({
        classification,
        canCompleteAutomatically: false,
        missingItems: [{
          code: 'BOM_WORKFLOW_INPUT_ITEM_MISSING',
          materialTypeId: 'RM-1',
          materialName: '鸡肉',
          processNodeId: 'process-1',
          field: 'inputPort',
          message: '鸡肉尚未指定投入工序',
          action: '请选择投入工序',
        }],
      });

      expect(canPublishWorkflowWithBomSync(result)).toBe(false);
      expect(workflowBomSyncBlockingIssues(result)).toEqual(result.missingItems);
      expect(workflowBomSyncStatusTitle(result)).toContain('尚未执行发布');
    },
  );

  it('fails closed when an apparently automatic classification is inconsistent', () => {
    const result = preflight({
      classification: 'AUTO_MIGRATABLE',
      canCompleteAutomatically: false,
    });

    expect(canPublishWorkflowWithBomSync(result)).toBe(false);
    expect(workflowBomSyncBlockingIssues(result)).toHaveLength(1);
  });

  it.each(['USER_INPUT_REQUIRED', 'CONFLICT'] as const)(
    'executes zero mutations for blocked classification %s',
    async (classification) => {
      const mutation = vi.fn().mockResolvedValue('published');
      const result = await executeWorkflowPublishMutation(preflight({
        classification,
        canCompleteAutomatically: false,
      }), mutation);

      expect(result).toBeNull();
      expect(mutation).not.toHaveBeenCalled();
    },
  );
});
