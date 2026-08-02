import fs from 'fs';
import path from 'path';

const srcRoot = path.resolve(__dirname, '../../../');
const read = (relativePath: string) =>
  fs.readFileSync(path.join(srcRoot, relativePath), 'utf8');

describe('restaurant create screens use master-data selectors', () => {
  const recipeSource = read('screens/restaurant/recipes/RecipeEditScreen.tsx');
  const recipeListSource = read('screens/restaurant/recipes/RecipeListScreen.tsx');
  const recipeDetailSource = read('screens/restaurant/recipes/RecipeDetailScreen.tsx');
  const requisitionSource = read('screens/restaurant/requisition/RequisitionCreateScreen.tsx');
  const navigationSource = read('types/navigation.ts');

  it('never asks users to type internal dish or ingredient IDs', () => {
    expect(recipeSource).not.toContain('placeholder="PT-XXX"');
    expect(recipeSource).not.toContain('placeholder="MT-XXX"');
    expect(requisitionSource).not.toContain('placeholder="PT-XXX"');
    expect(requisitionSource).not.toContain('placeholder="MT-XXX"');
    expect(recipeSource).toContain('ProductTypeSelector');
    expect(recipeSource).toContain('MaterialSelectModal');
    expect(requisitionSource).toContain('ProductTypeSelector');
    expect(requisitionSource).toContain('MaterialSelectModal');
    expect(recipeListSource).toContain('testID="recipe-create-fab"');
    expect(recipeSource).toContain('showConversionStatus={false}');
    expect(requisitionSource).toContain('showConversionStatus={false}');
  });

  it('carries the selected ingredient unit into both forms', () => {
    expect(recipeSource).toContain('unit: material.defaultUnit || prev.unit');
    expect(requisitionSource).toContain('unit: material.defaultUnit || prev.unit');
  });

  it('preserves dish context when adding an ingredient from recipe detail', () => {
    expect(recipeDetailSource).toContain("navigation.navigate('RecipeEdit', { productTypeId, dishName })");
    expect(recipeSource).toContain("productTypeId: route.params?.productTypeId || ''");
    expect(recipeSource).toContain('testID="recipe-dish-fixed"');
    expect(navigationSource).toContain('RecipeEdit: { productTypeId?: string; dishName?: string } | undefined;');
  });
});
