import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/production/ProductRecipeView.vue'),
  'utf8',
);

describe('ProductRecipeView source contract', () => {
  it('renders a compact process navigation and a single current-process editor', () => {
    expect(source).toContain('data-testid="seasoning-process-nav"');
    expect(source).toContain('data-testid="seasoning-current-process"');
    expect(source).not.toContain('v-for="wp in sortedWorkProcesses"');
  });

  it('selects seasoning material from master data instead of accepting a free-text name', () => {
    expect(source).toContain('v-model="row.materialTypeId"');
    expect(source).toContain('@change="onMaterialChange(row)"');
    expect(source).not.toContain('v-model="row.name"');
  });

  it('does not expose editable legacy dual-price fields', () => {
    expect(source).not.toContain('label="单价1"');
    expect(source).not.toContain('label="单价2"');
    expect(source).toContain('移动均价');
    expect(source).toContain('保存时自动带入');
    expect(source).not.toContain(':disabled="material.movingAvgPrice == null"');
    expect(source).not.toContain('未维护均价');
  });

  it('offers an explicit pot switch and a 0-100 percent input for every process', () => {
    expect(source).toContain('v-model="currentPotEnabled"');
    expect(source).toContain('v-model="currentPotPercent"');
    expect(source).toContain(':max="100"');
  });

  it('does not persist empty process-parameter rows as false seasoning configuration', () => {
    expect(source).toContain('param.subsequentPotRatio != null');
    expect(source).toContain('param.injectionAmountKg != null');
  });
});
