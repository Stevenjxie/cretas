import { describe, expect, it } from 'vitest';
import { matchesSearchText, pinyinInitials } from '../pinyinInitials';

describe('workflow pinyin initials search', () => {
  it('computes upper-case pinyin initials for known food/process characters', () => {
    expect(pinyinInitials('猪蹄')).toBe('ZT');
    expect(pinyinInitials('熟成鸡')).toBe('SCJ');
  });

  it('matches product pickers by pinyin initials, case-insensitive', () => {
    expect(matchesSearchText('zt', '猪蹄')).toBe(true);
    expect(matchesSearchText('ZT', '猪蹄')).toBe(true);
    expect(matchesSearchText('zt', '五香去骨猪蹄 400g')).toBe(true);
  });

  it('still matches by literal substring, case-insensitive', () => {
    expect(matchesSearchText('猪蹄', '五香去骨猪蹄 400g')).toBe(true);
    expect(matchesSearchText('fg-pig', 'FG-PIG-400')).toBe(true);
  });

  it('treats an empty/blank query as matching everything', () => {
    expect(matchesSearchText('', '猪蹄')).toBe(true);
    expect(matchesSearchText('   ', '猪蹄')).toBe(true);
  });

  it('does not match unrelated initials', () => {
    expect(matchesSearchText('zt', '熟成鸡')).toBe(false);
  });

  it('does not crash on unmapped characters and does not falsely match', () => {
    // 表里没收录的生僻字不贡献首字母, 但不应报错, 也不应该凭空产生匹配。
    expect(() => pinyinInitials('鼗')).not.toThrow();
    expect(matchesSearchText('zzzz', '鼗')).toBe(false);
  });
});
