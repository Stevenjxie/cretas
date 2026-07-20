import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const styleSource = readFileSync(resolve(import.meta.dirname, '../style.css'), 'utf8');

describe('全局业务表格长文本可读性', () => {
  it('表格按内容自适应并允许业务名称完整换行，不再强制省略', () => {
    expect(styleSource).toContain('.el-table .el-table__body');
    expect(styleSource).toContain('table-layout: auto !important');
    expect(styleSource).toContain('white-space: normal !important');
    expect(styleSource).toContain('overflow-wrap: anywhere');
    expect(styleSource).toContain('td.semantic-text-column');
  });
});
