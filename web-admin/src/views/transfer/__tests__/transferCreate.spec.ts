import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  aggregateFinishedGoodsOptions,
  aggregateMaterialInventoryOptions,
  aggregateTransferDemand,
  applySelectedOption,
  findDuplicateTransferRow,
  isTransferRowShortage,
  optionsForItemType,
  TRANSFER_TYPE_OPTIONS,
  toTransferItemPayload,
  transferRowIdentity,
  type TransferCreateRow,
} from '../transferCreate';
import { displayUnit } from '@/utils/unitPricing';

const listSource = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');

function row(itemType: TransferCreateRow['itemType']): TransferCreateRow {
  return {
    itemType,
    selectedItemId: '',
    itemName: '',
    quantity: 5,
    unit: 'kg',
  };
}

describe('M08 手动调拨选择器契约', () => {
  it('调拨类型只展示清晰中文语义，不向业务用户泄漏枚举代码', () => {
    expect(TRANSFER_TYPE_OPTIONS.map((option) => option.label)).toEqual([
      '总部调往分部', '分部之间调拨', '分部退回总部', '同一工厂仓库调拨',
    ]);
    expect(TRANSFER_TYPE_OPTIONS.every((option) => !/[A-Z_]{3,}/.test(option.label))).toBe(true);
  });

  it('调出/调入仓库必选，数量与只读单位相邻且现有库存在其右侧', () => {
    expect(listSource).toContain("sourceWarehouseId: [{ required: true");
    expect(listSource).toContain("targetWarehouseId: [{ required: true");
    expect(listSource).toContain('<el-table-column label="数量 / 单位"');
    expect(listSource).toContain('class="unit-chip"');
    expect(listSource).not.toContain('v-model="row.unit"');
    expect(listSource.indexOf('label="数量 / 单位"')).toBeLessThan(listSource.indexOf('label="现有库存"'));
  });
  it('按所选仓库聚合唯一可用成品 SKU，并保留 canonical box / 中文显示盒', () => {
    const options = aggregateFinishedGoodsOptions([
      {
        productTypeId: 'CPF0060015',
        productName: 'E2E-MVP-20260719-2111-黄油鸡-成品800g',
        unit: 'box',
        status: 'AVAILABLE',
        producedQuantity: '5',
        shippedQuantity: '0',
        reservedQuantity: '0',
      },
      {
        productTypeId: 'DEPLETED-SKU',
        productName: '已耗尽成品',
        unit: 'case',
        status: 'DEPLETED',
        availableQuantity: '0',
      },
    ]);

    expect(options).toEqual([
      expect.objectContaining({
        id: 'CPF0060015',
        name: 'E2E-MVP-20260719-2111-黄油鸡-成品800g',
        unit: 'box',
        currentStock: 5,
      }),
    ]);
    expect(displayUnit(options[0].unit)).toBe('盒');
  });

  it('选择成品后提交 productTypeId，不泄漏 materialTypeId 或中文单位', () => {
    const target = row('FINISHED_GOODS');
    applySelectedOption(target, {
      id: 'CPF0060015',
      name: '黄油鸡-成品800g',
      unit: '盒',
      currentStock: 5,
      unitPrice: 11.2,
    });

    expect(target.productTypeId).toBe('CPF0060015');
    expect(target.materialTypeId).toBeUndefined();
    expect(target._currentStock).toBe(5);
    expect(toTransferItemPayload(target)).toEqual({
      itemType: 'FINISHED_GOODS',
      productTypeId: 'CPF0060015',
      itemName: '黄油鸡-成品800g',
      quantity: 5,
      unit: 'box',
      unitPrice: 11.2,
      remark: undefined,
    });
  });

  it('同仓多个 box 批次相加，case/slice/g/kg canonical 单位不误转换', () => {
    const options = aggregateFinishedGoodsOptions([
      { productTypeId: 'P-BOX', productName: '盒装', unit: 'box', status: 'AVAILABLE', availableQuantity: 2 },
      { productTypeId: 'P-BOX', productName: '盒装', unit: '盒', status: 'AVAILABLE', availableQuantity: 3 },
      { productTypeId: 'P-CASE', productName: '箱装', unit: 'case', status: 'AVAILABLE', availableQuantity: 1 },
      { productTypeId: 'P-SLICE', productName: '片装', unit: 'slice', status: 'AVAILABLE', availableQuantity: 4 },
      { productTypeId: 'P-G', productName: '克装', unit: 'g', status: 'AVAILABLE', availableQuantity: 500 },
      { productTypeId: 'P-KG', productName: '千克装', unit: 'kg', status: 'AVAILABLE', availableQuantity: 2 },
    ]);
    expect(options.find((option) => option.id === 'P-BOX')?.currentStock).toBe(5);
    expect(Object.fromEntries(options.map((option) => [option.id, displayUnit(option.unit)]))).toMatchObject({
      'P-BOX': '盒', 'P-CASE': '箱', 'P-SLICE': '片', 'P-G': 'g', 'P-KG': 'kg',
    });
  });

  it('原料和包材仍使用 materialTypeId，并按 category 分流且取所选仓库库存', () => {
    const materialOptions = aggregateMaterialInventoryOptions([
      { materialTypeId: 'RAW-A', materialName: '原料 A', materialCategory: 'RAW', quantityUnit: 'kg', currentQuantity: 5, status: 'AVAILABLE' },
      { materialTypeId: 'PACK-BOX', materialName: '成品盒', materialCategory: 'PACKAGING', quantityUnit: 'box', currentQuantity: 5, status: 'AVAILABLE' },
    ]);
    const rawOptions = optionsForItemType('RAW_MATERIAL', materialOptions, []);
    const packagingOptions = optionsForItemType('PACKAGING_MATERIAL', materialOptions, []);
    expect(rawOptions.map((option) => option.id)).toEqual(['RAW-A']);
    expect(packagingOptions.map((option) => option.id)).toEqual(['PACK-BOX']);

    const packagingRow = row('PACKAGING_MATERIAL');
    packagingRow.quantity = 1;
    applySelectedOption(packagingRow, packagingOptions[0]);
    expect(toTransferItemPayload(packagingRow)).toMatchObject({
      itemType: 'PACKAGING_MATERIAL', materialTypeId: 'PACK-BOX', unit: 'box', quantity: 1,
    });
    expect(packagingRow.productTypeId).toBeUndefined();
  });

  it('原料可按包装数量调拨，但 payload 带规格身份并由后端折合基本量', () => {
    const raw = row('RAW_MATERIAL');
    applySelectedOption(raw, {
      id: 'RAW-KG',
      name: '冷冻原料',
      unit: 'kg',
      currentStock: 80,
      unitPrice: 12,
    });
    raw.quantity = 8;
    raw.unit = 'case';
    raw.materialPackagingSpecId = 'SPEC-CASE-10KG';
    raw._packageFactor = 10;
    raw._inventoryUnit = 'kg';

    expect(toTransferItemPayload(raw)).toMatchObject({
      itemType: 'RAW_MATERIAL',
      materialTypeId: 'RAW-KG',
      materialPackagingSpecId: 'SPEC-CASE-10KG',
      quantity: 8,
      unit: 'case',
      unitPrice: 120,
    });
    expect(listSource).toContain('label="调拨包装"');
    expect(listSource).toContain('折合 {{ formatStock(transferBaseQuantity(row)) }}');
    expect(listSource).toContain('transferBaseQuantity(it) > Number(stock)');
  });
});

/**
 * 2026-08-09 六膳门 prod 事故 TRF-20260809-1790 —— 同一原料写成两行各 1000kg, 主仓只有 1000kg。
 * 三道闸(建单表单/后端建单/详情页)全是按行比可用量, 每行都合法, 合计翻倍无人过问;
 * 审批通过后点「确认调拨入库」才炸, 而那时明细已不可编辑。
 */
describe('同一物料重复行 (TRF-20260809-1790)', () => {
  function pickedRow(id: string, name: string, quantity: number): TransferCreateRow {
    const r = row('RAW_MATERIAL');
    r.selectedItemId = id;
    r.materialTypeId = id;
    r.itemName = name;
    r.quantity = quantity;
    r._inventoryUnit = 'kg';
    r._packageFactor = 1;
    return r;
  }

  it('事故复现 — 同一 materialTypeId 两行被认出, 并给出合计基本量', () => {
    const dup = findDuplicateTransferRow([
      pickedRow('RMT_1786268511123', '金蒜牛排调味料 滚揉用', 1000),
      pickedRow('RMT_1786268298741', '冰水', 1000),
      pickedRow('RMT_1786268511123', '金蒜牛排调味料 滚揉用', 1000),
    ]);

    expect(dup).not.toBeNull();
    expect(dup!.name).toBe('金蒜牛排调味料 滚揉用');
    expect(dup!.rows).toHaveLength(2);
    // 合计才是真实需求 —— 逐行看是 1000 ≤ 1000 "够", 加起来是 2000 > 1000 "不够"。
    expect(dup!.totalBaseQuantity).toBe(2000);
    expect(dup!.baseUnit).toBe('kg');
  });

  it('按包装下单时合计折算成基本量 (2 箱×10kg + 5kg = 25kg)', () => {
    const byCase = pickedRow('RAW-KG', '冷冻原料', 2);
    byCase.unit = 'case';
    byCase._packageFactor = 10;
    const byKg = pickedRow('RAW-KG', '冷冻原料', 5);

    expect(findDuplicateTransferRow([byCase, byKg])!.totalBaseQuantity).toBe(25);
  });

  it('阴性对照 — 不同物料的多行不算重复, 未选物料的空行也不算', () => {
    expect(findDuplicateTransferRow([
      pickedRow('RMT_A', '甲', 1),
      pickedRow('RMT_B', '乙', 1),
    ])).toBeNull();
    // 两个空行 identity 都是 null, 不能被凑成一组"重复"
    expect(findDuplicateTransferRow([row('RAW_MATERIAL'), row('RAW_MATERIAL')])).toBeNull();
  });

  it('原料与成品即使 id 相同也不算同一物料', () => {
    const raw = pickedRow('X-1', '同名', 1);
    const fg = row('FINISHED_GOODS');
    fg.selectedItemId = 'X-1';
    fg.productTypeId = 'X-1';
    fg.itemName = '同名';

    expect(findDuplicateTransferRow([raw, fg])).toBeNull();
    expect(transferRowIdentity(raw)).toBe('M:X-1');
    expect(transferRowIdentity(fg)).toBe('P:X-1');
  });

  it('建单表单接了这道闸: 提交前拦截 + 下拉里已选物料不可再选', () => {
    expect(listSource).toContain('findDuplicateTransferRow(form.value.items)');
    expect(listSource).toContain(':disabled="isPickedInAnotherRow(row, m.id)"');
  });
});

/**
 * 详情页承接的是"禁令之前已落库"的存量单据 (如 TRF-20260809-1790 本身)。
 * 逐行比 currentStock 对重复行完全沉默 —— currentStock 是按物料查的, 两行拿到同一个数字。
 */
describe('详情页缺货判定按物料合计 (存量重复行单据)', () => {
  const dupRows = [
    { itemType: 'RAW_MATERIAL', materialTypeId: 'RMT_1786268511123', quantity: 1000, currentStock: 1000 },
    { itemType: 'RAW_MATERIAL', materialTypeId: 'RMT_1786268298741', quantity: 1000, currentStock: 1000 },
    { itemType: 'RAW_MATERIAL', materialTypeId: 'RMT_1786268511123', quantity: 1000, currentStock: 1000 },
  ];

  it('事故复现 — 重复行的两行都判缺货 (合计 2000 > 库存 1000)', () => {
    const demand = aggregateTransferDemand(dupRows);
    expect(demand.get('M:RMT_1786268511123')).toBe(2000);
    expect(dupRows.map(r => isTransferRowShortage(r, demand))).toEqual([true, false, true]);
  });

  it('阴性对照 — 无重复行时口径不变 (逐行够就是够, 不够就是不够)', () => {
    const rows = [
      { itemType: 'RAW_MATERIAL', materialTypeId: 'A', quantity: 1000, currentStock: 1000 },
      { itemType: 'RAW_MATERIAL', materialTypeId: 'B', quantity: 1001, currentStock: 1000 },
      { itemType: 'FINISHED_GOODS', productTypeId: 'C', quantity: 1, currentStock: null },
    ];
    const demand = aggregateTransferDemand(rows);
    expect(rows.map(r => isTransferRowShortage(r, demand))).toEqual([false, true, false]);
  });

  it('详情页接了这道闸, 且同厂调拨不再绕过它', () => {
    const detailSource = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');
    expect(detailSource).toContain('aggregateTransferDemand');
    expect(detailSource).toContain('isTransferRowShortage');
    // 缺陷版本用 `isOutbound && !isIntraFactory && hasStockShortage` 把同厂调拨排除在闸外
    expect(detailSource).not.toContain('!isIntraFactory && hasStockShortage');
    expect(detailSource).toContain("transfer.status === 'APPROVED' && isOutbound && hasStockShortage");
  });
});
