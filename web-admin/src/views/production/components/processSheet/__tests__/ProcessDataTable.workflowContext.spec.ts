// 2B Task F2 regression coverage (product-process-workflow runtime 2B, clerk-sheet):
//
// ProcessDataTable.vue renders a read-only "planned output / required inputs" banner whenever
// ProcessSheet.vue threads a non-null `workflowContext` prop (workflow-activated plan). Legacy
// plans never receive this prop (stays null via the component's own default), so the banner —
// and everything else on the row — must render exactly as before (fool-proof-design.md Rule
// 2/3: tell the clerk what to produce, read-only; no change to saveRow's request shape).
//
// BLOCKING correctness fix (adversarial review): `buildRequest()`'s `finished`/`unit` fields
// used to come ONLY from the name-keyword archetype heuristic (`processCode === 'qidiao'` /
// hardcoded 'kg'/'盒'), never from the workflow output port — even when workflowContext was
// present. Backend `ProcessSheetServiceImpl#validateWorkflowRowIfApplicable` validates the saved
// row's finished/unit against the port and 409s
// (WORKFLOW_ROW_OUTPUT_KIND_MISMATCH / WORKFLOW_ROW_OUTPUT_UNIT_MISMATCH) on mismatch, dead-ending
// the clerk on every save for any workflow whose finishing process name isn't literally "气调"
// (or whose output unit isn't 'kg'/'盒'). The tests below (in the new describe block) prove
// buildRequest now sources finished/unit from `workflowContext.output` when present, and leaves
// legacy (workflowContext-less) rows byte-identical to before.
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';

const getSemiFinishedInventory = vi.fn();
const getFinishedGoodsInventory = vi.fn();
const getAvailableRawBatches = vi.fn();
const saveRow = vi.fn();

vi.mock('@/api/processSheet', async () => {
  const actual = await vi.importActual<typeof import('@/api/processSheet')>('@/api/processSheet');
  return {
    ...actual,
    getSemiFinishedInventory: (...args: unknown[]) => getSemiFinishedInventory(...args),
    getFinishedGoodsInventory: (...args: unknown[]) => getFinishedGoodsInventory(...args),
    getAvailableRawBatches: (...args: unknown[]) => getAvailableRawBatches(...args),
    saveDraftRow: (...args: unknown[]) => saveRow(...args),
    submitRow: (...args: unknown[]) => saveRow(...args),
    deleteRow: vi.fn(),
    getRowHistory: vi.fn().mockResolvedValue({ success: true, data: [] }),
  };
});

const listWarehouses = vi.fn();
vi.mock('@/api/factoryWarehouse', () => ({
  listWarehouses: (...args: unknown[]) => listWarehouses(...args),
}));

import ProcessDataTable from '../ProcessDataTable.vue';

function mountTable(workflowContext: unknown) {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006',
      planId: 'PLAN-1',
      processCode: 'shuzhi',
      processOrder: 2,
      processLabel: '熟制',
      allowMultipleUpstreamSources: true,
      productTypeId: 'PT-1',
      upstreamItems: [],
      ownInventoryItems: [],
      initialRows: [],
      viewMode: 'card',
      workflowContext,
      inputUnit: 'kg',
      outputUnit: 'kg',
    },
    global: {
      plugins: [ElementPlus],
      stubs: { teleport: true, transition: false },
    },
  });
}

// -----------------------------------------------------------------------
// Helpers for the buildRequest (Part A) suite below. Mounts a `chaoshui` (焯水) single-upstream
// archetype table — deliberately NOT 'qidiao' — so the "even when processCode is a non-qidiao
// archetype" assertion in the task is meaningful. Drives the real card-view UI (add row → select
// source → fill before/after → click save) rather than reaching into private component internals,
// mirroring how ProcessSheet.vue actually drives ProcessDataTable in production.
// -----------------------------------------------------------------------
type MountOpts = {
  workflowContext?: unknown;
  seasoningPotEnabled?: boolean;
  seasoningConfigured?: boolean;
  inputUnit?: string;
  processCategory?: string | null;
};

function mountChaoshuiTable({
  workflowContext = null,
  seasoningPotEnabled = false,
  seasoningConfigured = seasoningPotEnabled,
  inputUnit = 'kg',
  processCategory = null,
}: MountOpts = {}) {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006',
      planId: 'PLAN-1',
      processCode: 'chaoshui', // 焯水: single-upstream archetype, NOT the finished-goods 'qidiao' one
      processOrder: 2,
      processLabel: '焯水',
      allowMultipleUpstreamSources: false,
      productTypeId: 'PT-1',
      upstreamItems: [{
        batchNumber: 'WIP-UP-1', produced: 1_000_000, used: 0, remaining: 1_000_000,
        status: 'ACTIVE', unit: 'kg', productTypeName: '上游半成品',
      }],
      ownInventoryItems: [],
      initialRows: [],
      viewMode: 'card',
      workflowContext,
      seasoningPotEnabled,
      seasoningConfigured,
      inputUnit,
      outputUnit: 'kg',
      processCategory,
    },
    global: {
      plugins: [ElementPlus],
      stubs: { teleport: true, transition: false },
    },
  });
}

async function setPotCount(wrapper: ReturnType<typeof mountChaoshuiTable>, value: number) {
  const field = wrapper.find('[data-testid="seasoning-pot-count"]');
  if (!field.exists()) throw new Error('找不到锅数字段');
  field.findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', value);
  await flushPromises();
}

async function clickAddRow(wrapper: ReturnType<typeof mountChaoshuiTable>) {
  const btn = wrapper.findAll('button').find((b) => b.text().includes('新增行'));
  if (!btn) throw new Error('找不到「+ 新增行」按钮');
  await btn.trigger('click');
  await flushPromises();
}

/**
 * 选中来源批次。
 *
 * 客户 2026-07-30 之后, 候选批次只有一条时界面直接显示批次文案、根本不渲染下拉
 * (「只有一个批次时自动选中, 不要让用户多点一次」)。这些用例只有一个上游批次, 所以走的正是
 * 那条路径 —— 断言改成「要么有下拉可以选, 要么已经自动选中并把批次显示出来」, 意图不变:
 * 这一行的来源批次必须落到 compositeKey 指的那一批。
 */
async function selectUpstreamSource(wrapper: ReturnType<typeof mountChaoshuiTable>, compositeKey: string) {
  const select = wrapper.findComponent({ name: 'ElSelect' });
  if (select.exists()) {
    select.vm.$emit('change', compositeKey);
    await flushPromises();
    return;
  }
  const fixed = wrapper.find('[data-testid="upstream-batch-fixed"]');
  if (!fixed.exists()) throw new Error('既没有来源批次下拉, 也没有自动选中的批次');
  const batchNumber = compositeKey.slice(compositeKey.indexOf('::') + 2);
  expect(fixed.text()).toContain(batchNumber);
  await flushPromises();
}

async function setNumberField(wrapper: ReturnType<typeof mountChaoshuiTable>, labelText: string, value: number) {
  if (labelText.startsWith('产出(')) {
    const workflowOutput = wrapper.find('[data-testid="workflow-output-line"]');
    if (workflowOutput.exists()) {
      const outputInput = workflowOutput.find('[data-testid="output-quantity"]')
        .findComponent({ name: 'ElInputNumber' });
      if (!outputInput.exists()) throw new Error('Workflow 产出行内找不到产出数量输入框');
      outputInput.vm.$emit('update:model-value', value);
      await wrapper.vm.$nextTick();
      return;
    }
  }
  const field = wrapper.findAll('.sp-card-field').find((f) => f.text().includes(labelText));
  if (!field) throw new Error(`找不到字段: ${labelText}`);
  const input = field.findComponent({ name: 'ElInputNumber' });
  if (!input.exists()) throw new Error(`字段 ${labelText} 内找不到 ElInputNumber`);
  input.vm.$emit('update:model-value', value);
  await flushPromises();
}

async function clickSave(wrapper: ReturnType<typeof mountChaoshuiTable>) {
  const btn = wrapper.findAll('button').find((b) => b.text().trim() === '正式报工');
  if (!btn) throw new Error('找不到「正式报工」按钮 (saveDisabledReason 未清空?)');
  await btn.trigger('click');
  await flushPromises();
}

describe('ProcessDataTable.vue workflow-planned-output banner (2B Task F2)', () => {
  beforeEach(() => {
    getSemiFinishedInventory.mockReset();
    getFinishedGoodsInventory.mockReset();
    getAvailableRawBatches.mockReset();
    getSemiFinishedInventory.mockResolvedValue({ success: true, data: [] });
    getFinishedGoodsInventory.mockResolvedValue({ success: true, data: [] });
    getAvailableRawBatches.mockResolvedValue({ success: true, data: [] });
  });

  it('shows the workflow-planned output (name/unit/成品-半成品 tag) and required raw input hint read-only, when workflowContext is present', async () => {
    const wrapper = mountTable({
      workflowNodeId: 'N1',
      workProcessId: 'WP1',
      processName: '熟制',
      defaultCostCategory: 'SEASONING',
      processOrder: 2,
      plannedUnit: 'kg',
      allowMultipleUpstreamSources: true,
      allowFinishedGoodsSource: false,
      customFieldSchema: null,
      inputs: [
        {
          workflowPortId: 'IN1', materialKind: 'RAW_MATERIAL', skuId: 'RM-1',
          materialName: '卤水调料', unit: 'kg', required: true, skuResolved: true, finished: false,
        },
      ],
      output: {
        workflowPortId: 'OUT1', materialKind: 'FINISHED_GOOD', skuId: 'PT-FIN-1',
        materialName: '卤猪蹄成品', unit: 'kg', required: true, skuResolved: true, finished: true,
      },
    });
    await flushPromises();

    const text = wrapper.text();
    expect(text).toContain('计划产出');
    expect(text).toContain('卤猪蹄成品');
    expect(text).toContain('kg');
    expect(text).toContain('成品');
    expect(text).toContain('需要原料');
    expect(text).toContain('卤水调料');
    // No stale/deleted-SKU warning when skuResolved is true.
    expect(text).not.toContain('已失效');
  });

  it('shows the "SKU 已失效" fool-proof warning (not a crash) when the planned output SKU has been deleted', async () => {
    const wrapper = mountTable({
      workflowNodeId: 'N1',
      workProcessId: 'WP1',
      processName: '熟制',
      defaultCostCategory: 'SEASONING',
      processOrder: 2,
      plannedUnit: 'kg',
      allowMultipleUpstreamSources: true,
      allowFinishedGoodsSource: false,
      customFieldSchema: null,
      inputs: [{
        workflowPortId: 'IN1', materialKind: 'RAW_MATERIAL', skuId: 'RM-1',
        materialName: '原料', unit: 'kg', required: true, skuResolved: true, finished: false,
      }],
      output: {
        workflowPortId: 'OUT1', materialKind: 'FINISHED_GOOD', skuId: 'PT-DELETED',
        materialName: null, unit: 'kg', required: true, skuResolved: false, finished: true,
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('SKU 已失效');
    expect(wrapper.text()).toContain('请回 Workflow 配置');
  });

  it('blocks reporting and points to Workflow repair when a required raw input has been deleted', async () => {
    const wrapper = mountTable({
      workflowNodeId: 'N1',
      workProcessId: 'WP1',
      processName: '熟制',
      defaultCostCategory: 'SEASONING',
      processOrder: 2,
      plannedUnit: 'kg',
      allowMultipleUpstreamSources: true,
      allowFinishedGoodsSource: false,
      customFieldSchema: null,
      inputs: [{
        workflowPortId: 'IN-DELETED', materialKind: 'RAW_MATERIAL', skuId: 'RMT-DELETED',
        materialName: null, unit: 'kg', required: true, skuResolved: false, finished: false,
      }],
      output: {
        workflowPortId: 'OUT1', materialKind: 'FINISHED_GOOD', skuId: 'PT-FIN-1',
        materialName: '盐葱横膈膜', unit: 'kg', required: true, skuResolved: true, finished: true,
      },
    });
    await flushPromises();

    const warning = wrapper.find('[data-testid="workflow-input-invalid"]');
    expect(warning.exists()).toBe(true);
    expect(warning.text()).toContain('当前计划绑定的原料已失效');
    expect(warning.text()).toContain('报工页只填写各原料实际投入量');
    expect(warning.text()).toContain('去产品-工序配置重新绑定');
  });

  it('renders no workflow banner at all for legacy processes (workflowContext null/default) — zero regression', async () => {
    const wrapper = mountTable(null);
    await flushPromises();

    const text = wrapper.text();
    expect(text).not.toContain('计划产出');
    expect(text).not.toContain('需要原料');
    expect(text).not.toContain('SKU 已失效');
  });
});

describe('ProcessDataTable.vue buildRequest sources finished/unit from the workflow output port (BLOCKING fix, Part A)', () => {
  beforeEach(() => {
    getSemiFinishedInventory.mockReset();
    getFinishedGoodsInventory.mockReset();
    getAvailableRawBatches.mockReset();
    saveRow.mockReset();
    getSemiFinishedInventory.mockResolvedValue({ success: true, data: [] });
    getFinishedGoodsInventory.mockResolvedValue({ success: true, data: [] });
    getAvailableRawBatches.mockResolvedValue({ success: true, data: [] });
    saveRow.mockResolvedValue({
      success: true,
      data: { clientRowId: 'CR-1', batchNumber: 'WIP-NEW-1', materialized: true, warnings: [] },
    });
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
  });

  it('(1) workflow output finished=true, unit="盒": the built request has finished:true, unit:"盒" even though processCode ("chaoshui") is a non-qidiao archetype', async () => {
    const wrapper = mountChaoshuiTable({
      workflowContext: {
        workflowNodeId: 'N1',
        workProcessId: 'WP1',
        processName: '气调包装(自定义命名不含气调二字)',
        defaultCostCategory: null,
        processOrder: 2,
        plannedUnit: '盒',
        allowMultipleUpstreamSources: false,
        allowFinishedGoodsSource: false,
        customFieldSchema: null,
        inputs: [{
          workflowPortId: 'IN1', materialKind: 'SEMI_FINISHED', skuId: 'PT-UP-1',
          materialName: '上游半成品', unit: 'kg', required: true, skuResolved: true, finished: false,
        }],
        output: {
          workflowPortId: 'OUT1', materialKind: 'FINISHED_GOOD', skuId: 'PT-FIN-1',
          materialName: '卤猪蹄成品', unit: '盒', required: true, skuResolved: true, finished: true,
        },
      },
    });
    await flushPromises();

    await clickAddRow(wrapper);
    await selectUpstreamSource(wrapper, 'wip::WIP-UP-1');
    await setNumberField(wrapper, '投入(kg)', 10);
    await setNumberField(wrapper, '产出(盒)', 8);
    await clickSave(wrapper);

    expect(saveRow).toHaveBeenCalledTimes(1);
    const [factoryId, planId, req] = saveRow.mock.calls[0] as [string, string, Record<string, unknown>];
    expect(factoryId).toBe('F006');
    expect(planId).toBe('PLAN-1');
    expect(req.processCode).toBe('chaoshui'); // confirms the non-qidiao archetype premise
    expect(req.finished).toBe(true);
    expect(req.unit).toBe('盒');
  });

  it('(2) workflow output finished=false, unit="只" (semi-finished): the built request has finished:false, unit:"只"', async () => {
    const wrapper = mountChaoshuiTable({
      workflowContext: {
        workflowNodeId: 'N1',
        workProcessId: 'WP1',
        processName: '焯水',
        defaultCostCategory: null,
        processOrder: 2,
        plannedUnit: '只',
        allowMultipleUpstreamSources: false,
        allowFinishedGoodsSource: false,
        customFieldSchema: null,
        inputs: [{
          workflowPortId: 'IN1', materialKind: 'SEMI_FINISHED', skuId: 'PT-UP-1',
          materialName: '上游半成品', unit: 'kg', required: true, skuResolved: true, finished: false,
        }],
        output: {
          workflowPortId: 'OUT1', materialKind: 'SEMI_FINISHED', skuId: 'PT-SEMI-1',
          materialName: '焯水半成品', unit: '只', required: true, skuResolved: true, finished: false,
        },
      },
    });
    await flushPromises();

    await clickAddRow(wrapper);
    await selectUpstreamSource(wrapper, 'wip::WIP-UP-1');
    await setNumberField(wrapper, '投入(kg)', 5);
    await setNumberField(wrapper, '产出(只)', 4);
    await clickSave(wrapper);

    expect(saveRow).toHaveBeenCalledTimes(1);
    const [, , req] = saveRow.mock.calls[0] as [string, string, Record<string, unknown>];
    expect(req.finished).toBe(false);
    expect(req.unit).toBe('只');
  });

  it('workflow g → 件 uses one authoritative resolver for inputUnit/outputUnit/unit and ignores plannedUnit', async () => {
    const wrapper = mountChaoshuiTable({
      workflowContext: {
        workflowNodeId: 'N1',
        workProcessId: 'WP1',
        processName: '称重装件',
        defaultCostCategory: null,
        processOrder: 2,
        plannedUnit: 'kg',
        allowMultipleUpstreamSources: false,
        allowFinishedGoodsSource: false,
        customFieldSchema: null,
        inputs: [{
          workflowPortId: 'IN1', materialKind: 'SEMI_FINISHED', skuId: 'PT-UP-1',
          materialName: '称重半成品', unit: 'g', required: true, skuResolved: true, finished: false,
        }],
        output: {
          workflowPortId: 'OUT1', materialKind: 'FINISHED_GOOD', skuId: 'PT-FIN-1',
          materialName: '独立装件成品', unit: '件', required: true, skuResolved: true, finished: true,
        },
      },
    });
    await flushPromises();

    await clickAddRow(wrapper);
    await selectUpstreamSource(wrapper, 'wip::WIP-UP-1');
    await setNumberField(wrapper, '投入(kg)', 0.5);
    await setNumberField(wrapper, '产出(件)', 8);
    await clickSave(wrapper);

    expect(saveRow).toHaveBeenCalledTimes(1);
    const [, , req] = saveRow.mock.calls[0] as [string, string, Record<string, unknown>];
    expect(req.inputUnit).toBe('kg');
    expect(req.outputUnit).toBe('件');
    expect(req.unit).toBe('件');
    expect(req.outputUnit).toBe(req.unit);
    expect(req.productTypeId).toBe('PT-FIN-1');
  });

  it('(4) legacy (workflowContext null): buildRequest is byte-identical to the pre-fix archetype heuristic (finished:false, unit:"kg" for a non-qidiao archetype)', async () => {
    const wrapper = mountChaoshuiTable({ workflowContext: null });
    await flushPromises();

    await clickAddRow(wrapper);
    await selectUpstreamSource(wrapper, 'wip::WIP-UP-1');
    await setNumberField(wrapper, '投入(kg)', 10);
    await setNumberField(wrapper, '产出(kg)', 8);
    await clickSave(wrapper);

    expect(saveRow).toHaveBeenCalledTimes(1);
    const [, , req] = saveRow.mock.calls[0] as [string, string, Record<string, unknown>];
    // Pre-fix legacy behavior: finished only ever true for processCode === 'qidiao'; unit
    // hardcoded 'kg' for the isSingleUpstream (chaoshui/gunrou) branch. Zero regression.
    expect(req.finished).toBe(false);
    expect(req.unit).toBe('kg');
    expect(req.inputQuantity).toBe(10);
    expect(req.outputQuantity).toBe(8);
  });

  it('shows pot count only from explicit seasoning config and serializes an equal kg split', async () => {
    const wrapper = mountChaoshuiTable({
      seasoningPotEnabled: true,
      processCategory: 'PROCESSING',
    });
    await flushPromises();

    await clickAddRow(wrapper);
    expect(wrapper.find('[data-testid="seasoning-pot-count"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('第1锅');
    await selectUpstreamSource(wrapper, 'wip::WIP-UP-1');
    await setNumberField(wrapper, '投入(kg)', 300);
    await setNumberField(wrapper, '产出(kg)', 280);
    await setPotCount(wrapper, 3);
    expect(wrapper.text()).toContain('每锅 100.00 kg');
    await clickSave(wrapper);

    const [, , req] = saveRow.mock.calls[0] as [string, string, Record<string, unknown>];
    expect(req.seasoningStep).toBe(true);
    expect(req.potCount).toBe(3);
    expect(req.potRawKgs).toEqual([100, 100, 100]);
  });

  it('does not show pot count for an unconfigured 熟制 category', async () => {
    const wrapper = mountChaoshuiTable({
      seasoningPotEnabled: false,
      processCategory: '熟制',
    });
    await flushPromises();
    await clickAddRow(wrapper);
    expect(wrapper.find('[data-testid="seasoning-pot-count"]').exists()).toBe(false);
  });

  it('serializes one kg-normalized seasoning input for a configured non-pot process', async () => {
    const wrapper = mountChaoshuiTable({
      seasoningConfigured: true,
      seasoningPotEnabled: false,
      inputUnit: 'g',
    });
    await flushPromises();

    await clickAddRow(wrapper);
    expect(wrapper.find('[data-testid="seasoning-pot-count"]').exists()).toBe(false);
    await selectUpstreamSource(wrapper, 'wip::WIP-UP-1');
    await setNumberField(wrapper, '投入(g)', 2500);
    await setNumberField(wrapper, '产出(kg)', 2);
    await clickSave(wrapper);

    const [, , req] = saveRow.mock.calls[0] as [string, string, Record<string, unknown>];
    expect(req.seasoningStep).toBe(true);
    expect(req.potCount).toBe(1);
    expect(req.potRawKgs).toEqual([2.5]);
  });

  it('converts configured g input to equal backend kg pot weights', async () => {
    const wrapper = mountChaoshuiTable({
      seasoningPotEnabled: true,
      inputUnit: 'g',
    });
    await flushPromises();

    await clickAddRow(wrapper);
    await selectUpstreamSource(wrapper, 'wip::WIP-UP-1');
    await setNumberField(wrapper, '投入(g)', 300_000);
    await setNumberField(wrapper, '产出(kg)', 280);
    await setPotCount(wrapper, 3);
    await clickSave(wrapper);

    const [, , req] = saveRow.mock.calls[0] as [string, string, Record<string, unknown>];
    expect(req.potRawKgs).toEqual([100, 100, 100]);
  });
});
