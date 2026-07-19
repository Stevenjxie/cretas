import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('legacy shipment write freeze', () => {
  const sales = source('src/views/sales/shipments/list.vue');
  const warehouse = source('src/views/warehouse/shipments/list.vue');

  it('removes every web mutation of the legacy shipment endpoint', () => {
    for (const view of [sales, warehouse]) {
      expect(view).not.toMatch(/\b(?:post|put|del|delete)\s*\(\s*`\/\$\{factoryId\.value\}\/shipments/);
    }
  });

  it('marks legacy records read-only and routes new work through sales delivery confirmation', () => {
    expect(sales).toContain('历史手工出货记录');
    expect(warehouse).toContain('历史出货记录 (只读)');
    expect(warehouse).toContain('/warehouse/deliveries/${confirmingDelivery.value.id}/confirm');
  });
});
