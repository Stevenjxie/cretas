import { describe, expect, it } from 'vitest';

import {
  ingredientDisplayName,
  normalizeIngredientOption,
  normalizedIngredientValue,
} from './ingredientOption';

describe('price anomaly ingredient options', () => {
  it('keeps ordinary scalar API fields unchanged', () => {
    const identity = {
      normalizedName: 'qingcai',
      ingredientName: '青菜',
    };

    expect(normalizeIngredientOption(identity)).toEqual({
      value: 'qingcai',
      label: '青菜',
    });
  });

  it('unwraps option objects so Element Plus never renders value/label objects', () => {
    const identity = {
      normalizedName: { value: 'napkin', label: '餐巾纸' },
      ingredientName: { value: 'napkin', label: '餐巾纸' },
    };

    expect(normalizedIngredientValue(identity)).toBe('napkin');
    expect(ingredientDisplayName(identity)).toBe('餐巾纸');
  });

  it('repairs historical JSON and value:x,label:y string shapes', () => {
    expect(normalizeIngredientOption({
      normalizedName: '{"value":"rice","label":"米饭"}',
      ingredientName: 'value:rice,label:米饭',
    })).toEqual({
      value: 'rice',
      label: '米饭',
    });
  });
});
