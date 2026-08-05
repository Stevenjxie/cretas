/**
 * 产品页的两把写入闸 —— 与后端 @RequirePermission 一一对应。
 *
 * 抽成纯函数不是为了复用（只有一个调用点），而是为了**可断言**：闸写在 SFC 的 computed 里
 * 时只能用源码 grep 去"证明"，而 grep 对「两把闸被合并成一把」这种改动是沉默的
 * （合并后两个模块名仍然都出现在文件里）。抽出来才能拿真实权限矩阵跑行为断言。
 *
 * 后端口径（2026-08-05 逐个端点核对）：
 *   ProductTypeController        所有写端点 = {"production:read_write", "rd:read_write"}
 *   ProductWorkProcessController 所有写端点 = {"production:read_write"}      ← 不含 rd
 *
 * 两者**不能合并**：sales_manager 是 production='r' / rd='rw'，正好落在中间 ——
 * 能建产品，不能配工序。合并成任意一把都会有一侧判错。
 */

/** 只需要「某模块可写吗」这一个能力；由调用方注入 permission store 的 canWrite。 */
export type ModuleWriteProbe = (module: string) => boolean;

/**
 * 产品主数据写权限：新增/编辑/删除 SKU、导入、SKU 组装、AI 建产品。
 *
 * 客户 2026-08-05「无法新建产品了」的直接成因是这里原本判的是 `system` ——
 * 调度 production/rd 可写、system 只读，后端放行而界面把入口藏了。
 */
export function canWriteProductMaster(canWrite: ModuleWriteProbe): boolean {
  return canWrite('production') || canWrite('rd');
}

/**
 * 工序绑定写权限：增删工序、调整工序顺序。
 *
 * 比 {@link canWriteProductMaster} **严格**——后端这条不认 rd。
 */
export function canWriteProductProcess(canWrite: ModuleWriteProbe): boolean {
  return canWrite('production');
}
