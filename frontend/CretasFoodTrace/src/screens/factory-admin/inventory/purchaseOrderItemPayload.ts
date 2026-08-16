/**
 * 新建采购单：一行明细的两个纯判断 —— 「要不要选采购包装规格」和「payload 怎么发」。
 *
 * 抽成纯函数是为了**能被真的跑一遍**。这两处正是缺陷所在，
 * 而源码扫描型的闸只能证明「代码里写了这个字符串」，证明不了「发出去的 payload 对不对」。
 *
 * 后端口径（`PurchaseServiceImpl.applySupplierPurchaseContract`）：
 *  - 供应关系上有【启用中】的采购包装规格 ⇒ 请求**必须**带 `purchasePackagingSpecId`，
 *    否则 422「该供应关系已配置采购包装规格，必须选择具体规格」
 *  - 两个规格字段同时发 ⇒ 后端比对二者换算系数，不一致抛 409
 *    「供应商包装规格与原料包装换算不一致」
 *  ⇒ 所以是**互斥**：要么发采购规格，要么发原料规格。与 web-admin 同口径。
 */

/** 只声明本模块用得到的字段，不依赖屏幕里的 DraftItem 全貌。 */
export interface PurchaseSpecDecisionInput {
  materialTypeId: string;
  /** 该行所属供应关系 id；空 = 查不了规格 */
  supplierMaterialId: string;
  purchasePackagingSpecId: string;
  materialPackagingSpecId: string;
}

/**
 * `undefined` = 还没取；`null` = **取失败**；`[]` = 确认没有。
 * ⛔ 三态不能塌成两态 —— 把「不知道」当成「没有」就会放行，提交时才撞 422。
 */
export type PurchaseSpecsByRelation = Record<string, { id: string }[] | null | undefined>;

export type PurchaseSpecState = 'none' | 'loading' | 'unknown' | 'required' | 'selected';

export function resolvePurchaseSpecState(
  item: PurchaseSpecDecisionInput,
  specsByRelation: PurchaseSpecsByRelation,
): PurchaseSpecState {
  if (!item.materialTypeId) return 'none';
  // 选了物料却没有供应关系 id ⇒ 查不了规格，属于「不知道」而不是「没有」
  if (!item.supplierMaterialId) return 'unknown';
  const specs = specsByRelation[item.supplierMaterialId];
  if (specs === undefined) return 'loading';
  if (specs === null) return 'unknown';
  if (specs.length === 0) return 'none';
  return item.purchasePackagingSpecId ? 'selected' : 'required';
}

/** 只有这三态可以提交；其余都要在界面上拦住并说明原因。 */
export function canSubmitPurchaseSpecState(state: PurchaseSpecState): boolean {
  return state === 'none' || state === 'selected';
}

export interface PurchaseOrderItemPayload {
  materialTypeId: string;
  supplierMaterialId?: string;
  purchasePackagingSpecId?: string;
  materialPackagingSpecId?: string;
  quantity: number;
  unitPrice: number;
  unit: string;
}

export function buildPurchaseOrderItemPayload(item: {
  materialTypeId: string;
  supplierMaterialId: string;
  purchasePackagingSpecId: string;
  materialPackagingSpecId: string;
  quantity: string;
  unitPrice: string;
  unit: string;
}): PurchaseOrderItemPayload {
  return {
    materialTypeId: item.materialTypeId,
    supplierMaterialId: item.supplierMaterialId || undefined,
    // 互斥：选了采购规格就不发原料规格，否则后端比换算系数会抛 409
    purchasePackagingSpecId: item.purchasePackagingSpecId || undefined,
    materialPackagingSpecId: item.purchasePackagingSpecId
      ? undefined
      : (item.materialPackagingSpecId || undefined),
    quantity: Number(item.quantity),
    unitPrice: Number(item.unitPrice),
    unit: item.unit,
  };
}
