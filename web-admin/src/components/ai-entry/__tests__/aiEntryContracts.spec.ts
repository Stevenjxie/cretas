import { describe, expect, it } from 'vitest';
import { PRODUCT_CONFIG, PRODUCTION_PLAN_CONFIG } from '../types';

describe('AI entry contracts', () => {
  it('supports semi-finished products without treating raw materials as SKU products', () => {
    expect(PRODUCT_CONFIG.systemPrompt).toContain('SEMI_FINISHED(半成品)');
    expect(PRODUCT_CONFIG.systemPrompt).toContain('原料类型字典');
  });

  it('keeps production quantity unit and removes the stale fixed date example', () => {
    expect(PRODUCTION_PLAN_CONFIG.fields).toContainEqual({
      key: 'quantityUnit',
      label: '数量单位',
      required: true,
    });
    expect(PRODUCTION_PLAN_CONFIG.systemPrompt).toContain('quantityUnit="kg"');
    expect(PRODUCTION_PLAN_CONFIG.systemPrompt).not.toContain('2026-03-10');
    expect(PRODUCTION_PLAN_CONFIG.systemPrompt).toContain('必须逐字保留用户输入的完整 SKU 名称');
    expect(PRODUCTION_PLAN_CONFIG.systemPrompt).toContain('400g、350g');
    expect(PRODUCTION_PLAN_CONFIG.systemPrompt).toContain('不要猜测或返回 productTypeId');
  });
});
