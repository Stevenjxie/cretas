import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('legacy shipment write freeze', () => {
  const sales = source('src/views/sales/shipments/list.vue');
  const warehouse = source('src/views/warehouse/shipments/list.vue');
  const salesOrderDetail = source('src/views/sales/orders/detail.vue');

  it('removes every web mutation of the legacy shipment endpoint', () => {
    for (const view of [sales, warehouse]) {
      expect(view).not.toMatch(/\b(?:post|put|del|delete)\s*\(\s*`\/\$\{factoryId\.value\}\/shipments/);
    }
  });

  it('marks legacy records read-only and routes new work through sales delivery confirmation', () => {
    expect(sales).toContain('历史手工出货记录');
    expect(warehouse).toContain('历史出货记录 (只读)');
    expect(warehouse).toContain('/warehouse/deliveries/${confirmingDelivery.value.id}/confirm');
    expect(warehouse).toContain('确认并出库');
    expect(warehouse).toContain('客户已有库存会自动匹配本销售订单预留的精确批次');
  });

  it('keeps inventory deduction out of the sales order page', () => {
    expect(salesOrderDetail).not.toContain('/sales/deliveries/${deliveryId}/ship');
    expect(salesOrderDetail).not.toContain('function handleShip(');
    expect(salesOrderDetail).toContain("router.push('/warehouse/shipments')");
  });
});
