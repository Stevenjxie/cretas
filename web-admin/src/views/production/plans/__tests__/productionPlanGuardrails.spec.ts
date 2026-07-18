import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '../list.vue'), 'utf8');

describe('production plan operator guardrails', () => {
  it('does not invent kilograms when a production plan unit is missing', () => {
    expect(source).not.toContain("|| 'kg'");
    expect(source).toContain('单位未配置');
  });
  it('shows production loss evidence guidance in settlement', () => {
    expect(source).toContain('生产报损/损耗留证');
    expect(source).toContain('报损原因选择“生产损耗”');
    expect(source).toContain('拍照或附件留证');
  });

  it('treats settlement as a recorded-process reconciliation instead of a second data-entry form', () => {
    expect(source).toContain('逐道报工汇总');
    expect(source).toContain('确认结单并结束计划');
    expect(source).toContain("source: 'PROCESS_REPORT'");
    expect(source).toContain('仅在上方出现异常时才需要补录或调整');
    expect(source).toContain('confirm: true');
    expect(source).toContain('settlementPrefillClean.value');
  });

  it('shows persistent WIP available quantity and max boundary in settlement', () => {
    expect(source).toContain('当前可用');
    expect(source).toContain('本行最多领用');
    expect(source).toContain('超出可用量');
  });

  it('shows backfill time-window guidance on production plan entry', () => {
    expect(source).toContain('补录时效');
    expect(source).toContain('今天/昨天可补');
    expect(source).toContain('前天为极限');
    expect(source).toContain('大前天及更早禁止补录');
  });

  it('blocks a single product without silently falling back to a multi-output Workflow', () => {
    expect(source).toContain('该产品没有单产出 Workflow，请前往创建单产出 Workflow');
    expect(source).toContain("planForm.value.resolutionMode === 'NONE'");
  });

  it('requires multiple products to share one multi-output Workflow', () => {
    expect(source).toContain('未找到共享的工序 Workflow，请分开创建生产计划');
    expect(source).toContain("resolutionMode === 'SHARED_MULTI_OUTPUT'");
    expect(source).toContain('计划绑定整张共同 Workflow');
    expect(source).not.toContain('父计划 + 多个产出计划行');
  });

  it('uses a fail-closed route dialog, previews Cell links, and submits the exact selected version', () => {
    expect(source).toContain('选择本计划使用的生产工序路线');
    expect(source).toContain('悬浮查看 Cell 连线');
    expect(source).toContain('<WorkflowRoutePreview');
    expect(source).toContain('payload.selectedWorkflowId');
    expect(source).toContain('payload.selectedWorkflowVersion');
    expect(source).toContain('WORKFLOW_SELECTED_VERSION_CHANGED');
  });

  it('requires explicit confirmation before adding superset co-products', () => {
    expect(source).toContain('额外联产成品加入本计划');
    expect(source).toContain('该 Workflow 会同时产出其它成品，需要确认完整产出集合');
    expect(source).toContain('planForm.value.targetFinishedGoodIds = completeOutputs');
  });
});
