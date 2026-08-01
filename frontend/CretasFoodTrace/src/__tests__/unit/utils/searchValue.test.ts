import { normalizedSearchValue } from '../../../utils/searchValue';

describe('normalizedSearchValue', () => {
  it('supports numeric and string API identifiers without crashing', () => {
    expect(normalizedSearchValue(123456)).toBe('123456');
    expect(normalizedSearchValue('PLAN-AbC')).toBe('plan-abc');
    expect(normalizedSearchValue(null)).toBe('');
  });
});
