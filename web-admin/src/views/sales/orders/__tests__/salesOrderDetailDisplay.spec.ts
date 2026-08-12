import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const detailSource = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');
const listSource = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');
const profitSource = readFileSync(resolve(import.meta.dirname, '../profit-detail.vue'), 'utf8');

describe('sales order detail display-unit contract', () => {
  it('renders canonical order and delivery units through displayUnit', () => {
    expect(detailSource).toContain('{{ displayUnit(row.unit) }}');
    expect(detailSource).toContain('{{ item.deliveredQuantity }}{{ displayUnit(item.unit) }}');
    expect(detailSource).toContain('{{ row.availableQuantity }}{{ displayUnit(row.unit || item.unit) }}');
    expect(detailSource).not.toContain('<el-table-column prop="unit" label="单位"');
  });

  it('keeps source-warehouse payload canonical while rendering the configured warehouse name', () => {
    expect(detailSource).toContain('sourceWarehouseCode: it.sourceWarehouseCode ||');
    expect(detailSource).toContain('sourceWarehouseLabel(row.sourceWarehouseCode)');
  });

  it('covers subsequent quick-delivery and profit displays without changing payload values', () => {
    expect(listSource).toContain('{{ displayUnit(item.unit) }}');
    expect(profitSource).toContain('{{ displayUnit(row.unit) }}');
    // 契约是「payload 存规范化单位码, 界面用 displayUnit 翻译」, 不是「必须叫这个函数名」。
    // 2026-08-13: handleEdit 改用 canonicalUnitCodeKeepingCount —— 它仍然规范化,
    // 只是把 只/个 排除在折叠之外(普通版会把两者都并成 pcs → 显示回「件」,
    // 而 handleEdit 是【打开已有订单】的路径, 那等于静默改写已落库的单位)。
    // 断言放宽到两个版本任一, 但仍然要求 unit 是经过规范化的 —— 裸 item.unit 照样红。
    expect(listSource).toMatch(/unit: canonicalUnitCode(KeepingCount)?\(item\.unit/);
  });

  it('does not expose procurement semantics as a sales-order row action', () => {
    expect(listSource).not.toContain('>开始采购</el-button>');
    expect(listSource).not.toContain('<StartPurchaseDialog');
  });

  it('shows actionable shipment badge, localized audit time and explicit historical tracking gaps', () => {
    expect(detailSource).toContain('actionableDeliveries');
    expect(detailSource).toContain('neutral-record-count');
    expect(detailSource).toContain(':timestamp="formatBusinessDateTime(node.time)"');
    expect(detailSource).toContain("'系统自动审批'");
    expect(detailSource).toContain('未填写（历史数据）');
    expect(detailSource).toContain('shipmentForm.deliveryMethod');
  });
});
