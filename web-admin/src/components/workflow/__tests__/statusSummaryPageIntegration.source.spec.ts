import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('page-level status summary integration', () => {
  it('removes static tutorial banners from the reported sales and production pages', () => {
    const returns = source('src/views/sales/returns/list.vue');
    const plans = source('src/views/production/plans/list.vue');

    expect(returns).not.toContain('如何创建退货单');
    expect(plans).not.toContain('生产计划操作指引');
    expect(plans).not.toContain('<ConceptDisambiguationAlert');
    expect(plans).toContain('title="生产计划状态"');
  });

  it('uses the existing inbound tabs as the only warehouse receiving status navigation', () => {
    const materials = source('src/views/warehouse/materials/list.vue');

    expect(materials).not.toContain('source-only-hint');
    expect(materials).not.toContain('receiving-workflow-overview');
    expect(materials).not.toContain('workflow-circle');
    expect(materials).not.toContain('<ConceptDisambiguationAlert');
    expect(materials).toContain('`待收货 ${receivingCounts.WAITING_RECEIVE}`');
    expect(materials).toContain('`收货中 ${receivingCounts.RECEIVING}`');
    expect(materials).toContain('`部分入库 ${receivingCounts.PARTIAL}`');
  });

  it('keeps inventory status inside a compact status summary without a prose banner', () => {
    const inventory = source('src/views/warehouse/inventory/index.vue');

    expect(inventory).toContain('title="库存批次状态"');
    expect(inventory).not.toContain('<ConceptDisambiguationAlert');
  });
});
