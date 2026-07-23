import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/sales/orders/list.vue'),
  'utf8',
);

describe('sales order ordinary create entry', () => {
  it('opens the governed ordinary form directly and keeps AI entry separate', () => {
    expect(source).toContain('@click="openCreateDialog"');
    expect(source).toContain('@click="aiEntryVisible = true"');
    expect(source).not.toContain('CreateModeSelector');
    expect(source).not.toContain('BatchCreateDialog');
    expect(source).not.toContain('QuickCreateDialog');
    expect(source).not.toContain('BomExpansionDialog');
    expect(source).not.toContain('选择录入方式');
  });

  it('renders the form before loading selectable reference data in parallel', () => {
    const start = source.indexOf('async function openCreateDialog()');
    const end = source.indexOf('\n}', source.indexOf('createOptionsLoading.value = false;', start));
    const body = source.slice(start, end);
    const visibleAt = body.indexOf('dialogVisible.value = true;');
    const loadAt = body.indexOf('void Promise.allSettled([');

    expect(visibleAt).toBeGreaterThanOrEqual(0);
    expect(loadAt).toBeGreaterThan(visibleAt);
    expect(body).toContain('loadCustomers()');
    expect(body).toContain('loadProducts()');
    expect(body).toContain('loadSalesEmployees()');
    expect(body).toContain('loadWarehouses()');
  });

  it('adds data-type appropriate sorting and categorical header filters to the order table', () => {
    expect(source).toContain('prop="orderNumber" label="订单编号" width="170" sortable');
    expect(source).toContain(':filters="processingModeFilters"');
    expect(source).toContain(':filters="materialSupplyModeFilters"');
    expect(source).toContain(':filters="invoiceStatusFilters"');
    expect(source).toContain(':filters="salesStatusFilters"');
    expect(source).toContain('prop="totalAmount"');
    expect(source).toContain('label="总金额"');
    expect(source).toContain('sortable');
  });

  it('preserves source warehouse through create, edit and payload submission', () => {
    expect(source).toContain('sourceWarehouseCode: getRememberedWarehouse()');
    expect(source).toContain('sourceWarehouseCode: String(item.sourceWarehouseCode || \'\')');
    expect(source).toContain('items: toOrderItemPayload(selectedItems)');
    expect(source).toContain('v-model="item.sourceWarehouseCode"');
  });
});
