import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const bomSource = readFileSync(
  resolve(import.meta.dirname, '../index.vue'),
  'utf8',
);
const unifiedSource = readFileSync(
  resolve(import.meta.dirname, '../../bom-unified/index.vue'),
  'utf8',
);
const apiSource = readFileSync(
  resolve(import.meta.dirname, '../../../../api/bom.ts'),
  'utf8',
);

describe('BOM loading UX source contract', () => {
  it('loads the primary BOM view with the route shell while keeping only the secondary tab lazy', () => {
    expect(unifiedSource).toContain("import BomContent from '@/views/production/bom/index.vue'");
    expect(unifiedSource).not.toContain(
      "defineAsyncComponent(() => import('@/views/production/bom/index.vue'))",
    );
    expect(unifiedSource).toContain(
      "defineAsyncComponent(() => import('@/views/production/conversions/index.vue'))",
    );
  });

  it('keeps one stable workspace skeleton and a draft action row outside the scroll container', () => {
    expect(bomSource).toContain('data-testid="bom-workspace-skeleton"');
    expect(bomSource).toContain('aria-busy="true"');
    expect(bomSource).toMatch(
      /<\/template>\s*<\/div>\s*<footer[\s\S]*class="bom-draft-bar"/,
    );
    expect(bomSource).toContain('.bom-page__scroll');
    expect(bomSource).toMatch(/\.bom-page\s*\{[\s\S]*?overflow:\s*hidden;/);
  });

  it('silences the global readiness toast because the product coordinator owns one-error presentation', () => {
    expect(apiSource).toMatch(
      /product-configuration-readiness\/\$\{productTypeId\}[\s\S]*?_silent:\s*true/,
    );
    expect(bomSource).toContain('workspaceLoadCoordinator.shouldNotifyOnce');
    expect(bomSource).toContain('workspaceLoadCoordinator.singleFlight');
  });

  it('loads only the selected product versions instead of scanning a factory-wide recipe page', () => {
    expect(apiSource).toContain(
      'getVersionsByProduct: (factoryId: string, productTypeId: string)',
    );
    expect(bomSource).toContain(
      'bomRecipeApi.getVersionsByProduct(currentFactoryId, productTypeId)',
    );
    expect(bomSource).not.toContain(
      'bomRecipeApi.listRecipes(currentFactoryId, { size: 200 })',
    );
  });

  it('clears the previous product recipe state before starting a new workspace request', () => {
    const loadWorkspaceSource = bomSource.slice(
      bomSource.indexOf('async function loadProductWorkspace'),
      bomSource.indexOf('async function loadBomDetail'),
    );
    const recipesResetIndex = loadWorkspaceSource.indexOf('bomRecipes.value = [];');
    const selectionResetIndex = loadWorkspaceSource.indexOf("selectedRecipeId.value = '';");
    const recipeRequestIndex = loadWorkspaceSource.indexOf('loadBomRecipes()');

    expect(recipesResetIndex).toBeGreaterThan(-1);
    expect(selectionResetIndex).toBeGreaterThan(-1);
    expect(recipeRequestIndex).toBeGreaterThan(-1);
    expect(recipesResetIndex).toBeLessThan(recipeRequestIndex);
    expect(selectionResetIndex).toBeLessThan(recipeRequestIndex);
  });
});
