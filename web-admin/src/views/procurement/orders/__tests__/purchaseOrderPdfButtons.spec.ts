import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const viewDir = resolve(__dirname, '..');

function readView(name: string): string {
  return readFileSync(resolve(viewDir, name), 'utf8');
}

describe('procurement order PDF buttons', () => {
  it('detail page exposes explicit external and internal PDF actions', () => {
    const source = readView('detail.vue');

    expect(source).toContain('handleDownloadPdf(true)');
    expect(source).toContain('handleDownloadPdf(false)');
    expect(source).toContain('外发供应商PDF');
    expect(source).toContain('内部打印');
    expect(source).toContain('v-if="canViewPrice"');
    expect(source).toContain('downloadPurchaseOrderPdf');
    expect(source).toContain('external: externalVersion');
    expect(source).toContain('message,');
  });

  it('list shortcut always downloads the external supplier PDF', () => {
    const source = readView('list.vue');

    expect(source).toContain('downloadPurchaseOrderPdf');
    expect(source).toContain('external: true');
    expect(source).toContain('handleDownloadPdf(row)">外发供应商PDF</el-button>');
    expect(source).toContain('ElMessage.error(message)');
  });
});
