import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '../ProcessDataTable.vue'), 'utf8');
const sheetSource = readFileSync(resolve(__dirname, '../ProcessSheet.vue'), 'utf8');
const outputTableSource = readFileSync(resolve(__dirname, '../ProcessOutputTable.vue'), 'utf8');

describe('process reporting information architecture', () => {
  it('keeps the reporting sequence and quantity/unit controls explicit', () => {
    const input = source.indexOf('<span><b>①</b> 投入</span>');
    const execution = source.indexOf('<span><b>②</b> 工序执行</span>');
    const output = source.indexOf('<ProcessOutputTable');
    const submit = source.indexOf('<strong><b>④</b> 确认提交</strong>');

    // 顺序仍是 ① 投入 → ② 工序执行(工序参数) → ③ 产出 → ④ 确认提交。
    // 变化在于: 客户实测反馈布局散, 开始/结束时间与人数已从「② 工序执行」下的独立一节
    // 并进每条产出行 —— 同一个产出品名不再在一张卡里出现三次。
    expect(input).toBeGreaterThan(-1);
    expect(execution).toBeGreaterThan(input);
    expect(output).toBeGreaterThan(execution);
    expect(submit).toBeGreaterThan(output);
    expect(source).toContain('开始、结束时间和人数随每条产出一起录入');
    expect(source).not.toContain('data-testid="output-start-time"');

    // 产出块只有一份实现, 卡片模式与表格模式都用它 —— 两边曾经漂移过 (表格模式漏了必填标识
    // 和跨单位出成率说明), 这条断言防止再分叉。
    expect(source.match(/<ProcessOutputTable/g)).toHaveLength(2);
    expect(source.match(/data-testid="process-output-table"/g)).toHaveLength(2);
    expect(source.match(/:views="outputViews\(row\)"/g)).toHaveLength(2);
    expect(source.match(/data-testid="stock-shortage-alert"/g)).toHaveLength(2);
    expect(source.match(/:presentation="row\.stockShortage"/g)).toHaveLength(2);
    const shortageBranch = source.slice(
      source.indexOf("code === 'PRODUCTION_STOCK_SHORTAGE'"),
      source.indexOf('// 并发双提交'),
    );
    expect(shortageBranch).toContain('presentStockShortage');
    expect(shortageBranch).not.toContain('ElMessage(');

    const startTime = outputTableSource.indexOf('data-testid="output-start-time"');
    const outputQuantity = outputTableSource.indexOf('data-testid="output-quantity"');
    const optional = outputTableSource.indexOf('按需填写：副产与成本分摊');
    expect(outputQuantity).toBeGreaterThan(-1);
    expect(startTime).toBeGreaterThan(outputQuantity);
    expect(optional).toBeGreaterThan(startTime);

    expect(outputTableSource).toContain('aria-label="产出数量与单位"');
    expect(outputTableSource).toContain('class="sp-inline-input"');
    expect(outputTableSource).toContain('class="sp-required"');
    expect(source).toContain('class="sp-submit-primary"');
    expect(source).toContain('class="sp-draft-action"');
    expect(source).toContain('固定 BOM 中的包材与工序调料');
    // 产出表自己横向滚动, 不把整页撑宽; 卡片其余部分仍在窄屏堆叠
    expect(outputTableSource).toContain('@media (max-width: 1366px)');
    expect(outputTableSource).toContain('overflow-x: auto');
    expect(source).toContain('@media (max-width: 720px)');
  });

  it('keeps report context separate from the task workspace and stacks safely on narrow screens', () => {
    expect(sheetSource).toContain('报工上下文');
    expect(sheetSource).toContain('class="process-flow-strip"');
    expect(sheetSource).toContain('物料 / 上游半成品');
    expect(sheetSource).toContain('填写实际产出并生成批次');
    expect(sheetSource).toContain('process-entry-workspace');
    expect(sheetSource).toContain('grid-template-columns: minmax(0, 1.7fr) minmax(300px, 0.8fr)');
    expect(sheetSource).toContain('@media (max-width: 1200px)');
  });

  it('uses table as the default business view while preserving the explicit card alternative', () => {
    expect(sheetSource).toContain("{ label: '表格', value: 'grid' }");
    expect(sheetSource).toContain("{ label: '卡片', value: 'card' }");
    expect(sheetSource).toContain('initialProcessSheetViewMode(savedView)');
  });
});
