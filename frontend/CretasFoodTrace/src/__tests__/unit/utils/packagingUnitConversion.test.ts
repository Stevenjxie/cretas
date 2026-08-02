/**
 * 单位换算契约测试。
 *
 * 用例形状全部取自 prod F006 实测数据 —— 尤其是那些**必须拒绝换算**的场景:
 * 静默按 1:1 换算正是「可用数量 725,908.175」和「box 当 kg 算投料偏大 25%」的成因,
 * 所以"该返回 null 时真的返回 null"跟"该算对时算对"同等重要。
 */
import {
  canonicalUnit,
  sameUnit,
  convertByHierarchy,
  bucketByDimension,
  formatQuantity,
} from '../../../utils/packagingUnitConversion';
import type { MaterialPackagingHierarchy } from '../../../services/api/materialPackagingApiClient';

/** 黄油鸡: 800 g = 1 盒, 8 盒 = 1 箱 (与 product_unit_conversions 的 case→box=8 / box→g=800 一致) */
const 黄油鸡: MaterialPackagingHierarchy = {
  materialTypeId: 'RMT_TEST_CHICKEN',
  level1Unit: 'g',
  level1PerLevel2: 800,
  level2Unit: 'box',
  level2PerLevel3: 8,
  level3Unit: 'case',
};

/** prod F006 真实行: kg ×10 = 箱, ×5 = 柜 (两级+三级) */
const 三文鱼: MaterialPackagingHierarchy = {
  materialTypeId: 'RMT_1777441647274',
  level1Unit: 'kg',
  level1PerLevel2: 10,
  level2Unit: '箱',
  level2PerLevel3: 5,
  level3Unit: '柜',
};

/** 只有一级 —— 封膜这类没有包装层级的包材 */
const 封膜: MaterialPackagingHierarchy = {
  materialTypeId: 'RMT_TEST_FILM',
  level1Unit: 'slice',
};

describe('canonicalUnit / sameUnit — 中英同义归一', () => {
  it('库里中英混存的同义单位折成同一写法', () => {
    expect(canonicalUnit('盒')).toBe('box');
    expect(canonicalUnit('箱')).toBe('case');
    expect(canonicalUnit('BOX')).toBe('box');
    expect(sameUnit('盒', 'box')).toBe(true);
    expect(sameUnit('箱', 'case')).toBe(true);
  });

  it('不同的计数单位绝不能被判成同一个 —— #1976 定的「一只 ≠ 一件」', () => {
    expect(sameUnit('盒', '箱')).toBe(false);
    expect(sameUnit('片', '卷')).toBe(false);
    expect(sameUnit('box', 'case')).toBe(false);
  });

  it('空单位不等于任何东西 (含另一个空)', () => {
    expect(sameUnit(null, null)).toBe(false);
    expect(sameUnit('', 'box')).toBe(false);
  });
});

describe('convertByHierarchy — 同一原料层级内换算', () => {
  it('从最小单位往上换: 8000 g = 10 盒 = 1.25 箱', () => {
    const r = convertByHierarchy(8000, 'g', 黄油鸡)!;
    expect(r.matchedLevel).toBe(1);
    expect(r.levels).toEqual([
      { unit: 'g', quantity: 8000 },
      { unit: 'box', quantity: 10 },
      { unit: 'case', quantity: 1.25 },
    ]);
  });

  it('从中间单位换: 10000 盒 = 800 万 g = 1250 箱 (库存页真实数字)', () => {
    const r = convertByHierarchy(10000, 'box', 黄油鸡)!;
    expect(r.matchedLevel).toBe(2);
    expect(r.levels[0]).toEqual({ unit: 'g', quantity: 8000000 });
    expect(r.levels[2]).toEqual({ unit: 'case', quantity: 1250 });
  });

  it('从最大单位换: 1 箱 = 8 盒 = 6400 g', () => {
    const r = convertByHierarchy(1, 'case', 黄油鸡)!;
    expect(r.matchedLevel).toBe(3);
    expect(r.levels).toEqual([
      { unit: 'g', quantity: 6400 },
      { unit: 'box', quantity: 8 },
      { unit: 'case', quantity: 1 },
    ]);
  });

  it('中文单位也能换 (库里就是中文「盒」)', () => {
    const r = convertByHierarchy(8, '盒', 黄油鸡)!;
    expect(r.levels[2]).toEqual({ unit: 'case', quantity: 1 });
  });

  it('prod 真实行 三文鱼: 100 kg = 10 箱 = 2 柜', () => {
    const r = convertByHierarchy(100, 'kg', 三文鱼)!;
    expect(r.levels).toEqual([
      { unit: 'kg', quantity: 100 },
      { unit: '箱', quantity: 10 },
      { unit: '柜', quantity: 2 },
    ]);
  });

  // ---- 以下是「必须拒绝」的场景, 静默换算就是缺陷 ----

  it('没配层级 → null, 不许猜', () => {
    expect(convertByHierarchy(100, 'box', null)).toBeNull();
    expect(convertByHierarchy(100, 'box', undefined)).toBeNull();
  });

  it('单位不在这条层级里 → null (封膜按片, 问它多少箱应当答不出来)', () => {
    expect(convertByHierarchy(100, 'case', 封膜)).toBeNull();
    expect(convertByHierarchy(100, 'kg', 黄油鸡)).toBeNull();
  });

  it('只配了一级 → 只返回那一级, 不虚构二三级', () => {
    const r = convertByHierarchy(10000, 'slice', 封膜)!;
    expect(r.levels).toEqual([{ unit: 'slice', quantity: 10000 }]);
  });

  it('层级因子是 0 或负数 → 当成没配, 不能拿去做除数', () => {
    const 坏配置: MaterialPackagingHierarchy = {
      materialTypeId: 'X', level1Unit: 'g', level1PerLevel2: 0, level2Unit: 'box',
    };
    const r = convertByHierarchy(100, 'g', 坏配置)!;
    expect(r.levels).toEqual([{ unit: 'g', quantity: 100 }]);
    expect(r.levels.some((l) => !Number.isFinite(l.quantity))).toBe(false);
  });

  it('配了二级单位却没配因子 → 不产出该级 (而不是按 1 算)', () => {
    const 缺因子: MaterialPackagingHierarchy = {
      materialTypeId: 'X', level1Unit: 'g', level2Unit: 'box',
    };
    const r = convertByHierarchy(100, 'g', 缺因子)!;
    expect(r.levels).toEqual([{ unit: 'g', quantity: 100 }]);
  });
});

describe('bucketByDimension — 汇总时按量纲分组', () => {
  it('重量族折成 kg 合并; 计数族按单位各自成组, 绝不互相合并', () => {
    // prod F006 实测构成
    const buckets = bucketByDimension([
      { unit: 'g', quantity: 700340 },
      { unit: 'kg', quantity: 4829 },
      { unit: '盒', quantity: 10000 },
      { unit: 'box', quantity: 213 },
      { unit: 'slice', quantity: 10207.88 },
      { unit: 'case', quantity: 310 },
    ]);

    const weight = buckets.find((b) => b.kind === 'weight')!;
    expect(weight.unit).toBe('kg');
    // 4829 + 700.34 = 5529.34, 而不是把 70 万克当 70 万 kg
    expect(weight.quantity).toBeCloseTo(5529.34, 2);

    // 盒和 box 是同义 → 合并成一组; 但绝不和 slice/case 合并
    const box = buckets.find((b) => b.kind === 'count' && b.unit === 'box')!;
    expect(box.quantity).toBe(10213);
    expect(buckets.find((b) => b.unit === 'slice')!.quantity).toBeCloseTo(10207.88, 2);
    expect(buckets.find((b) => b.unit === 'case')!.quantity).toBe(310);

    // 关键: 不存在任何一个把它们加在一起的桶
    const total = buckets.reduce((s, b) => s + b.quantity, 0);
    expect(total).not.toBeCloseTo(725908.175, 1);
  });

  it('没有单位的批次单独成组, 不并进任何真实单位', () => {
    const buckets = bucketByDimension([
      { unit: null, quantity: 5 },
      { unit: 'kg', quantity: 10 },
    ]);
    expect(buckets.find((b) => b.unit === '(未标注)')!.quantity).toBe(5);
    expect(buckets.find((b) => b.kind === 'weight')!.quantity).toBe(10);
  });

  it('空输入返回空数组, 不是一个 0', () => {
    expect(bucketByDimension([])).toEqual([]);
  });
});

describe('formatQuantity', () => {
  it('去掉无意义小数尾巴', () => {
    expect(formatQuantity(8000)).toBe('8000');
    expect(formatQuantity(6.4)).toBe('6.4');
    expect(formatQuantity(1.25)).toBe('1.25');
  });
});
