/**
 * 画布上标成「副产」的物料, 在报工界面必须按副产渲染 —— 产品负责人 2026-08-17 当面报障。
 *
 * 缺陷 (F006 / SOP-20260817-01-黄油鸡「原料处理」):
 *   画布产出物料区挂了「半成品 Cell = 处理后半成品」和「副产 Cell = 肥油(YL119)」。
 *   报工界面渲染出**两行**, 肥油那行标签写着「半成品」。
 *
 * 正确形态 (产品负责人原话):
 *   ① 画布标成副产的, 报工时也标成「副产」;
 *   ② 副产**不独立成行**;
 *   ③ 它应该填进主产出行下方那块「副产数量 + 副产回收单价」—— SKU 由画布带出,
 *      用户只填数量和回收单价;
 *   ④ 行内手填与画布绑定**二选一**, 不并存。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';
import type { ProcessSheetRowRequest } from '@/api/processSheet';
import {
  byproductEntryMode,
  partitionOutputPorts,
} from '../processSheetOutputs';

const saveDraftRow = vi.fn();
const submitRow = vi.fn();

vi.mock('@/api/processSheet', async () => {
  const actual = await vi.importActual<typeof import('@/api/processSheet')>('@/api/processSheet');
  return {
    ...actual,
    saveDraftRow: (...args: unknown[]) => saveDraftRow(...args),
    submitRow: (...args: unknown[]) => submitRow(...args),
    deleteRow: vi.fn(),
    getRowHistory: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getAvailableRawBatches: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getSemiFinishedInventory: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getFinishedGoodsInventory: vi.fn().mockResolvedValue({ success: true, data: [] }),
  };
});

vi.mock('@/api/factoryWarehouse', () => ({
  listWarehouses: vi.fn().mockResolvedValue({ success: true, data: [] }),
}));

import ProcessDataTable from '../ProcessDataTable.vue';

// ---------------------------------------------------------------------------
// 纯函数层 —— 归位逻辑本身
// ---------------------------------------------------------------------------

describe('partitionOutputPorts', () => {
  const main = { workflowPortId: 'OUT-SEMI', byproduct: false, materialName: '处理后半成品' };
  const fat = { workflowPortId: 'OUT-FAT', byproduct: true, materialName: '肥油' };
  const skin = { workflowPortId: 'OUT-SKIN', byproduct: true, materialName: '鸡皮' };

  it('🔴 副产不进 rowPorts, 而是挂到第一条主产出行下面', () => {
    const result = partitionOutputPorts([main, fat]);
    expect(result.rowPorts.map((p) => p.workflowPortId)).toEqual(['OUT-SEMI']);
    expect(result.inlineByproductPorts.map((p) => p.workflowPortId)).toEqual(['OUT-FAT']);
    expect(result.hostPortId).toBe('OUT-SEMI');
    expect(result.orphanNotice).toBeNull();
  });

  it('阴性对照: 没有副产时一切照旧 —— 全部独立成行, 没有副产区', () => {
    const other = { workflowPortId: 'OUT-B', byproduct: false, materialName: '另一半成品' };
    const result = partitionOutputPorts([main, other]);
    expect(result.rowPorts).toHaveLength(2);
    expect(result.inlineByproductPorts).toHaveLength(0);
    expect(result.hostPortId).toBeNull();
  });

  it('多个副产 Cell 时【全部】归入副产区 —— 不许只取第一个', () => {
    const result = partitionOutputPorts([main, fat, skin]);
    expect(result.inlineByproductPorts.map((p) => p.materialName)).toEqual(['肥油', '鸡皮']);
  });

  it('只有副产、没有主产出时不静默丢弃: 提升成行并给出说明', () => {
    const result = partitionOutputPorts([fat, skin]);
    expect(result.rowPorts.map((p) => p.workflowPortId)).toEqual(['OUT-FAT', 'OUT-SKIN']);
    expect(result.inlineByproductPorts).toHaveLength(0);
    expect(result.orphanNotice).toContain('肥油');
    expect(result.orphanNotice).toContain('鸡皮');
    expect(result.orphanNotice).toContain('没有主产出');
  });

  it('byproduct 字段缺失 (老后端快照) 按「不是副产」处理, 行为逐字不变', () => {
    const legacy = { workflowPortId: 'OUT-LEGACY', materialName: '老端口' };
    const result = partitionOutputPorts([legacy]);
    expect(result.rowPorts).toHaveLength(1);
    expect(result.inlineByproductPorts).toHaveLength(0);
  });
});

describe('byproductEntryMode 二选一', () => {
  it('画布绑了副产 → BOUND', () => {
    expect(byproductEntryMode({
      boundByproducts: [{
        workflowPortId: 'OUT-FAT', materialNodeId: 'N', productTypeId: 'S',
        materialName: '肥油', unit: 'kg', quantity: null, unitPrice: null,
      }],
    })).toBe('BOUND');
  });

  it('没绑 → MANUAL (沿用原来那对手填字段)', () => {
    expect(byproductEntryMode({ boundByproducts: [] })).toBe('MANUAL');
  });
});

// ---------------------------------------------------------------------------
// 真实入口 —— 挂载组件, 断言界面与提交载荷
// ---------------------------------------------------------------------------

const MAIN_PORT = {
  workflowPortId: 'output:1786933016386', materialNodeId: 'material:semi:1786933016386',
  materialKind: 'SEMI_FINISHED' as const, skuId: 'PTF0060156',
  materialName: 'SOP-20260817-01-黄油鸡-处理后半成品', unit: 'kg',
  required: true, skuResolved: true, finished: false, byproduct: false,
};
const BYPRODUCT_PORT = {
  workflowPortId: 'output:1786934233525', materialNodeId: 'material:output:1786934233525',
  materialKind: 'SEMI_FINISHED' as const, skuId: 'RMT_2ac4f36e',
  materialName: 'SOP-20260817-01-黄油鸡-肥油', unit: 'kg',
  required: false, skuResolved: true, finished: false, byproduct: true,
};

function contextWith(outputs: Array<Record<string, unknown>>) {
  return {
    workflowNodeId: 'process:e5551abc', workProcessId: 'e5551abc',
    processName: 'SOP-20260817-01-黄油鸡-原料处理', processCategory: null,
    defaultCostCategory: null, processOrder: 1, plannedUnit: 'kg',
    allowMultipleUpstreamSources: false, allowFinishedGoodsSource: false, customFieldSchema: null,
    inputs: [{
      workflowPortId: 'input:1786933016386', materialNodeId: 'material:raw',
      materialKind: 'RAW_MATERIAL', skuId: 'RMT_41e1a2d4',
      materialName: 'SOP-20260817-01-黄油鸡-原料A', unit: 'kg',
      required: true, skuResolved: true, finished: false,
    }],
    output: outputs[0],
    outputs,
  };
}

function mountTable(context: ReturnType<typeof contextWith>) {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-BUTTER-CHICKEN', processCode: 'xiuyou', processOrder: 1,
      processLabel: 'SOP-20260817-01-黄油鸡-原料处理', productTypeId: 'PTF0060156',
      inputUnit: 'kg', outputUnit: 'kg', isFirstProcess: true,
      initialRows: [], upstreamItems: [], ownInventoryItems: [],
      viewMode: 'card', workflowContext: context,
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  });
}

async function addRow(wrapper: ReturnType<typeof mountTable>) {
  await wrapper.findAll('button').find((item) => item.text().includes('新增行'))!.trigger('click');
  await flushPromises();
}

describe('ProcessDataTable 副产角色渲染 (F006 黄油鸡实测形状)', () => {
  beforeEach(() => {
    saveDraftRow.mockReset();
    submitRow.mockReset();
    vi.restoreAllMocks();
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    submitRow.mockResolvedValue({
      success: true,
      data: { submissionStatus: 'SUBMITTED', materialized: true, batchNumber: 'B-1', outputs: [] },
    });
  });

  it('🔴 回归: 副产【不独立成行】—— 两个产出端口只渲染一行', async () => {
    const wrapper = mountTable(contextWith([MAIN_PORT, BYPRODUCT_PORT]));
    await addRow(wrapper);

    const lines = wrapper.findAll('[data-testid="workflow-output-line"]');
    expect(lines).toHaveLength(1);
    expect(lines[0].text()).toContain('SOP-20260817-01-黄油鸡-处理后半成品');
  });

  it('阳性对照: 把 byproduct 关掉, 同样两个端口就渲染两行 —— 证明上一条不是恒真', async () => {
    const notByproduct = { ...BYPRODUCT_PORT, byproduct: false };
    const wrapper = mountTable(contextWith([MAIN_PORT, notByproduct]));
    await addRow(wrapper);

    expect(wrapper.findAll('[data-testid="workflow-output-line"]')).toHaveLength(2);
  });

  it('🔴 回归: 副产填进主产出行的副产区, SKU 由画布带出且只读', async () => {
    const wrapper = mountTable(contextWith([MAIN_PORT, BYPRODUCT_PORT]));
    await addRow(wrapper);

    const bound = wrapper.findAll('[data-testid="byproduct-bound-item"]');
    expect(bound).toHaveLength(1);
    expect(bound[0].find('[data-testid="byproduct-bound-name"]').text())
      .toContain('SOP-20260817-01-黄油鸡-肥油');
    // 品名是画布配的, 界面上不给选 —— fool-proof: 操作员不能自由选产品。
    expect(bound[0].findComponent({ name: 'ElSelect' }).exists()).toBe(false);
  });

  it('🔴 二选一: 绑定模式下手填的「副产数量/副产回收单价」不再出现', async () => {
    const wrapper = mountTable(contextWith([MAIN_PORT, BYPRODUCT_PORT]));
    await addRow(wrapper);

    expect(wrapper.find('[data-testid="byproduct-bound-list"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="byproduct-quantity"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="byproduct-unit-price"]').exists()).toBe(false);
  });

  it('阴性对照: 没有画布绑定副产时, 手填那对字段照常在 (老工作流零回归)', async () => {
    const wrapper = mountTable(contextWith([MAIN_PORT]));
    await addRow(wrapper);

    expect(wrapper.find('[data-testid="byproduct-bound-list"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="byproduct-quantity"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="byproduct-unit-price"]').exists()).toBe(true);
  });

  it('多个副产 Cell 全部列出 —— 静默只取第一个是明令禁止的', async () => {
    const second = {
      ...BYPRODUCT_PORT, workflowPortId: 'output:skin',
      materialNodeId: 'material:output:skin', skuId: 'RMT_skin', materialName: '鸡皮',
    };
    const wrapper = mountTable(contextWith([MAIN_PORT, BYPRODUCT_PORT, second]));
    await addRow(wrapper);

    expect(wrapper.findAll('[data-testid="workflow-output-line"]')).toHaveLength(1);
    const names = wrapper.findAll('[data-testid="byproduct-bound-name"]').map((n) => n.text());
    expect(names).toHaveLength(2);
    expect(names.join('|')).toContain('肥油');
    expect(names.join('|')).toContain('鸡皮');
  });

  it('只有副产没有主产出时: 提升成行, 标签是「副产」, 并显式说明配置缺口', async () => {
    const wrapper = mountTable(contextWith([BYPRODUCT_PORT]));
    await addRow(wrapper);

    const lines = wrapper.findAll('[data-testid="workflow-output-line"]');
    expect(lines).toHaveLength(1);
    expect(lines[0].find('[data-testid="output-role-tag"]').text()).toBe('副产');
    expect(wrapper.find('[data-testid="byproduct-orphan-notice"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="byproduct-orphan-notice"]').text()).toContain('没有主产出');
  });

  it('🔴 标签三态: 主产出仍是「半成品」, 不能因为这次改动全变副产', async () => {
    const wrapper = mountTable(contextWith([MAIN_PORT, BYPRODUCT_PORT]));
    await addRow(wrapper);

    expect(wrapper.find('[data-testid="output-role-tag"]').text()).toBe('半成品');
  });

  it('🔴 提交载荷: 副产按【画布 SKU 真名】上报, 不再是硬编码的占位串「副产」', async () => {
    const wrapper = mountTable(contextWith([MAIN_PORT, BYPRODUCT_PORT]));
    await addRow(wrapper);

    wrapper.find('[data-testid="material-input-total"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 100);
    wrapper.find('[data-testid="production-date"]')
      .findComponent({ name: 'ElDatePicker' }).vm.$emit('update:model-value', '2026-08-17');
    wrapper.find('[data-testid="output-quantity"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 64);

    const bound = wrapper.find('[data-testid="byproduct-bound-item"]');
    const numbers = bound.findAllComponents({ name: 'ElInputNumber' });
    numbers[0].vm.$emit('update:model-value', 3.5);   // 副产数量
    numbers[1].vm.$emit('update:model-value', 12.5);  // 副产回收单价
    await flushPromises();

    await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
    await flushPromises();

    const request = submitRow.mock.calls[0][2] as ProcessSheetRowRequest;
    expect(request.byproducts).toHaveLength(1);
    expect(request.byproducts![0]).toMatchObject({
      name: 'SOP-20260817-01-黄油鸡-肥油',
      quantity: 3.5,
      unit: 'kg',
      unitPrice: 12.5,
    });
    expect(request.byproducts![0].name).not.toBe('副产');
  });

  it('阴性对照: 没绑定时提交的仍是占位串「副产」—— 老路径行为逐字不变', async () => {
    const wrapper = mountTable(contextWith([MAIN_PORT]));
    await addRow(wrapper);

    wrapper.find('[data-testid="material-input-total"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 100);
    wrapper.find('[data-testid="production-date"]')
      .findComponent({ name: 'ElDatePicker' }).vm.$emit('update:model-value', '2026-08-17');
    wrapper.find('[data-testid="output-quantity"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 64);
    wrapper.find('[data-testid="byproduct-quantity"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 2);
    await flushPromises();

    await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
    await flushPromises();

    const request = submitRow.mock.calls[0][2] as ProcessSheetRowRequest;
    expect(request.byproducts![0].name).toBe('副产');
  });

  it('副产没填数量就不上报 —— 不许把空的副产当成 0 产出发上去', async () => {
    const wrapper = mountTable(contextWith([MAIN_PORT, BYPRODUCT_PORT]));
    await addRow(wrapper);

    wrapper.find('[data-testid="material-input-total"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 100);
    wrapper.find('[data-testid="production-date"]')
      .findComponent({ name: 'ElDatePicker' }).vm.$emit('update:model-value', '2026-08-17');
    wrapper.find('[data-testid="output-quantity"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 64);
    await flushPromises();

    await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
    await flushPromises();

    const request = submitRow.mock.calls[0][2] as ProcessSheetRowRequest;
    expect(request.byproducts).toBeUndefined();
  });
});
