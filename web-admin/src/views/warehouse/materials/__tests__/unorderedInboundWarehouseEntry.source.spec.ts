import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('无订单入库申请统一在仓储页内完成', () => {
  const materials = source('src/views/warehouse/materials/list.vue');
  const requestPanel = source('src/views/warehouse/materials/UnorderedInboundNoticePanel.vue');
  const receivingPanel = source('src/views/warehouse/materials/PendingPurchaseReceivingPanel.vue');
  const router = source('src/router/index.ts');
  const menu = source('src/components/layout/menuConfig.ts');
  const api = source('src/api/customerMaterialArrival.ts');

  it('不再增加独立运营菜单和页面，旧地址只做兼容跳转', () => {
    expect(menu).not.toContain("path: '/operations', title:");
    expect(menu).toContain("path: '/warehouse/materials', title: '原料 / 物料入库与批次'");
    expect(existsSync(resolve(process.cwd(), 'src/views/operations/customer-material-arrivals/index.vue'))).toBe(false);
    expect(router).toContain("path: 'operations/customer-material-arrivals'");
    expect(router).toContain("path: '/warehouse/materials'");
    expect(router).toContain("action: 'unordered-inbound'");
  });

  it('运营在仓储页创建申请，仓管仍只执行实际收货', () => {
    expect(materials).toContain("permissionStore.canWrite('operations')");
    expect(materials).toContain("permissionStore.canWrite('warehouse')");
    expect(materials).toContain('v-if="factoryId && canManageUnorderedInbound"');
    expect(materials).toContain(':can-write="canWrite"');
    expect(requestPanel).toContain('发起无订单入库');
    expect(requestPanel).toContain('发送给仓储');
    expect(requestPanel).not.toContain('实际原料');
    expect(requestPanel).not.toContain('实收数量');
    expect(api).toContain('/operations/customer-material-arrivals');
  });

  it('申请原因和库存归属固定映射，提交申请不会直接加库存', () => {
    expect(requestPanel).toContain('value="CUSTOMER_MATERIAL"');
    expect(requestPanel).toContain('value="GIFT"');
    expect(requestPanel).toContain('value="OTHER"');
    expect(requestPanel).toContain("form.reason === 'CUSTOMER_MATERIAL'");
    expect(requestPanel).toContain('客户来料必须选择归属客户');
    expect(requestPanel).toContain('客户所有：只能用于所选客户');
    expect(requestPanel).toContain('公司所有：进入本厂普通库存');
    expect(requestPanel).toContain('当前没有增加库存');
  });

  it('仓管确认时再次看到原因、客户、实物和最终所有权，且不出现来料质检', () => {
    expect(receivingPanel).toContain("CUSTOMER_MATERIAL: '客户来料'");
    expect(receivingPanel).toContain("GIFT: '赠予'");
    expect(receivingPanel).toContain("OTHER: '其他无订单入库'");
    expect(receivingPanel).toContain("? `客户所有（${task.customerName || '客户待核对'}，未绑定销售订单）`");
    expect(receivingPanel).toContain("'公司所有（本厂普通库存）'");
    expect(receivingPanel).toContain('确认后直接生成原料库存批次，不触发来料质检');
    expect(receivingPanel).not.toContain('来料质检结果');
  });
});
