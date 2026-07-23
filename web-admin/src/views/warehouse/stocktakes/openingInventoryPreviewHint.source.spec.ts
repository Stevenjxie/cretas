import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/stocktakes/index.vue'),
  'utf8',
);

describe('opening inventory preview-before-confirm contract', () => {
  it('requires a preview before confirming and explains the missing preview', () => {
    expect(source).toContain('const bulkConfirmDisabledReason = computed');
    expect(source).toContain("if (!bulkPreview.value) return '请先预览比对，确认导入前需核对本次数据'");
    expect(source).toContain(':disabled="!!bulkConfirmDisabledReason"');
  });

  it('keeps the disabled reason discoverable by mouse and keyboard focus', () => {
    expect(source).toContain('<el-tooltip :content="bulkConfirmDisabledReason"');
    expect(source).toContain(':disabled="!bulkConfirmDisabledReason"');
    expect(source).toContain(':tabindex="bulkConfirmDisabledReason ? 0 : -1"');
    expect(source).toContain(':aria-label="bulkConfirmDisabledReason || undefined"');
  });

  it('invalidates a stale preview after the input source changes', () => {
    expect(source).toContain('openingRows,');
    expect(source).toContain('() => { bulkPreview.value = null; }');
    expect(source).toContain("'预览中没有可导入的匹配数据，请修正后重新预览比对'");
  });
});
