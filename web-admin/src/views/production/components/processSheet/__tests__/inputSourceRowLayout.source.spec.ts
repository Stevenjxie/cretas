import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '..', 'ProcessDataTable.vue'), 'utf8');
const inputTableSource = readFileSync(resolve(__dirname, '..', 'ProcessInputSourceTable.vue'), 'utf8');

/**
 * 客户 2026-07-30 (刘山门 / 六膳门 F006 报工) —— 半成品/成品投入改行式 + 唯一候选自动选中。
 *
 * 行为断言在 ProcessDataTable.inputSourceRows.spec.ts。这里只守**结构不变量**:
 * 「同一块 UI 在这个文件里只有一份实现, 且两套模板都用上了」。这个文件历史上因为
 * 卡片/表格两套模板漂移, 让默认视图(表格)漏掉过必填标识和整个新功能 —— 数出现次数是唯一
 * 能挡住再次分叉的断言。
 */
describe('半成品/成品投入的行式实现只有一份 (两套模板早已漂移过)', () => {
  it('多来源投入块由子组件统一实现, 卡片与表格各用一次', () => {
    const usages = source.match(/<ProcessInputSourceTable/g) || [];
    expect(usages.length).toBe(2);
    // 三份重复的 flex 行实现 (卡片 1 + 表格模式熟制/气调各 1) 已全部收进子组件
    expect(source).not.toContain('data-testid="upstream-source-line"');
    expect(source).not.toContain('同物料再加批次');
    expect(inputTableSource.match(/data-testid="upstream-source-line"/g)).toHaveLength(1);
  });

  it('唯一候选批次的只读展示在卡片和表格两套模板里都有', () => {
    // 单上游道的那一份 (子组件里多来源的那一份另算, 见下一条)
    const fixed = source.match(/data-testid="upstream-batch-fixed"/g) || [];
    expect(fixed.length).toBe(2);
    expect(inputTableSource.match(/data-testid="upstream-batch-fixed"/g)).toHaveLength(1);
  });

  it('去舌苔的重复分支已合并 —— 单上游只剩一份判据', () => {
    // 合并前: 卡片 2 份 + 表格 2 份 + 表头 2 份, 差别只有带 v-if 的 isQidiao 投入量块
    expect(source).not.toContain('isSingleSource && isQuSheTou');
    expect(source).not.toContain('isSingleSource && !isQuSheTou');
  });

  it('「是不是只有一条候选」不能拿带搜索过滤/截断的下拉列表来数', () => {
    // sfiOptionsDisplay / fgOptionsDisplay 带搜索词过滤且只取最近 30 条; 拿它们做判据,
    // 操作员在下拉里打个字就会被自动选中一个他并没有挑的批次。
    expect(source).toContain('function allUpstreamCandidates');
    expect(source).toContain('function allSingleUpstreamCandidates');
    expect(source).not.toMatch(/function all(Single)?UpstreamCandidates[\s\S]{0,400}OptionsDisplay/);
  });

  it('自动选中走的是操作员手点的同一条路径, 不另写一遍赋值', () => {
    // 绕开 onUpstreamSelect 就会丢掉成品混锅道「来源选择即结转可用量」的既有契约,
    // 结果是静默提交 inputQuantity=0。
    expect(source).toContain('if (sole) onUpstreamSelect(src, sole.value);');
    expect(source).toContain('if (sole) onSingleUpstreamSelect(row, sole.value);');
    // 库存还在加载时一律不选 (加载中途会误判成唯一候选)
    expect(source).toMatch(/function autoSelectSoleUpstreamBatches[\s\S]{0,200}if \(sfiLoading\.value \|\| fgLoading\.value\) return;/);
  });

  it('数量不自动带出 —— 自动的只有「选哪一批」', () => {
    expect(source).toContain('**数量一律由操作员实填**');
    expect(inputTableSource).toContain('数量**不自动带出**');
  });

  it('行式表格的每个控件都有无障碍名 (表头只做视觉对齐)', () => {
    expect(inputTableSource).toContain('aria-hidden="true"');
    expect(inputTableSource).toContain(':aria-label="`选用 ${view.materialName}`"');
    expect(inputTableSource).toContain(':aria-label="`${view.materialName} 投入数量`"');
    expect(inputTableSource).toContain(':aria-label="`${view.materialName} 来源批次`"');
    expect(inputTableSource).toContain('class="sp-required"');
    // 窄屏下投入表自己横向滚动, 不把整页撑宽 (与产出表同一处理)
    expect(inputTableSource).toContain('overflow-x: auto');
    expect(inputTableSource).toContain('@media (max-width: 1366px)');
  });

  it('成品工序投入量仍然只提示不阻断 (Steve 拍板: 账实差异由盘点纠正)', () => {
    // 行式改造不得顺手把 advisory 提示改成硬闸
    expect(source).toContain('function finishedInputOverAvailableHint');
    expect(source).toContain('账实差异由盘点纠正');
    expect(source).not.toContain('finishedInputOverAvailableHint(row)) return');
  });
});
