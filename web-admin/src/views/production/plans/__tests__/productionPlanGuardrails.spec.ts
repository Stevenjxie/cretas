import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '../list.vue'), 'utf8');

describe('production plan operator guardrails', () => {
  it('does not invent kilograms when a production plan unit is missing', () => {
    expect(source).not.toContain("|| 'kg'");
    expect(source).toContain('单位未配置');
  });
  it('shows production loss evidence guidance in settlement', () => {
    expect(source).toContain('生产报损/损耗留证');
    expect(source).toContain('报损原因选择“生产损耗”');
    expect(source).toContain('拍照或附件留证');
  });

  it('shows persistent WIP available quantity and max boundary in settlement', () => {
    expect(source).toContain('当前可用');
    expect(source).toContain('本行最多领用');
    expect(source).toContain('超出可用量');
  });

  it('shows backfill time-window guidance on production plan entry', () => {
    expect(source).toContain('补录时效');
    expect(source).toContain('今天/昨天可补');
    expect(source).toContain('前天为极限');
    expect(source).toContain('大前天及更早禁止补录');
  });
});
