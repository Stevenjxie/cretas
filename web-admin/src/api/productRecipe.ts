import { del, get, post, put } from './request';

export interface RecipeIngredient {
  id?: string;
  section: 'INJECTION' | 'COOKING';
  seq?: number;
  name: string;
  dosagePerKgG: number | null;
  priceSource1: number | null;
  priceSource2: number | null;
  countInSeasoning: boolean;
  remark?: string | null;
}

export interface ProductRecipe {
  id: string;
  factoryId: string;
  productTypeId: string;
  name: string;
  injectionRate?: number | null;
  cookingPotBaseKg?: number | null;
  subsequentPotRatio?: number | null;
  status: string;
  version: number;
  ingredients: RecipeIngredient[];
  injectionCostPerKg?: number;
  cookingFullCostPerKg?: number;
  costPerKgFirstPot?: number;
  costPerKgSubsequentPot?: number;
}

export interface SaveRecipePayload {
  productTypeId: string;
  name: string;
  injectionRate?: number | null;
  cookingPotBaseKg?: number | null;
  subsequentPotRatio?: number | null;
  ingredients: RecipeIngredient[];
}

export function listRecipes(factoryId: string) {
  return get<ProductRecipe[]>(`/${factoryId}/product-recipes`);
}
export function getRecipe(factoryId: string, id: string) {
  return get<ProductRecipe>(`/${factoryId}/product-recipes/${id}`);
}
export function createRecipe(factoryId: string, payload: SaveRecipePayload) {
  return post<ProductRecipe>(`/${factoryId}/product-recipes`, payload);
}
export function updateRecipe(factoryId: string, id: string, payload: SaveRecipePayload) {
  return put<ProductRecipe>(`/${factoryId}/product-recipes/${id}`, payload);
}
export function deleteRecipe(factoryId: string, id: string) {
  return del<void>(`/${factoryId}/product-recipes/${id}`);
}
