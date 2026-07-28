import { describe, expect, it } from 'vitest';
import {
  PROCESS_TASK_CONFIG,
  PRODUCT_CONFIG,
  PRODUCTION_PLAN_CONFIG,
  PURCHASE_ORDER_CONFIG,
  SALES_ORDER_CONFIG,
  STOCKTAKING_CONFIG,
  WH_INBOUND_CONFIG,
  type AiEntryConfig,
} from '../types';

const ALL_CONFIGS: AiEntryConfig[] = [
  PRODUCTION_PLAN_CONFIG, PRODUCT_CONFIG, PURCHASE_ORDER_CONFIG, SALES_ORDER_CONFIG,
  STOCKTAKING_CONFIG, WH_INBOUND_CONFIG, PROCESS_TASK_CONFIG,
];

describe('AI entry contracts', () => {
  // 2026-07-28: prompt 已搬到后端 resources/ai/form-prompts/factory/{ENTITY}.md。
  // 「SKU 名逐字保留 / 禁止缩写」这些防呆规则的回归断言现在住在 Java 侧的
  // FormPromptRegistryTest —— 这里只守前端这半边的契约。
  it('配置里不再带 prompt —— 浏览器不发 systemPrompt', () => {
    for (const config of ALL_CONFIGS) {
      expect(config).not.toHaveProperty('systemPrompt');
    }
  });

  it('7 个实体的 entityType 与后端 prompt 资源文件名一一对应', () => {
    expect(ALL_CONFIGS.map((c) => c.entityType).sort()).toEqual([
      'PROCESS_TASK', 'PRODUCT', 'PRODUCTION_PLAN', 'PURCHASE_ORDER',
      'SALES_ORDER', 'STOCKTAKING', 'WH_INBOUND',
    ]);
  });

  it('每个实体都有必填字段 —— 缺项判定完全靠它，不采信模型自报', () => {
    for (const config of ALL_CONFIGS) {
      expect(config.fields.some((f) => f.required)).toBe(true);
    }
  });

  it('明细类实体的 items 声明为 array', () => {
    for (const config of [PURCHASE_ORDER_CONFIG, SALES_ORDER_CONFIG, WH_INBOUND_CONFIG]) {
      const items = config.fields.find((f) => f.key === 'items');
      expect(items?.type).toBe('array');
      expect(items?.required).toBe(true);
    }
  });

  it('生产计划保留 quantityUnit —— 数量和单位不能拆丢', () => {
    expect(PRODUCTION_PLAN_CONFIG.fields).toContainEqual({
      key: 'quantityUnit',
      label: '数量单位',
      required: true,
    });
  });
});
