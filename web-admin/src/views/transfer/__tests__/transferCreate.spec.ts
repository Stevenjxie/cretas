import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  aggregateFinishedGoodsOptions,
  aggregateMaterialInventoryOptions,
  applySelectedOption,
  optionsForItemType,
  TRANSFER_TYPE_OPTIONS,
  toTransferItemPayload,
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
