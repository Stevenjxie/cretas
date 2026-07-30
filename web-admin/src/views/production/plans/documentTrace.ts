/**
 * 生产计划单据追踪的导航映射。
 *
 * 2026-07-30: 销售/采购/调拨 也上了单据追踪, 映射表收敛到 `@/utils/documentTraceNavigation`
 * 单一来源 —— 这里只做转发, 免得同一份 documentType → 路由表在仓库里散成四份。
 */
export { documentTraceTarget, traceDocumentLabel } from '@/utils/documentTraceNavigation';
