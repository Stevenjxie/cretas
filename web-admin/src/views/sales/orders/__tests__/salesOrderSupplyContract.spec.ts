import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  customerMaterialReceivingStatusLabel,
  materialSupplyModeLabel,
  newSalesOrderSupplyContract,
  processingModeLabel,
  suppliedMaterialsValidationError,
  supplyContractValidationError,
  warehouseReceivingRoute,
} from '../salesOrderSupplyContract';

const listSource = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');
const detailSource = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');

describe('sales order supply contract', () => {
  it('defaults a new order to explicit standard sale and factory supply', () => {
    expect(newSalesOrderSupplyContract()).toEqual({
      processingMode: 'STANDARD_SALE',
      materialSupplyMode: 'FACTORY_SUPPLIED',
    });
  });

  it('blocks invalid supply combinations and incomplete customer material rows', () => {
    expect(supplyContractValidationError({
      processingMode: 'STANDARD_SALE',
      materialSupplyMode: 'CUSTOMER_SUPPLIED',
    })).toContain('普通销售不能选择客户自带原料');
    const contract = { processingMode: 'TOLL_PROCESSING' as const, materialSupplyMode: 'CUSTOMER_SUPPLIED' as const };
    expect(suppliedMaterialsValidationError(contract, [])).toContain('至少添加一项');
    expect(suppliedMaterialsValidationError(contract, [{
      materialTypeId: 'm1', materialName: '原料A', expectedQuantity: 5, unit: 'kg',
      expectedArrivalAt: '2026-07-23', targetWarehouseId: 'w1',
    }])).toBeNull();
    expect(suppliedMaterialsValidationError(contract, [
      { materialTypeId: 'm1', materialName: '原料A', expectedQuantity: 5, unit: 'kg', expectedArrivalAt: '2026-07-23', targetWarehouseId: 'w1' },
      { materialTypeId: 'm1', materialName: '原料A', expectedQuantity: 2, unit: 'kg', expectedArrivalAt: '2026-07-24', targetWarehouseId: 'w1' },
    ])).toContain('不能重复添加');
  });

  it('uses Chinese labels and explicit legacy fallbacks', () => {
    expect(processingModeLabel('TOLL_PROCESSING')).toBe('代加工');
    expect(materialSupplyModeLabel('CUSTOMER_SUPPLIED')).toBe('客户自带原料');
    expect(processingModeLabel(null)).toBe('未设置（历史数据）');
    expect(customerMaterialReceivingStatusLabel('PARTIALLY_RECEIVED')).toBe('部分收货');
  });

  it('routes to the unified warehouse page without performing a mutation', () => {
    expect(warehouseReceivingRoute({ id: 'so-1', orderNumber: 'SO-001' })).toEqual({
      path: '/warehouse/materials',
      query: {
        view: 'receiving',
        sourceType: 'SALES_ORDER_CUSTOMER_SUPPLIED',
        salesOrderId: 'so-1',
        salesOrderNo: 'SO-001',
      },
    });
    const routeFunction = detailSource.slice(
      detailSource.indexOf('function goWarehouseReceiving'),
      detailSource.indexOf('const deliveryDialogVisible'),
    );
    expect(routeFunction).not.toContain('post(');
  });

  it('persists create and edit values and keeps customer receiving read-only in sales', () => {
    expect(listSource).toContain('v-model="form.processingMode"');
    expect(listSource).toContain('v-model="form.materialSupplyMode"');
    expect(listSource).toContain('suppliedMaterials: suppliedMaterialsPayload()');
    expect(listSource).toContain('value-format="YYYY-MM-DDTHH:mm:ss"');
    expect(listSource).toContain('expectedArrivalAt: row.expectedArrivalAt.length === 10');
    expect(listSource).toContain('Array.isArray(row.suppliedMaterials)');
    expect(listSource).toContain('const detailResponse = await get(`/${factoryId.value}/sales/orders/${orderId}`)');
    expect(detailSource).toContain('processingModeLabel(order.processingMode)');
    expect(detailSource).toContain('客户自带原料需求');
    expect(detailSource).toContain('前往仓储收货任务');
    expect(detailSource).not.toContain('customer-material-receipts');
    expect(detailSource).not.toContain('客供料入库');
  });
});
