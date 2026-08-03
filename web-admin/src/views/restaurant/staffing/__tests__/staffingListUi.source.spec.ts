import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/restaurant/staffing/index.vue'),
  'utf8',
);

describe('预测排班全门店明细 UI 契约', () => {
  it('只把分页后的门店明细交给表格，并限制内部滚动高度', () => {
    expect(source).toContain(':data="pagedSummaryRows"');
    expect(source).toContain(':max-height="560"');
    expect(source).not.toContain(':data="dashboard?.summaryRows ?? []"');
  });

  it('提供可访问的门店、时段、缺口和排序控件', () => {
    expect(source).toContain('aria-label="搜索或选择门店"');
    expect(source).toContain('aria-label="筛选营业时段"');
    expect(source).toContain('aria-label="筛选人力状态"');
    expect(source).toContain('aria-label="门店明细排序"');
    expect(source).toContain('仅看缺人');
    expect(source).toContain('低置信度优先');
  });

  it('分页只允许受控页大小，并保留班次明细入口', () => {
    expect(source).toContain(':page-sizes="[10, 20, 50]"');
    expect(source).toContain('v-model:current-page="currentPage"');
    expect(source).toContain('@click="openDetail(row)"');
  });
});
