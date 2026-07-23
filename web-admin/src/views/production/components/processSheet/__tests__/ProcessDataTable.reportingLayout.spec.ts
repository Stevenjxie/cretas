import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '../ProcessDataTable.vue'), 'utf8');
const sheetSource = readFileSync(resolve(__dirname, '../ProcessSheet.vue'), 'utf8');

describe('process reporting information architecture', () => {
  it('keeps the reporting sequence and quantity/unit controls explicit', () => {
    const input = source.indexOf('<strong>投入明细</strong>');
    const execution = source.indexOf('<span>工序执行</span>');
    const output = source.indexOf('产出明细 — {{ row.multiOutputs.length }} 项');
    const optional = source.indexOf('按需填写：副产与成本分摊');

    expect(input).toBeGreaterThan(-1);
    expect(execution).toBeGreaterThan(input);
    expect(output).toBeGreaterThan(execution);
    expect(optional).toBeGreaterThan(output);
    expect(source).toContain('aria-label="产出数量与单位"');
    expect(source).toContain('class="sp-inline-input"');
    expect(source).toContain('@media (max-width: 1366px)');
  });

  it('keeps report context separate from the task workspace and stacks safely on narrow screens', () => {
    expect(sheetSource).toContain('报工上下文');
    expect(sheetSource).toContain('process-entry-workspace');
    expect(sheetSource).toContain('grid-template-columns: minmax(0, 1.7fr) minmax(300px, 0.8fr)');
    expect(sheetSource).toContain('@media (max-width: 1200px)');
  });
});
