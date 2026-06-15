/**
 * Lane A · F2 厂号筛选纯逻辑单测.
 *
 * 验证 deriveFactoryNumberKeys / isRowVisibleUnderFactoryFilter — 同品多厂号时
 * 按厂号分组/筛选 (不同厂号 = 不同批次), 防呆 Rule 2.
 */
import {
  NO_FACTORY_NUMBER,
  deriveFactoryNumberKeys,
  isRowVisibleUnderFactoryFilter,
} from '../../../components/processing/MaterialBatchPicker';

describe('deriveFactoryNumberKeys', () => {
  it('单一厂号 → 不展示筛选 (showFilter=false)', () => {
    const { keys, showFilter } = deriveFactoryNumberKeys(['F-001', 'F-001']);
    expect(keys).toEqual(['F-001']);
    expect(showFilter).toBe(false);
  });

  it('多厂号 → 去重保序 + 展示筛选', () => {
    const { keys, showFilter } = deriveFactoryNumberKeys(['乙厂', '甲厂', '乙厂', '甲厂']);
    expect(keys).toEqual(['乙厂', '甲厂']);
    expect(showFilter).toBe(true);
  });

  it('空/null 厂号归入 NO_FACTORY_NUMBER 哨兵', () => {
    const { keys, showFilter } = deriveFactoryNumberKeys(['甲厂', null, '', undefined]);
    expect(keys).toEqual(['甲厂', NO_FACTORY_NUMBER]);
    expect(showFilter).toBe(true);
  });

  it('全无厂号 → 单一哨兵, 不展示筛选', () => {
    const { keys, showFilter } = deriveFactoryNumberKeys([null, '', undefined]);
    expect(keys).toEqual([NO_FACTORY_NUMBER]);
    expect(showFilter).toBe(false);
  });

  it('空列表 → 空 keys, 不展示', () => {
    expect(deriveFactoryNumberKeys([])).toEqual({ keys: [], showFilter: false });
  });
});

describe('isRowVisibleUnderFactoryFilter', () => {
  it('未展示筛选时全部可见', () => {
    expect(isRowVisibleUnderFactoryFilter('甲厂', false, '乙厂', false)).toBe(true);
  });

  it('无激活筛选 (filter=null) 时全部可见', () => {
    expect(isRowVisibleUnderFactoryFilter('甲厂', false, null, true)).toBe(true);
  });

  it('匹配激活厂号的行可见', () => {
    expect(isRowVisibleUnderFactoryFilter('甲厂', false, '甲厂', true)).toBe(true);
  });

  it('不匹配且未选中 → 隐藏', () => {
    expect(isRowVisibleUnderFactoryFilter('乙厂', false, '甲厂', true)).toBe(false);
  });

  it('不匹配但已选中 → 仍可见 (防止丢失已选批次)', () => {
    expect(isRowVisibleUnderFactoryFilter('乙厂', true, '甲厂', true)).toBe(true);
  });

  it('无厂号行在 NO_FACTORY_NUMBER 筛选下可见', () => {
    expect(isRowVisibleUnderFactoryFilter(null, false, NO_FACTORY_NUMBER, true)).toBe(true);
    expect(isRowVisibleUnderFactoryFilter('', false, NO_FACTORY_NUMBER, true)).toBe(true);
  });
});
