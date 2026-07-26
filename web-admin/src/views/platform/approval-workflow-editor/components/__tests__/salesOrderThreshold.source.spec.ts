import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('sales order approval threshold editor', () => {
  const editor = source('src/views/platform/approval-workflow-editor/index.vue');
  const properties = source('src/views/platform/approval-workflow-editor/components/PropertyPanel.vue');

  it('writes the amount threshold to the graph edge condition used by WorkflowEngine', () => {
    expect(editor).toContain(':decision-type="selectedDecisionType"');
    expect(properties).toContain('销售订单金额阈值（元）');
    expect(properties).toContain('buildSalesApprovalAmountCondition');
    expect(properties).toContain('localData.condition = buildSalesApprovalAmountCondition(v)');
  });

  it('only exposes the amount editor on the approval branch or an existing amount rule', () => {
    expect(editor).toContain("sourceNode?.type === 'condition' && targetNode?.type === 'approval'");
    expect(editor).toContain('parseSalesApprovalAmountThreshold(condition) !== null');
  });

  it('does not offer the disconnected WorkflowRule editor for sales order routing', () => {
    expect(editor).toContain("selectedDecisionType !== 'SALES_ORDER_APPROVAL'");
    expect(properties).toContain('v-if="!isSalesOrderDecision && !props.businessMode"');
    expect(properties).toContain('销售订单金额分流由连线条件决定');
  });
});
