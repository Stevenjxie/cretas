import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('business-scoped unified OA entry contract', () => {
  const legacyChains = source('src/views/system/approval-chains/list.vue');
  const canvas = source('src/views/platform/canvas-editor/index.vue');
  const editor = source('src/views/platform/approval-workflow-editor/index.vue');
  const financeList = source('src/views/sales/finance-review/list.vue');
  const financeDetail = source('src/views/sales/finance-review/detail.vue');
  const financeApi = source('src/api/salesFinanceReview.ts');
  const pending = source('src/views/workflow/pending.vue');

  it('turns system settings into a business-first approval catalog', () => {
    expect(legacyChains).toContain('<h1>审批业务</h1>');
    expect(legacyChains).toContain('审批已启用');
    expect(legacyChains).toContain('无需审批');
    expect(legacyChains).toContain('在途审批继续使用原运行版本');
    expect(legacyChains).toContain('buildOaCanvasQuery');
    expect(legacyChains).not.toContain("post(`/${factoryId.value}/approval-chains`");
    expect(legacyChains).not.toContain("put(`/${factoryId.value}/approval-chains/");
    expect(legacyChains).not.toContain("del(`/${factoryId.value}/approval-chains/");
  });

  it('opens the exact approval business and workflow in Canvas', () => {
    expect(canvas).toContain(':initial-decision-type="initialApprovalDecisionType"');
    expect(canvas).toContain(':initial-workflow-id="initialApprovalWorkflowId"');
    expect(canvas).toContain(':lock-decision-type="approvalBusinessLocked"');
    expect(canvas).toContain("if (tab === 'approval')");
    expect(canvas).toContain('isDecisionType(value)');
    expect(editor).toContain('initialDecisionType?: DecisionType');
    expect(editor).toContain('initialWorkflowId?: string');
    expect(editor).toContain('lockDecisionType?: boolean');
    expect(editor).toContain("props.initialDecisionType ?? 'QUALITY_RELEASE'");
    expect(editor).toContain('await loadWorkflow(preferred.id, true)');
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
