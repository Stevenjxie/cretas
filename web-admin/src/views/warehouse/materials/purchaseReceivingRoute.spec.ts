import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { resolveReceivingRouteFilters } from './purchaseReceivingFilters';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('统一仓储待收货入口', () => {
  const detail = source('src/views/procurement/orders/detail.vue');
  const materials = source('src/views/warehouse/materials/list.vue');
  const panel = source('src/views/warehouse/materials/PendingPurchaseReceivingPanel.vue');
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
    expect(panel).toContain('getPendingCustomerSuppliedReceivingTasks');
    expect(panel).toContain("source: 'CUSTOMER_SUPPLIED'");
    expect(panel).toContain('客户来料待收货');
    expect(panel).toContain('客户所有（仅限该客户/订单）');
  });

  it('只能从来源任务创建受约束收货单，并可续办已有草稿', () => {
    expect(panel).toContain('getPendingPurchaseReceivingTasks');
    expect(panel).toContain("row.purchase.activeReceiptId ? '继续收货' : '收货'");
    expect(panel).toContain('purchaseOrderId: task.purchaseOrderId');
    expect(panel).toContain('supplierId: task.supplierId');
    expect(panel).toContain('拍照 / 上传供货凭证');
    expect(panel).toContain('打印收货单');
    expect(panel).toContain('确认收货入库');
    expect(materials).not.toContain('>入库登记</el-button>');
  });

  it('旧采购入库路由兼容重定向且菜单不再暴露割裂入口', () => {
    expect(router).toContain("redirect: (to) => ({ path: '/warehouse/materials'");
    expect(menu).toContain("title: '原料入库与批次'");
    expect(menu).not.toContain("{ path: '/procurement/receives', title: '采购入库'");
  });

  it('客户来料只能由仓储页面确认，凭证、幂等键和客户所有权提示齐全', () => {
    expect(panel).toContain('CUSTOMER_SUPPLIED_RECEIPT');
    expect(panel).toContain('拍照 / 上传客户送货凭证');
    expect(panel).toContain('newIdempotencyKey()');
    expect(panel).toContain('confirmCustomerSuppliedReceipt');
    expect(panel).toContain('确认客户来料收货');
    expect(panel).toContain('本次实收必须大于 0，且不能超过待收');
  });

  it('销售客供料路由不会把销售单号误当采购单号', () => {
    expect(resolveReceivingRouteFilters({
      sourceType: 'customer-supplied',
      salesOrderId: 'sales-1',
      salesOrderNo: 'SO-001',
    })).toMatchObject({
      purchaseOrderId: '',
      purchaseOrderNumber: '',
      salesOrderId: 'sales-1',
      salesOrderNumber: 'SO-001',
      restrictToPurchase: false,
      restrictToCustomerSupplied: true,
    });

    // 兼容旧链接：customer-supplied 下的 orderNo 仍按销售单号解析。
    expect(resolveReceivingRouteFilters({
      sourceType: 'customer-supplied',
      orderNo: 'SO-LEGACY',
    })).toMatchObject({
      purchaseOrderNumber: '',
      salesOrderNumber: 'SO-LEGACY',
      restrictToPurchase: false,
    });
  });

  it('采购单路由仍严格定位采购待收货任务', () => {
    expect(resolveReceivingRouteFilters({
      purchaseOrderId: 'po-1',
      orderNo: 'PO-001',
    })).toMatchObject({
      purchaseOrderId: 'po-1',
      purchaseOrderNumber: 'PO-001',
      salesOrderId: '',
      salesOrderNumber: '',
      restrictToPurchase: true,
      restrictToCustomerSupplied: false,
    });
  });
});
