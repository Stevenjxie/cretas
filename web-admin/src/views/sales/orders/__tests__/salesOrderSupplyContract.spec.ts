import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  customerMaterialReceivingStatusLabel,
  materialSupplyModeLabel,
  newSalesOrderSupplyContract,
  processingModeLabel,
  supplyContractValidationError,
  warehouseReceivingRoute,
} from '../salesOrderSupplyContract';

const listSource = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');
const detailSource = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');

describe('sales order supply contract', () => {
  it('new order defaults to an explicit standard-sale/factory-supplied contract', () => {
    expect(newSalesOrderSupplyContract()).toEqual({
      processingMode: 'STANDARD_SALE',
      materialSupplyMode: 'FACTORY_SUPPLIED',
    });
  });

  it('blocks the invalid standard-sale/customer-supplied combination', () => {
    expect(supplyContractValidationError({
      processingMode: 'STANDARD_SALE',
      materialSupplyMode: 'CUSTOMER_SUPPLIED',
    })).toContain('普通销售不能选择客户自带原料');
    expect(supplyContractValidationError({
      processingMode: 'TOLL_PROCESSING',
      materialSupplyMode: 'CUSTOMER_SUPPLIED',
    })).toBeNull();
  });

  it('uses business Chinese labels and explicitly identifies legacy blanks', () => {
    expect(processingModeLabel('TOLL_PROCESSING')).toBe('代加工');
    expect(materialSupplyModeLabel('CUSTOMER_SUPPLIED')).toBe('客户自带原料');
    expect(processingModeLabel(null)).toBe('未设置（历史数据）');
    expect(customerMaterialReceivingStatusLabel('PARTIALLY_RECEIVED')).toBe('部分收货');
    expect(customerMaterialReceivingStatusLabel(undefined)).toBe('请前往仓储查看');
  });

  it('routes customer material receiving to the unified warehouse page without mutation', () => {
    expect(warehouseReceivingRoute({ id: 'so-1', orderNumber: 'SO-001' })).toEqual({
      path: '/warehouse/materials',
      query: {
        view: 'receiving',
        sourceType: 'customer-supplied',
        salesOrderId: 'so-1',
        salesOrderNo: 'SO-001',
      },
    });
  });

  it('persists and reloads the contract in normal create/edit while keeping details read-only', () => {
    expect(listSource).toContain('v-model="form.processingMode"');
    expect(listSource).toContain('v-model="form.materialSupplyMode"');
    expect(listSource).toContain("processingMode: String(editableRow.processingMode || '')");
    expect(listSource).toContain("materialSupplyMode: String(editableRow.materialSupplyMode || '')");
    expect(listSource).toContain('...form.value');
    expect(detailSource).toContain('processingModeLabel(order.processingMode)');
    expect(detailSource).toContain('materialSupplyModeLabel(order.materialSupplyMode)');
    expect(detailSource).toContain('...supplyContract');
    expect(detailSource).toContain('前往仓储收货任务');
    expect(detailSource).not.toContain('customer-material-receipts');
    expect(detailSource).not.toContain('确认入库');
  });

  it('captures structured customer-supplied requirements and reloads the same rows on edit', () => {
    expect(listSource).toContain('客户自带原料需求');
    expect(listSource).toContain('v-model="row.materialTypeId"');
    expect(listSource).toContain('v-model="row.expectedQuantity"');
    expect(listSource).toContain('v-model="row.expectedArrivalAt"');
    expect(listSource).toContain('v-model="row.targetWarehouseId"');
    expect(listSource).toContain('suppliedMaterials: suppliedMaterialsPayload()');
    expect(listSource).toContain('editableRow.suppliedMaterials');
    expect(detailSource).toContain('客户自带原料需求');
    expect(detailSource).toContain('order.suppliedMaterials || []');
  });
});
