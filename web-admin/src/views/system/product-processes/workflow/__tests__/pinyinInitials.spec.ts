import { describe, expect, it } from 'vitest';
import { nextTick, ref } from 'vue';
import { matchesSearchText, pinyinInitials, usePinyinFilter } from '../pinyinInitials';

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

// #2: 全局共享模块 (usePinyinFilter composable) —— WorkflowSkuPicker /
// WorkflowMaterialNode 原料选择器 / 顶部产品选择器 / 工序选择 dialog 均复用这份实现。
describe('usePinyinFilter composable', () => {
  const ITEMS = [
    { id: 'PIG', name: '五香去骨猪蹄半成品', code: 'FG-PIG' },
    { id: 'CHICKEN', name: '干式熟成鸡半成品', code: 'FG-CHK' },
  ];

  it('returns every item unfiltered when the query is empty', () => {
    const { filtered } = usePinyinFilter(() => ITEMS, (item) => [item.name]);
    expect(filtered.value).toEqual(ITEMS);
  });

  it('filters reactively by pinyin initials across the configured text fields', () => {
    const { handleFilter, filtered } = usePinyinFilter(() => ITEMS, (item) => [item.name, item.code]);

    handleFilter('zt');
    expect(filtered.value.map((item) => item.id)).toEqual(['PIG']);
  });

  it('matches on any of the multiple text fields supplied by textOf', () => {
    const { handleFilter, filtered } = usePinyinFilter(() => ITEMS, (item) => [item.name, item.code]);

    handleFilter('fg-chk');
    expect(filtered.value.map((item) => item.id)).toEqual(['CHICKEN']);
  });

  it('clears the query (shows everything again) when handleVisibleChange(false) fires', () => {
    const { handleFilter, handleVisibleChange, filtered } = usePinyinFilter(() => ITEMS, (item) => [item.name]);

    handleFilter('zt');
    expect(filtered.value).toHaveLength(1);

    handleVisibleChange(false);
    expect(filtered.value).toEqual(ITEMS);
  });

  it('re-derives filtered results when the underlying reactive source changes (matches real usage: getter reads a ref/prop, not a plain snapshot)', async () => {
    const source = ref([...ITEMS]);
    const { handleFilter, filtered } = usePinyinFilter(() => source.value, (item) => [item.name]);
    handleFilter('zt');
    expect(filtered.value).toHaveLength(1);

    source.value = [];
    await nextTick();
    expect(filtered.value).toHaveLength(0);
  });
});
