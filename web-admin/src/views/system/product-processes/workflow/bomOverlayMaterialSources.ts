import { bigCategoryOf } from '@/utils/materialCategory';
import { bomTabAllowsMaterial } from './bom/bomCategoryTabs';

/**
 * 画布 BOM 叠加层三个编辑弹窗(辅料 / 包材 / 副产)各自的「可选物料」来源。
 *
 * 🔴 提出来的原因是一个真实缺陷 (2026-08-06, #2313 上线后发现):
 * 画布副产弹窗当时写的是
 *
 * ```ts
 * await ensureBomOverlayPackagingMaterials();
 * bomOverlayByproductMaterials.value = bomOverlayPackagingMaterials.value;  // ⛔
 * ```
 *
 * 两个 ref 类型相同、都来自同一个 `/raw-material-types/active` 端点, 编译与类型检查全绿,
 * 但右边那份**已经按包材口径筛过**。结果副产下拉里是 25 项贴体膜/外箱, 选不到鸡架、
 * 骨头这类真正的副产物 —— 功能上线即不可用, 且没有任何报错。
 *
 * **判据: 同一份档案筛出来的两个列表, 类型相同 ≠ 口径相同, 不可互相赋值。**
 * 三个口径在这里各写一遍并各自有单测钉死, 就没有"顺手复用一下"的写法可言了。
 *
 * 口径本身仍收敛在既有权威处, 这里不另立判据:
 * - 辅料 / 包材: 看**材质**大类 ({@link bigCategoryOf} + {@link bomTabAllowsMaterial})
 * - 副产: 看物料上的**标记** (`isByproduct`), 与材质正交 —— 见 utils/byproductMaterial.ts
 */
export interface BomOverlayMaterialRow {
  id: string;
  name: string;
  unit?: string | null;
  category?: string | null;
  /** BOM 明细上的类别码; 物料档案行上偶有该字段, 辅料口径历史上认它。 */
  materialCategory?: string | null;
  /** 副产标记。后端 RawMaterialTypeDTO 直出布尔, 历史接口有过字符串布尔。 */
  isByproduct?: unknown;
}

/**
 * 辅料弹窗可选物料: `materialCategory === 'AUXILIARY'` 或材质大类为 辅料/调料。
 * (调料与辅料同桶是 bomTabBigCategories('AUXILIARY') 的既有口径。)
 */
export function selectAuxiliaryMaterials<T extends BomOverlayMaterialRow>(rows: T[]): T[] {
  return rows.filter((material) => {
    if (material.materialCategory === 'AUXILIARY') return true;
    const category = bigCategoryOf(material.category);
    return category === '辅料' || category === '调料';
  });
}

/** 包材弹窗可选物料: 材质大类为 包材。 */
export function selectPackagingMaterials<T extends BomOverlayMaterialRow>(rows: T[]): T[] {
  return rows.filter((material) => bomTabAllowsMaterial(
    'PACKAGING', null, bigCategoryOf(material.category),
  ));
}

/**
 * 副产弹窗可选物料: 只看 `isByproduct` 标记, **不看材质**。
 *
 * 鸡架的材质是原料、肥油是油脂 —— 按材质怎么筛都筛不出副产。同理, 一个物料被标成副产
 * **不**把它从原料档里赶走 (「副产以后能当原料被别的 workflow 投入」是这条设计的初衷)。
 */
export function selectByproductMaterials<T extends BomOverlayMaterialRow>(rows: T[]): T[] {
  return rows.filter((material) => bomTabAllowsMaterial(
    'BYPRODUCT', material, bigCategoryOf(material.category),
  ));
}
