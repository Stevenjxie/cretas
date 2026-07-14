import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const readSource = (path: string) => readFileSync(resolve(process.cwd(), path), 'utf8');

const bomSource = readSource('src/views/production/bom/index.vue');
const unifiedSource = readSource('src/views/production/bom-unified/index.vue');
const routerSource = readSource('src/router/index.ts');

describe('seasoning BOM integration source contract', () => {
  it('keeps only the materials and conversion top-level tabs', () => {
    expect(unifiedSource).toContain('label="原辅料配方"');
    expect(unifiedSource).toContain('label="转换率"');
    expect(unifiedSource).not.toContain('label="调料配方"');
    expect(unifiedSource).not.toContain('RecipeContent');
  });

  it('normalizes both legacy seasoning entries to the process-first auxiliary view', () => {
    for (const source of [unifiedSource, routerSource]) {
      expect(source).toContain("tab: 'materials'");
      expect(source).toContain("category: 'AUXILIARY'");
      expect(source).toContain("auxView: 'process'");
    }
    expect(routerSource).toContain("path: 'product-recipes'");
    expect(routerSource).not.toContain("import('@/views/production/ProductRecipeView.vue')");
  });

  it('renders the process workspace only for auxiliary materials with an explicit recipe', () => {
    expect(bomSource).toContain("activeCategoryTab === 'AUXILIARY'");
    expect(bomSource).toContain('<BomAuxiliaryWorkspace');
    expect(bomSource).toContain(':recipe-id="selectedRecipe.id"');
    expect(bomSource).toContain(':recipe-status="selectedRecipe.status"');
    expect(bomSource).toContain("recipe.status === 'DRAFT'");
    expect(bomSource).toContain("recipe.status === 'ACTIVE'");
  });

  it('does not offer auxiliary material through the generic create flow', () => {
    expect(bomSource).not.toContain('<el-option label="辅料" value="AUXILIARY" />');
    expect(bomSource).toContain('待绑定工序 / 可能重复计成本');
    expect(bomSource).toContain('批量绑定工序（暂不可用）');
  });
});
