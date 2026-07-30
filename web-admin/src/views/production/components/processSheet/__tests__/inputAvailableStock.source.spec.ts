import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '..', 'ProcessDataTable.vue'), 'utf8');

/**
 * 客户 2026-07-30 反馈 (刘山门 / F006 报工):
 *  - 「生产仓有货, 报工却说可用 0只」—— 填完点「正式报工」才被后端告知
 *    「需要 1只, 可用 0只, 缺少 1只」(ProductionStockShortageException)
 *  - 「都选用了, 本次投入原料就不用下拉了」「替代料就是选用的」
 *
 * 原料投入端口原本**完全没有**库存边界: upstreamWarning() 只覆盖 isSingleSource /
 * isMultiSource, rowCompletenessReason 在 usesAutoMaterialTotals 分支只检查「填了没」。
 * 防呆 Rule 1 要求预先显示边界, 所以把可用量摆进录入行并在超领时拦住保存。
 */
describe('原料投入行的可用库存 (客户 2026-07-30)', () => {
  it('renders the available stock in BOTH card and table templates (两套模板早已漂移过)', () => {
    const occurrences = source.match(/data-testid="input-available-stock"/g) || [];
    expect(occurrences.length).toBe(2);
  });

  it('shows the fixed material name instead of a redundant dropdown, in BOTH templates', () => {
    const fixed = source.match(/data-testid="bom-authorized-material-fixed"/g) || [];
    expect(fixed.length).toBe(2);
    // 旧的每行物料下拉已移除 (选用勾选已经表达了主料/替代料的取舍)
    expect(source).not.toContain('data-testid="bom-authorized-material-select"');
  });

  it('sums availability in the PORT unit and never adds up incomparable units', () => {
    expect(source).toContain('function inputStock');
    // 必须走 convertQuantityToUnit (质量可换算, 计数/包装按字面) —— 不能裸相加
    expect(source).toContain('convertQuantityToUnit(qty, batchUnit, unit)');
    // 回归防线: v1 曾是对全部同物料批次无视单位的 reduce 求和
    expect(source).not.toMatch(/\.reduce\(\(sum, batch\) => sum \+ rawBatchAvailable\(batch\), 0\)/);
  });

  it('normalises BOTH sides the same way before comparing units', () => {
    // item.unit 来自 workflowPortDisplayUnit(port) —— 已是显示形式 (pcs → 件)。
    // 拿批次原始 quantityUnit 去字面比会得到「可用 0件 · 另有 12件 未计入」这种自相矛盾文案。
    expect(source).toContain('const batchUnit = displayProcessUnit(rawUnit) || rawUnit;');
  });

  it('discloses same-material batches whose unit cannot be converted, instead of dropping them silently', () => {
    expect(source).toContain('incomparable');
    expect(source).toContain('单位不同, 未计入');
  });

  it('shows nothing (and blocks nothing) while the batch list is still loading', () => {
    // rawBatchOptions 初值 [] 且异步加载 → 首帧会闪「可用 0只」, 正是要消除的误导信息;
    // 同理加载期不得拦保存。
    expect(source).toMatch(/if \(rawBatchLoading\.value\) return '';/);
    expect(source).toMatch(/if \(!item\.selected \|\| rawBatchLoading\.value\) return false;/);
  });

  it('stands down from blocking when availability is only a lower bound', () => {
    // 有换不了的单位 → available 只是下限, 拿它拦截就是假阳性 (本文件已因缓存过期踩过一次)
    expect(source).toMatch(/if \(!stock\.unit \|\| stock\.incomparable\.length\) return false;/);
  });

  it('warns without ever disabling the save (空批次列表 ≠ 真的没货)', () => {
    // 硬闸的失败模式是「彻底提交不了」: loadRawBatches 在取不到仓库列表、或 workflow
    // 原料类型客户端过滤对不上时都会静默置 []。对仓管员来说那比「后端告诉你缺料」更糟,
    // 而且本文件已因缓存过期造成过一次假阳性拦截。
    expect(source).toContain('超领**刻意只提示不阻断**');
    // rowCompletenessReason 里不得出现超领分支 (它的返回值会 disable 保存/正式报工)
    expect(source).not.toContain('inputExceedsAvailable(item));');
    expect(source).not.toContain('超出可领用库存');
    // 只标红, 不禁用
    expect(source).toContain("'sp-in-stock-over': inputExceedsAvailable(item)");
  });

  it('keeps 成品工序投入量 advisory too — same ruling, same reason', () => {
    expect(source).toContain('function finishedInputOverAvailableHint');
    expect(source).toContain('账实差异由盘点纠正');
  });
});
