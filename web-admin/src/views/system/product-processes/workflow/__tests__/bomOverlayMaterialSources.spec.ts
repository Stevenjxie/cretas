import { describe, expect, it } from 'vitest';
import {
  selectAuxiliaryMaterials,
  selectByproductMaterials,
  selectPackagingMaterials,
  type BomOverlayMaterialRow,
} from '../bomOverlayMaterialSources';

/**
 * 回归钉子 (2026-08-06): 画布副产弹窗曾直接把**包材筛好的那份**赋给副产
 * (`bomOverlayByproductMaterials.value = bomOverlayPackagingMaterials.value`),
 * 于是副产下拉里全是贴体膜/外箱, 鸡架骨头一个选不到。两份列表类型相同、来自同一端点,
 * 编译与类型检查都不会响 —— 只有这里的「口径互斥」断言才拦得住。
 */

// 六膳门真实形状: 鸡架是**原料**材质 + 副产标记, 按材质怎么筛都筛不出来。
const CHICKEN_FRAME: BomOverlayMaterialRow = {
  id: 'M-FRAME', name: '鸡架', category: '原料', unit: 'kg', isByproduct: true,
};
const BONE_STRING_FLAG: BomOverlayMaterialRow = {
  id: 'M-BONE', name: '鸡骨头', category: '肉类', unit: 'kg', isByproduct: 'true',
};
const VACUUM_BAG: BomOverlayMaterialRow = {
  id: 'M-BAG', name: '2530真空袋', category: '包材', unit: 'pcs', isByproduct: false,
};
const OUTER_BOX: BomOverlayMaterialRow = {
  id: 'M-BOX', name: 'L2 外箱', category: '包材', unit: 'kg',
};
const SOY_SAUCE: BomOverlayMaterialRow = {
  id: 'M-SOY', name: '李锦记薄盐生抽', category: '调味料', unit: 'g',
};
const ADDITIVE: BomOverlayMaterialRow = {
  id: 'M-ADD', name: '乳酸链球菌素', category: '添加剂', unit: 'g',
};
const CHICKEN_LEG: BomOverlayMaterialRow = {
  id: 'M-LEG', name: '鸭腿', category: '原料', unit: 'kg', isByproduct: false,
};

const ARCHIVE = [
  CHICKEN_FRAME, BONE_STRING_FLAG, VACUUM_BAG, OUTER_BOX, SOY_SAUCE, ADDITIVE, CHICKEN_LEG,
];

const idsOf = (rows: BomOverlayMaterialRow[]) => rows.map((row) => row.id).sort();

describe('bomOverlayMaterialSources', () => {
  it('副产只认标记, 不认材质 —— 原料/肉类材质的副产照样选得到', () => {
    expect(idsOf(selectByproductMaterials(ARCHIVE))).toEqual(['M-BONE', 'M-FRAME']);
  });

  it('🔴 副产列表里一个包材都没有 (这正是上线时的缺陷形状)', () => {
    const byproductIds = idsOf(selectByproductMaterials(ARCHIVE));
    expect(byproductIds).not.toContain('M-BAG');
    expect(byproductIds).not.toContain('M-BOX');
  });

  it('包材只认材质, 副产标记不让它进包材档', () => {
    expect(idsOf(selectPackagingMaterials(ARCHIVE))).toEqual(['M-BAG', 'M-BOX']);
  });

  it('辅料含调味料与添加剂, 不含原料/包材/副产', () => {
    expect(idsOf(selectAuxiliaryMaterials(ARCHIVE))).toEqual(['M-ADD', 'M-SOY']);
  });

  it('三个口径两两不相交 —— 任何一对相等都说明又发生了"赋值复用"', () => {
    const byproduct = idsOf(selectByproductMaterials(ARCHIVE));
    const packaging = idsOf(selectPackagingMaterials(ARCHIVE));
    const auxiliary = idsOf(selectAuxiliaryMaterials(ARCHIVE));
    expect(byproduct).not.toEqual(packaging);
    expect(byproduct.filter((id) => packaging.includes(id))).toEqual([]);
    expect(byproduct.filter((id) => auxiliary.includes(id))).toEqual([]);
    expect(packaging.filter((id) => auxiliary.includes(id))).toEqual([]);
  });

  it('副产 SKU 不因标记而从原料侧消失 —— 标记与材质正交 (设计初衷)', () => {
    // 鸡架的材质仍是「原料」, 别的 workflow 还能把它当投入选到。
    expect(CHICKEN_FRAME.category).toBe('原料');
    expect(selectPackagingMaterials([CHICKEN_FRAME])).toEqual([]);
    expect(selectAuxiliaryMaterials([CHICKEN_FRAME])).toEqual([]);
  });

  it('宁可漏认不误认: isByproduct 为 1/"yes" 这类真值不算副产', () => {
    const fuzzy: BomOverlayMaterialRow[] = [
      { id: 'M-1', name: '数字1', category: '原料', isByproduct: 1 },
      { id: 'M-YES', name: 'yes', category: '原料', isByproduct: 'yes' },
      { id: 'M-NULL', name: '空', category: '原料', isByproduct: null },
    ];
    expect(selectByproductMaterials(fuzzy)).toEqual([]);
  });

  it('物料档案里一个副产标记都没有时返回空列表 (六膳门当前的真实状态)', () => {
    expect(selectByproductMaterials([VACUUM_BAG, OUTER_BOX, SOY_SAUCE, CHICKEN_LEG])).toEqual([]);
  });
});
