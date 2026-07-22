import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('sales order unified OA entry contract', () => {
  const legacyChains = source('src/views/system/approval-chains/list.vue');
  const canvas = source('src/views/platform/canvas-editor/index.vue');
  const editor = source('src/views/platform/approval-workflow-editor/index.vue');
  const financeList = source('src/views/sales/finance-review/list.vue');
  const financeDetail = source('src/views/sales/finance-review/detail.vue');
  const financeApi = source('src/api/salesFinanceReview.ts');
  const pending = source('src/views/workflow/pending.vue');

  it('keeps legacy sales approval-chain rows read-only and links to Canvas OA', () => {
    expect(legacyChains).toContain("const UNIFIED_OA_DECISION_TYPES = new Set(['SALES_ORDER_APPROVAL'])");
    expect(legacyChains).toContain('销售订单审批已迁移至统一 OA');
    expect(legacyChains).toContain('前往统一 OA');
    expect(legacyChains).toContain("types: ['SALES_RETURN_APPROVAL'");
    expect(legacyChains).not.toContain("types: ['SALES_ORDER_APPROVAL', 'SALES_RETURN_APPROVAL'");
  });

  it('opens the approval tab with the requested sales decision type', () => {
    expect(canvas).toContain(':initial-decision-type="initialApprovalDecisionType"');
    expect(canvas).toContain("if (tab === 'approval')");
    expect(canvas).toContain("value === 'SALES_ORDER_APPROVAL'");
    expect(editor).toContain('initialDecisionType?: DecisionType');
    expect(editor).toContain("props.initialDecisionType ?? 'QUALITY_RELEASE'");
  });

  it('removes business-page direct approval and routes old screens to personal OA', () => {
    expect(financeApi).not.toContain('export function financeApprove');
    expect(financeApi).not.toContain('export function financeReject');
    expect(financeDetail).not.toContain('handleApprove');
    expect(financeDetail).not.toContain('handleReject');
    expect(financeDetail).toContain('前往统一 OA');
    expect(financeList).toContain('前往 OA');
    expect(pending).toContain('route.query.moduleCode');
    expect(pending).toContain("ACTIONABLE_MODULE_CODES.has(requestedModuleCode)");
  });
});
