import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = (path: string): string =>
  readFileSync(resolve(process.cwd(), path), 'utf8');

describe('label QC training lifecycle UI contract', () => {
  const page = source('src/views/quality/label-qc/index.vue');
  const api = source('src/api/labelQc.ts');

  it('separates daily review, reviewed-data management, and archives', () => {
    expect(page).toContain('待人工审核');
    expect(page).toContain('已审核整理');
    expect(page).toContain('归档记录');
    expect(page).toContain('归档不删除，可随时恢复');
    expect(api).toContain('/archive');
    expect(api).toContain('/restore');
  });

  it('keeps backup export separate from approved training export', () => {
    expect(page).toContain('下载备份');
    expect(page).toContain('导出已批准训练集');
    expect(api).toContain('/backup');
    expect(api).toContain('/training-export');
  });

  it('gates explicit training approval behind the system permission', () => {
    expect(page).toContain("permissions.includes('system:read_write')");
    expect(page).toContain('批准训练');
    expect(page).toContain('拒绝训练');
    expect(page).toContain('此操作不会自动训练或发布模型');
    expect(api).toContain('/training-decision');
  });
});
