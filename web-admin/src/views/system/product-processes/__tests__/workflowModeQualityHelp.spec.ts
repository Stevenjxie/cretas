import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(import.meta.dirname, '../index.vue'),
  'utf8',
);

describe('product-process workflow mode quality help', () => {
  it('provides distinct approved explanations for finished and raw modes', () => {
    expect(source).toContain('aria-label="成品质检说明"');
    expect(source).toContain('成品 workflow：以单个成品作为产出目标，支持多个原料投入');
    expect(source).toContain('aria-label="原料质检说明"');
    expect(source).toContain('原料 workflow：以单个原料作为产出目标，支持多个成品产出');
  });
});
