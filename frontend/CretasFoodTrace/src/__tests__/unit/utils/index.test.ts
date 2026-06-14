import {
  capitalize,
  deepClone,
  formatNumber,
  isEmpty,
  isValidEmail,
  isValidPhone,
  unique,
} from '../../../utils';

describe('utils index helpers', () => {
  it('handles string helpers', () => {
    expect(isEmpty(null)).toBe(true);
    expect(isEmpty('   ')).toBe(true);
    expect(isEmpty('F006')).toBe(false);
    expect(capitalize('cretas')).toBe('Cretas');
  });

  it('handles number, array and clone helpers', () => {
    const original = { factoryId: 'F006', nested: { quantity: 12 } };
    const clone = deepClone(original);

    clone.nested.quantity = 15;

    expect(formatNumber(12.3456, 1)).toBe('12.3');
    expect(unique(['a', 'b', 'a'])).toEqual(['a', 'b']);
    expect(original.nested.quantity).toBe(12);
    expect(clone.nested.quantity).toBe(15);
  });

  it('validates phone and email formats', () => {
    expect(isValidPhone('13800138000')).toBe(true);
    expect(isValidPhone('12800138000')).toBe(false);
    expect(isValidEmail('factory@example.com')).toBe(true);
    expect(isValidEmail('factory.example.com')).toBe(false);
  });
});
