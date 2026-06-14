import {
  generateHikvisionPassword,
  getPasswordStrength,
  validateHikvisionPassword,
} from '../../../utils/passwordGenerator';

describe('passwordGenerator', () => {
  it('generates Hikvision-compatible passwords', () => {
    const password = generateHikvisionPassword();

    expect(password).toHaveLength(8);
    expect(validateHikvisionPassword(password)).toEqual({ valid: true });
    expect(password.toLowerCase()).not.toContain('admin');
  });

  it('rejects invalid Hikvision passwords with specific rule failures', () => {
    expect(validateHikvisionPassword('Ab2')).toMatchObject({ valid: false });
    expect(validateHikvisionPassword('abcdefgh')).toMatchObject({ valid: false });
    expect(validateHikvisionPassword('Admin123')).toMatchObject({ valid: false });
  });

  it('classifies password strength by length and character variety', () => {
    expect(getPasswordStrength('abc')).toBe('weak');
    expect(getPasswordStrength('abcdef12')).toBe('medium');
    expect(getPasswordStrength('Abcdef123456')).toBe('strong');
  });
});
