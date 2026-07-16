import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(process.cwd(), 'src/views/production/bom/index.vue'), 'utf8');

describe('BOM same-source copy page integration', () => {
  it('automatically checks once per empty SKU and keeps manual create available', () => {
    expect(source).toContain('const automaticallyPromptedProductIds = new Set<string>()');
    expect(source).toContain('automaticallyPromptedProductIds.has(targetProductTypeId)');
    expect(source).toContain('automaticallyPromptedProductIds.add(targetProductTypeId)');
    expect(source).toContain('bomRecipes.value.length === 0 && canWrite.value');
    expect(source).toContain('await openBomCopySuggestions(true)');
  });

  it('refreshes and selects the returned draft without activating it', () => {
    expect(source).toContain('loadBomRecipes(response.data.id)');
    expect(source).toContain('bomRecipeApi.copyToProduct(factoryId.value, payload)');
    expect(source).not.toContain('bomRecipeApi.activate(factoryId.value, response.data.id)');
    expect(source).toContain('@blank-create="handleBlankCreateFromCopyDialog"');
  });
});
