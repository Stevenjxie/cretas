/**
 * 副产物料的判定 —— 唯一入口。
 *
 * 🔴 副产是物料上的**标记**(`isByproduct`)，与 `category` **正交**：
 * category / L1-L3 描述「这是什么材质」(肥油是油脂、舌苔碎肉是肉类)，
 * 副产描述「它怎么来的」(生产产出、没有采购来源)。两件不同的事。
 *
 * 这条契约取代了 2026-07-31 上午的 `category='副产'` 做法。走前端验收时它被两件事推翻：
 *
 * 1. **建不出来**：原料字典「新建原料类型」的类别下拉取的是**物料分段字典的 L1 类族**
 *    (`material-types/list.vue` 的 `materialFamilyOptions`)，prod 上 F006 只有
 *    001 原料 / 002 包材 / 003 辅料 —— 没有「副产」。于是副产 SKU 一个都建不出来，
 *    BOM 第四类的物料下拉永远是空的，整条链(报工落生产仓 → 盘点抵扣)都到不了。
 *
 * 2. 🔴 **堵死设计自己的目标**：副产放进原料字典的理由是「好让它以后能当原料被别的
 *    workflow 投入」，但 BOM「原料」页签的放行集合是 `['原料']`，而 `category='副产'`
 *    的 SKU 归到「副产」桶 —— 它在原料页签里永远选不到，当不成投入。
 *
 * 用标记则同一个 SKU 既能被认成副产(排除采购、进 BOM 第四类、落生产仓)，
 * 又天然还是原料(能被别的 workflow 当投入选到)。
 */

/** 物料上与副产判定相关的最小形状。故意只要这一个字段，避免把整个物料类型拖进来。 */
export interface ByproductFlagged {
  isByproduct?: unknown;
}

/**
 * 这个物料是不是副产。
 *
 * 🔴 **宁可漏认也不误认**：只接受布尔 `true` 与字符串 `'true'`(历史接口有过字符串布尔)。
 * 不接受 `1` / `'yes'` 这类真值 —— 误判会把一个真的采购原料从采购下拉里藏掉，
 * 那是「宁缺勿藏」反过来的伤害(fool-proof-design Rule 5)。
 */
export function isByproductMaterial(material: ByproductFlagged | null | undefined): boolean {
  if (!material || typeof material !== 'object') return false;
  const flag = (material as ByproductFlagged).isByproduct;
  if (flag === true) return true;
  return typeof flag === 'string' && flag.trim().toLowerCase() === 'true';
}

/**
 * 把物料分成「副产」与「可采购」两堆。
 *
 * 采购下拉与补货建议要排除副产 —— 它没有采购来源，`unitPrice` / `movingAvgPrice` /
 * `minStock` 对它全是空的，混进去只会让人选到一个永远没有报价的物料。
 *
 * ⚠️ 2026-07-31 查证：这条排除**此前从没实现过**。上一版 commit 声称「避免出现在采购下拉
 * 与补货建议」，但实际只加了一个归类桶，采购侧与补货建议(后端)都没有任何过滤。
 */
export function splitByproductMaterials<T extends ByproductFlagged>(
  materials: T[] | null | undefined,
): { byproducts: T[]; purchasable: T[] } {
  if (!Array.isArray(materials) || materials.length === 0) {
    return { byproducts: [], purchasable: [] };
  }
  const byproducts: T[] = [];
  const purchasable: T[] = [];
  for (const material of materials) {
    (isByproductMaterial(material) ? byproducts : purchasable).push(material);
  }
  return { byproducts, purchasable };
}
