import { describe, expect, it } from 'vitest';
import { canonicalUnitCode, canonicalUnitCodeKeepingCount, displayUnit, sameUnit } from '../unitPricing';

/**
 * 只 / 个 / 件 不能在销售链路上被合并成同一个字。
 *
 * 🔴 2026-08-13 真机 E2E 抓到: 销售订单选中物料 YL060 温氏黄油鸡(物料字典单位「只」)后,
 * 订单行显示「件」; BC001 吸塑盒(单位「个」)也显示「件」。
 *
 * 根因不在 displayUnit —— 它有 RAW_COUNT_LABELS, 传「只」会原样返回。
 * 坏在**更早**: 选品时 canonicalUnitCode('只') → 'pcs' 就把原标签抹掉了,
 * 显示层再想还原也没有了。
 *
 * 后果: 仓管在发货单上看到「2 件黄油鸡」而不是「2 只」。数量算术不受影响,
 * 坏的是**人读到的东西** —— 与同文件 #1672 / #2097 修过两次的是同一类缺陷。
 *
 * ⚠️ 名单(DISTINCT_COUNT_LABELS)早就存在, 只是**只接在 sameUnit 上**,
 * 没接到落库/展示这条路 —— 「规则写对了但没接上」比没写更难发现。
 */
describe('计件单位 只/个 不被并进 pcs', () => {
  it('普通 canonicalUnitCode 确实会合并 —— 这是缺陷的来源, 钉住它的行为', () => {
    expect(canonicalUnitCode('只')).toBe('pcs');
    expect(canonicalUnitCode('个')).toBe('pcs');
  });

  it('KeepingCount 版本保留 只/个', () => {
    expect(canonicalUnitCodeKeepingCount('只')).toBe('只');
    expect(canonicalUnitCodeKeepingCount('个')).toBe('个');
  });

  it('「件」仍然归一到 pcs —— 它就是 pcs 的中文名, 不在例外名单里', () => {
    expect(canonicalUnitCodeKeepingCount('件')).toBe('pcs');
  });

  it('其它单位行为不变(不能顺手改坏)', () => {
    expect(canonicalUnitCodeKeepingCount('kg')).toBe(canonicalUnitCode('kg'));
    expect(canonicalUnitCodeKeepingCount('公斤')).toBe(canonicalUnitCode('公斤'));
    expect(canonicalUnitCodeKeepingCount('箱')).toBe(canonicalUnitCode('箱'));
    expect(canonicalUnitCodeKeepingCount('')).toBe('');
  });

  it('端到端: 存什么就显示什么 —— 只→只, 个→个', () => {
    expect(displayUnit(canonicalUnitCodeKeepingCount('只'))).toBe('只');
    expect(displayUnit(canonicalUnitCodeKeepingCount('个'))).toBe('个');
    // 对照: 走合并版本就会全变成「件」, 那正是缺陷现场
    expect(displayUnit(canonicalUnitCode('只'))).toBe('件');
  });

  it('一只 ≠ 一个 ≠ 一件(sameUnit 与本归一同源, 不再各写一份)', () => {
    expect(sameUnit('只', '个')).toBe(false);
    expect(sameUnit('只', '件')).toBe(false);
    expect(sameUnit('件', 'pcs')).toBe(true);
    expect(sameUnit('kg', '公斤')).toBe(true);
  });
});
