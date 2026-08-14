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

  it('keeps warehouse receipt units canonical in payload while localizing BY_STOCK receipt truth', () => {
    // 2026-08-14: payload 仍然必须是**规范码**(不能是展示标签), 但换成保留 只/个 的那一支。
    // canonicalUnitCode 把 件/个/只 全折成 pcs, 而后端 #2628 起把它们拆成三个独立单位
    // (个/只 没有英文别名)。继续用它, 档案单位是「个」的物料回传 pcs, 后端判不等价直接拒 ——
    // 「包材单位必须继承物料档案」那条 409 就是这么来的。
    // canonicalUnitCodeKeepingCount 是本仓指定的落库归一(销售订单一直用它), 依然是规范码。
    expect(source).toContain(
      "import { canonicalUnitCode, canonicalUnitCodeKeepingCount, displayUnit } from '@/utils/unitPricing'");
    expect(source).toContain(
      'canonicalUnitCodeKeepingCount(res.data.quantityUnit || row.unit || row.quantityUnit)');
    // 展示侧仍走 displayUnit —— 规范码不得直接示人。
    expect(source).not.toContain('{{ receiptUnit }}');
    expect(source).toContain('const receiptDisplayUnit = computed(() => displayUnit(receiptUnit.value))');
    expect(source).toContain('{{ receiptReportedQuantity }} {{ receiptDisplayUnit }}');
    expect(source).toContain('本步骤仅确认仓库实收，不会重复创建成品批次');
    expect(source).toContain('quantityUnit: receiptHasOutputLines.value ? null : receiptUnit.value');
  });

  it('confirms Workflow terminal outputs per SKU and preserves exact DAG input identity', () => {
    expect(source).toContain('本计划包含多个终端产出');
    expect(source).toContain('不同单位不会相加');
    expect(source).toContain('v-for="(line, index) in receiptForm.outputLines"');
    expect(source).toContain('batchNumber: line.reportedBatchNumber');
    expect(source).toContain(':precision="4"');
    expect(source).toContain(':step="0.0001"');
    expect(source).toContain('workflowMaterialNodeId: line.workflowMaterialNodeId || null');
    expect(source).toContain('workflowInputPortId: line.workflowInputPortId || null');
    expect(source).not.toContain('选择领用归属 SKU');
  });

  it('submits an independent local-date batchDate for sales-derived plans', () => {
    expect(source).toContain("ElMessage.warning('请选择批次日期')");
    expect(source).toContain('batchDate: planForm.value.batchDate');
    expect(source).toContain('plannedDate: planForm.value.plannedDate');
    expect(source).toContain('value-format="YYYY-MM-DD"');
  });

  it('localizes canonical units throughout plan list, detail and settlement display', () => {
    expect(source).toContain('`${v} ${displayUnit(unit)}`');
    expect(source).toContain('{{ completeActualQuantity }} {{ displayUnit(completePlannedUnit) }}');
    expect(source).toContain('{{ output.quantity }} {{ displayUnit(output.unit) }}');
    expect(source).toContain('{{ line.quantity }} {{ displayUnit(line.unit) }}');
    expect(source).toContain('{{ displayUnit(selectedWip.unit) }}');
  });

  it('removes the static backfill tutorial while preserving live shortage warnings', () => {
    expect(source).not.toContain('补录时效');
    expect(source).not.toContain('今天/昨天可补');
    expect(source).not.toContain('前天为极限');
    expect(source).not.toContain('大前天及更早禁止补录');
    expect(source).toContain('getPlanAdvisory(row)?.hasWarning');
    expect(source).toContain('缺料预警');
  });

  it('allows a single product to use a smallest-superset joint Workflow and still fails closed when none covers it', () => {
    expect(source).toContain('未找到覆盖该产品的工序 Workflow，请前往 Workflow 配置');
    expect(source).toContain('该 Workflow 会同时产出其它成品，需要确认完整产出集合');
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
