import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('无订单入库申请与实际入库严格分域', () => {
  const materials = source('src/views/warehouse/materials/list.vue');
  const application = source('src/views/warehouse/unordered-inbound-applications/index.vue');
  const receivingPanel = source('src/views/warehouse/materials/PendingPurchaseReceivingPanel.vue');
  const router = source('src/router/index.ts');
  const menu = source('src/components/layout/menuConfig.ts');
  const api = source('src/api/customerMaterialArrival.ts');

  it('仓储侧边栏有独立申请审批页，旧地址仅做兼容跳转', () => {
    expect(existsSync(resolve(process.cwd(), 'src/views/warehouse/unordered-inbound-applications/index.vue'))).toBe(true);
    expect(menu).toContain("path: '/warehouse/unordered-inbound-applications'");
    expect(menu).toContain("title: '无订单入库申请'");
    expect(router).toContain("path: 'unordered-inbound-applications'");
    expect(router).toContain("redirect: '/warehouse/unordered-inbound-applications'");
  });

  it('原料入库页不再内嵌申请表，只保留入库任务与批次', () => {
    expect(materials).not.toContain('UnorderedInboundNoticePanel');
    expect(materials).not.toContain("permissionStore.canWrite('operations')");
    expect(materials).toContain('PendingPurchaseReceivingPanel');
    expect(materials).toContain(':can-write="canWrite"');
  });

  it('申请页只显示申请、审批、驳回、撤回与任务交接', () => {
    expect(application).toContain("'PENDING_APPROVAL'");
    expect(application).toContain("'REJECTED'");
    expect(application).toContain('提交审批');
    expect(application).toContain('确认通过并交接任务');
    expect(application).toContain('rejectReasonOptions');
    expect(application).toContain("{ value: 'OTHER', label: '其他' }");
    expect(application).toContain('查看关联任务');
    expect(application).not.toContain('createCustomerMaterialArrivalReceipt');
    expect(application).not.toContain('receivedQuantity');
    expect(application).not.toContain('materialTypeId');
    expect(application).not.toContain('warehouseId');
  });

  it('审批通过才跳转为精确的无订单入库任务', () => {
    expect(api).toContain('/approve`');
    expect(api).toContain('/reject`');
    expect(application).toContain("sourceType: 'CUSTOMER_MATERIAL_ARRIVAL'");
    expect(application).toContain('arrivalNoticeId: row.id');
    expect(receivingPanel).toContain('arrivalNoticeId: exactArrivalNoticeId.value || undefined');
    expect(receivingPanel).toContain('task.sourceId !== exactArrivalNoticeId.value');
  });

  it('申请原因和库存归属映射保持不变', () => {
    expect(application).toContain('value="CUSTOMER_MATERIAL"');
    expect(application).toContain('value="GIFT"');
    expect(application).toContain('value="OTHER"');
    expect(application).toContain('CUSTOMER_MATERIAL');
    expect(application).toContain('客户来料必须选择归属客户');
    expect(application).toContain('客户所有：只能用于所选客户');
    expect(application).toContain('公司所有：进入本厂普通库存');
  });
});
