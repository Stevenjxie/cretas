import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const listSource = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');
const detailSource = readFileSync(resolve(__dirname, '..', 'detail.vue'), 'utf8');

describe('purchase order supplier and execution boundaries', () => {
  it('selects only the current supplier relation and its purchase specifications', () => {
    expect(listSource).toContain('listSupplierMaterials(factoryId.value, form.value.supplierId)');
    expect(listSource).toContain('listSupplierPurchaseSpecs(factoryId.value, form.value.supplierId, relationId)');
    expect(listSource).toContain('v-model="item.supplierMaterialId"');
    expect(listSource).toContain('v-model="item.purchasePackagingSpecId"');
    expect(listSource).toContain('item.unit = unit');
    expect(listSource).toContain('quantity * Number(spec.factor)');
    expect(listSource).not.toContain('getUnitOptionsForItem(item)');
    expect(listSource).not.toContain('recalcBoxQuantity(item)');
  });

  it('creates the order once and retries only queued attachments', () => {
    expect(listSource).toContain('createdOrderId.value = savedOrderId');
    expect(listSource).toContain('uploadQueued(savedOrderId)');
    expect(listSource).toContain('采购订单已创建一次，但部分附件上传失败');
    expect(listSource).toContain('uploadQueued(createdOrderId.value)');
  });

  it('keeps warehouse receipt and finance payment mutations out of procurement detail', () => {
    expect(detailSource).toContain('前往仓储收货任务');
    expect(detailSource).toContain('采购履约与财务状态（只读）');
    expect(detailSource).toContain("path: '/finance/ar-ap'");
    expect(detailSource).not.toContain('申请付款</el-button>');
    expect(detailSource).not.toContain('/confirm-receive');
    expect(detailSource).not.toContain('handleCreateReceive');
  });
});
