import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { allocationEmptyStateDesc, allocationEmptyStateTitle } from '../allocationEmptyState';

const detailSource = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');

/**
 * 客户 2026-07-31 反馈 (Sheet 第 40 行): 「点击分配批次, 提示的仓库为英文代号」。
 *
 * 这段空态文案是唯一告诉仓管「货其实在别的仓」的地方, 拼错对象就等于没说。
 * 断言全部落在**渲染出来的那句话**上, 不是落在实现细节。
 */

// 客户在仓库配置里自己命名的仓 —— DB name 是权威 (见 utils/warehouse.ts 2026-07-02 注释)
const names: Record<string, string> = {
  'WH-FG': '成品仓',
  'WH-LOG': '外仓',
  'WH-WKS': '线边仓',
};
const resolveName = (code: string | null | undefined): string => (
  code ? (names[code] ?? code) : '-'
);

describe('分配批次空态文案不能把仓库 code 甩给用户', () => {
  it('货在别的仓时, 有货仓与来源仓都渲染成仓库名', () => {
    const desc = allocationEmptyStateDesc(['WH-FG'], 'WH-LOG', resolveName);
    expect(desc).toContain('成品仓');
    expect(desc).toContain('外仓');
    expect(desc).not.toContain('WH-FG');
    expect(desc).not.toContain('WH-LOG');
  });

  it('多个有货仓逐个换名, 不是只换第一个', () => {
    const desc = allocationEmptyStateDesc(['WH-FG', 'WH-WKS'], 'WH-LOG', resolveName);
    expect(desc).toContain('成品仓');
    expect(desc).toContain('线边仓');
    expect(desc).not.toMatch(/WH-/);
  });

  it('发货行未声明来源仓时说清楚是「未声明」, 不渲染空的「来源仓为「」」', () => {
    const desc = allocationEmptyStateDesc(['WH-FG'], '', resolveName);
    expect(desc).toContain('未声明来源仓');
    expect(desc).not.toContain('「」');
  });

  it('全厂无货是另一条文案, 不能误导成「换个仓就有」', () => {
    const desc = allocationEmptyStateDesc([], 'WH-LOG', resolveName);
    expect(desc).toContain('无可发货成品库存');
    expect(desc).not.toContain('请改选来源仓');
  });

  it('仓库列表还没加载出来时回退显示 code —— 宁可露 code 也不能显示空白或「-」', () => {
    // resolveName 拿不到名字时按 warehouseNameByCode 的约定回退到 code 本身
    const desc = allocationEmptyStateDesc(['WH-NEW'], 'WH-LOG', resolveName);
    expect(desc).toContain('WH-NEW');
    expect(desc).not.toContain('「-」');
  });

  it('标题区分「来源仓没有」与「真的没有」', () => {
    expect(allocationEmptyStateTitle(['WH-FG'])).toBe('当前来源仓无可用成品批次');
    expect(allocationEmptyStateTitle([])).toBe('没有可用成品批次');
  });

  it('detail.vue 只经 helper 出这段话, 不再自己拼 code', () => {
    // 这两句是回归闸: 只要有人把文案拼回 .vue 里, 就会绕开上面全部断言。
    expect(detailSource).toContain('allocationEmptyStateDesc(');
    expect(detailSource).toContain('sourceWarehouseLabel,');
    expect(detailSource).not.toContain('item.stockWarehouses.join');
    expect(detailSource).not.toContain('来源仓为「${item.sourceWarehouseCode}」');
  });
});
