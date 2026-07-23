import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '../ProcessDataTable.vue'), 'utf8');
const sheetSource = readFileSync(resolve(__dirname, '../ProcessSheet.vue'), 'utf8');

describe('process reporting information architecture', () => {
  it('keeps the reporting sequence and quantity/unit controls explicit', () => {
    const input = source.indexOf('<span><b>①</b> 投入</span>');
    const execution = source.indexOf('<span><b>②</b> 工序参数</span>');
    const output = source.indexOf('产出明细 — {{ row.multiOutputs.length }} 项');
    const submit = source.indexOf('<strong><b>④</b> 确认提交</strong>');
    const optional = source.indexOf('按需填写：副产与成本分摊');

    expect(input).toBeGreaterThan(-1);
    expect(execution).toBeGreaterThan(input);
    expect(output).toBeGreaterThan(execution);
    expect(optional).toBeGreaterThan(output);
    expect(submit).toBeGreaterThan(optional);
    expect(source).toContain('aria-label="产出数量与单位"');
    expect(source).toContain('class="sp-inline-input"');
    expect(source.indexOf('data-testid="output-quantity"')).toBeLessThan(source.indexOf('data-testid="output-start-time"'));
    expect(source).toContain('固定 BOM 中的包材与工序调料');
    expect(source).toContain('@media (max-width: 1366px)');
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
