import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '..', 'ProcessDataTable.vue'), 'utf8');

/**
 * 客户 2026-07-30 反馈 (成品工序「定量包装」):
 *  - 「投入的数量也没有办法去输入」
 *  - 上游批次下拉混进 22 个别的产品的半成品批次, 全部显示为「半成品: 半成品」
 *  - 选错批次 → 出成率 800% 且无任何提示
 *
 * Steve 拍板: 投入不一定等于上一道产出 (损耗/只用一部分/补料), 必须留给操作员填;
 * 以实填为准, 填错由盘点纠正 —— 所以超量只提示、不阻断。
 */
describe('finished-process input quantity (客户 2026-07-30)', () => {
  it('lets the operator type the actual feed quantity instead of only deriving it', () => {
    expect(source).toContain('finishedInputKg');
    expect(source).toContain('function effectiveFinishedInputKg');
    // 实填优先, 未填才回落到所选批次可用量
    expect(source).toMatch(/if \(row\.finishedInputKg != null && row\.finishedInputKg > 0\) return row\.finishedInputKg;/);
    expect(source).toContain('return resolvedFinishedInputKg(row);');
  });

  it('feeds the entered quantity into the submitted request, not the auto-derived one', () => {
    expect(source).toContain('const inputKg = effectiveFinishedInputKg(row);');
    expect(source).not.toContain('const inputKg = resolvedFinishedInputKg(row);');
    expect(source).toContain('if (isQidiao.value) return effectiveFinishedInputKg(row) ?? 0;');
  });

  it('warns without blocking when the entered quantity exceeds the batch availability', () => {
    expect(source).toContain('function finishedInputOverAvailableHint');
    expect(source).toContain('账实差异由盘点纠正');
    // 只提示: 不得把超量写进 formalSubmitBlockedReason 的阻断分支
    expect(source).not.toMatch(/return[^\n]*超过所选批次可用量[^\n]*;\s*\n\s*}\s*\n\s*\/\*\* Mirrors the inputQuantity/);
  });

  it('renders the input in BOTH card and table templates (两套模板早已漂移过)', () => {
    const occurrences = source.match(/data-testid="finished-input-kg"/g) || [];
    expect(occurrences.length).toBe(2);
  });

  it('restores the entered quantity when a saved draft is reopened', () => {
    expect(source).toContain('row.finishedInputKg = p.upstreamSources?.[0]?.feedQuantityKg ?? null;');
  });

  it('narrows the upstream batch list to SKUs the workflow port actually accepts', () => {
    expect(source).toContain('function sfiMatchesUpstreamPort');
    expect(source).toContain('.filter(sfiMatchesUpstreamPort)');
    expect(source).toContain('upstreamAllowedSkuIds');
  });

  it('never shows a nameless 半成品 option the operator cannot tell apart', () => {
    expect(source).toContain('未命名半成品(${item.intermediateBatchNo})');
    expect(source).not.toContain("item.productTypeName || item.processName || '半成品'");
  });
});
