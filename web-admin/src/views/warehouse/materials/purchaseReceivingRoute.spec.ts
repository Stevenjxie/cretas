import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('统一仓储待收货入口', () => {
  const detail = source('src/views/procurement/orders/detail.vue');
  const materials = source('src/views/warehouse/materials/list.vue');
  const panel = source('src/views/warehouse/materials/PendingPurchaseReceivingPanel.vue');
  const dropZone = source('src/components/attachment/AttachmentDropZone.vue');
  const receiveApi = source('src/api/purchaseReceive.ts');
  const deepE2e = source('scripts/rn-closed-loop-deep-e2e.mjs');
  const router = source('src/router/index.ts');
  const menu = source('src/components/layout/menuConfig.ts');

  it('采购详情跳到统一仓储页并携带订单定位参数，导航本身零写', () => {
    expect(detail).toContain("path: '/warehouse/materials'");
    expect(detail).toContain('purchaseOrderId: orderId.value');
    expect(detail).toContain("orderNo: String(order.value?.orderNumber || '')");
    const goWarehouse = detail.slice(detail.indexOf('function goWarehouseReceive'), detail.indexOf('async function loadPriceComparison'));
    expect(goWarehouse).not.toContain('post(');
    expect(goWarehouse).not.toContain('/procurement/receives');
  });

  it('同一页面同时展示置顶待收货任务与正常库存批次', () => {
    expect(materials).toContain('<PendingPurchaseReceivingPanel');
    expect(materials).toContain('原料 / 物料入库与批次');
    expect(materials).toContain(':data="tableData"');
    expect(panel).toContain('待收货 / 待入库任务');
    expect(panel).toContain('pending-receive-row');
    expect(panel).toContain('type="danger" effect="dark"');
    expect(panel).toContain('计划数量');
    expect(panel).toContain('已收数量');
    expect(panel).toContain('待收数量');
    expect(panel).toContain('SALES_ORDER_CUSTOMER_SUPPLIED');
    expect(panel).not.toContain('PRODUCTION_PLAN');
    expect(panel).not.toContain('warehouse/transit-ledgers');
  });

  it('采购续办既有草稿，客供料按任务一次确认且不伪造第二套草稿', () => {
    expect(panel).toContain('getPendingPurchaseReceivingTasks');
    expect(panel).toContain('getPendingWarehouseReceivingTasks');
    expect(panel).toContain("row.activeReceiptId ? '继续收货' : '收货'");
    expect(panel).toContain('purchaseOrderId: task.purchaseOrderId');
    expect(panel).toContain('supplierId: task.supplierId');
    expect(panel).toContain('<AttachmentDropZone');
    expect(dropZone).toContain('松开即可上传');
    expect(panel).toContain('打印收货单');
    expect(panel).toContain('确认收货入库');
    expect(panel).toContain('来源、客户、物料、单位和目标仓库均由销售订单锁定');
    expect(panel).toContain(':max="Number(selectedCustomerLine.remainingReceivableQuantity)"');
    expect(panel).toContain('entity-type="CUSTOMER_SUPPLIED_RECEIPT"');
    expect(panel).toContain(':entity-id="selectedCustomerTask.taskId"');
    expect(panel).toContain('确认客户来料入库');
    expect(receiveApi).toContain('salesOrderNo: filters?.salesOrderNo');
    expect(receiveApi).toContain('/warehouse/receiving/tasks/${taskId}/receipts');
    expect(panel).not.toContain('createCustomerReceiptDraft');
    expect(panel).not.toContain('loadExistingCustomerReceipt');
    const customerConfirm = panel.slice(
      panel.indexOf('async function confirmCustomerReceipt'),
      panel.indexOf('function sourceLabel'),
    );
    expect(customerConfirm).not.toContain('/warehouse/receiving/receipts/');
    expect(materials).not.toContain('>入库登记</el-button>');
  });

  it('旧采购入库路由兼容重定向且菜单不再暴露割裂入口', () => {
    expect(router).toContain("redirect: (to) => ({ path: '/warehouse/materials'");
    expect(menu).toContain("title: '原料入库与批次'");
    expect(menu).not.toContain("{ path: '/procurement/receives', title: '采购入库'");
  });

  it('采购任务查询错误不会被吞掉后伪装成空待办', () => {
    expect(panel).toContain('await getPendingPurchaseReceivingTasks');
    expect(panel).not.toContain('Promise.allSettled');
  });

  it('新页面只调用仓储命名空间，不再使用旧采购收货 API', () => {
    expect(receiveApi).toContain('/warehouse/receiving/tasks');
    expect(receiveApi).toContain('/warehouse/receiving/default-warehouse');
    expect(panel).toContain('/warehouse/receiving/receipts');
    expect(panel).not.toContain('/purchase/receives');
    expect(detail).not.toContain('/purchase/receives');
    expect(deepE2e).toContain('/warehouse/receiving/receipts');
    expect(deepE2e).not.toContain('/purchase/receives');
  });
});
