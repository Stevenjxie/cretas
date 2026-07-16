import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const files = [
  '../../api/request.ts',
  '../../views/system/products/index.vue',
  '../../views/factory/material-requisitions/list.vue',
  '../../views/production/bom/seasoning/SeasoningBindingDialog.vue',
  '../../views/sales/orders/list.vue',
];

describe('persistent notification migration', () => {
  it.each(files)('%s routes notifications through the singleton wrapper', (relativePath) => {
    const source = readFileSync(resolve(import.meta.dirname, relativePath), 'utf8');
    expect(source).not.toMatch(/\bElNotification\s*\(/);
    expect(source).toContain('showSingletonNotification');
  });
});
