/** 供货关系至少要能判断是否停用; 其余字段由调用方的具体类型决定。 */
export interface SupplierRelationLike {
  active?: boolean | null;
}

/** 读取某供应商在本厂的供货关系。 */
export type SupplierMaterialFetcher<T extends SupplierRelationLike> = (
  factoryId: string,
  supplierId: string,
) => Promise<T[]>;

/**
 * 解析编辑/建单时可选的供货关系。
 *
 * 没有供应商时**不发请求** —— 采购单的 supplierId 允许为空 (「开始采购」按销售订单
 * 自动生成的草稿单就没有供应商, 见 Sheet 第 22 行), 而供货关系接口的路径是
 * `/{factoryId}/suppliers/{supplierId}/materials`, 空 id 会拼成 `/suppliers//materials`
 * 并 404, 把调用方整个打断。这里返回空表是诚实的: 还没选供应商, 本来就没有供货关系。
 *
 * 供应商存在却读取失败时**照常抛出**, 不吞成空表 —— 那是错误, 不是「该供应商没有物料」,
 * 吞掉会让用户在残缺的物料下拉里存出错单。
 */
export async function resolveSupplierMaterialRelations<T extends SupplierRelationLike>(
  factoryId: string,
  supplierId: string | null | undefined,
  fetchRelations: SupplierMaterialFetcher<T>,
): Promise<T[]> {
  const resolvedSupplierId = String(supplierId ?? '').trim();
  if (!resolvedSupplierId) return [];
  const relations = await fetchRelations(factoryId, resolvedSupplierId);
  return relations.filter((row) => row.active !== false);
}
