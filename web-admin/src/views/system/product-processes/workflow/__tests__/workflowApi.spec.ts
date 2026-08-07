import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
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


/**
 * 2026-08-07 阶段 3-3 的反向判据。
 *
 * 上面两处清单里原本有 `WORKFLOW_ACTIVE_BOM_REQUIRED` —— 后端已不再产出它
 * （没有生效 BOM 时改为从画布定义投影出一份，见
 *  `BomRecipeServiceImpl#projectActiveBomFromRevision`）。
 *
 * ⛔ 原断言的**意图**是「发布路径要显式列出它自己处理的失败态」，这条意图保留：
 * 所以这里不是简单地把那一行删掉了事，而是钉住「它不许再回到清单里」。
 * 它一旦回来，要么是后端又把前置加回去了（那会重新逼出手工凑的假 BOM），
 * 要么是有人复制粘贴了旧清单 —— 两种都该当场变红。
 */
describe('阶段 3-3: 已删除的 ACTIVE_BOM_REQUIRED 不许回到已知错误码清单', () => {
  it('发布相关的 _handledErrorCodes 里没有它', () => {
    const source = readFileSync(
      resolve(process.cwd(), 'src/views/system/product-processes/workflow/workflowApi.ts'),
      'utf-8',
    );
    // ⚠️ 断言代码形态而不是「字符串不出现」—— workflowApi.ts 的注释里就写着这个码
    //    （解释它为什么被删），用 not.toContain 会被那段注释打红。
    expect(source).not.toMatch(/^\s*'WORKFLOW_ACTIVE_BOM_REQUIRED',\s*$/m);
  });
});

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
