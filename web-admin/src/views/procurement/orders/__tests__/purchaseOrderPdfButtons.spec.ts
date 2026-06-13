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
    expect(source).toContain('v-if="canViewPrice"');
    expect(source).toContain('params: { external: externalVersion }');
  });

  it('list shortcut always downloads the external supplier PDF', () => {
    const source = readView('list.vue');

    expect(source).toContain('params: { external: true }');
    expect(source).toContain('handleDownloadPdf(row)">对外供货单</el-button>');
  });
});
