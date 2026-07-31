/**
 * 单据追踪的前端契约。
 *
 * 后端有两个入口但形状刻意一致:
 * - 生产计划: `ProductionDocumentTraceResponse` (锚点字段名是 planNumber/planStatus)
 * - 销售/采购/调拨: `BusinessDocumentTraceResponse` (锚点字段名是 anchorNumber/anchorStatus)
 *
 * 抽屉组件只认这里的 `DocumentTrace`; 生产计划那份由调用方用 `fromProductionTrace` 适配,
 * 免得抽屉里塞两套字段名的分支。
 */

export interface TraceDocument {
  documentType: string;
  documentId: string;
  documentNumber?: string | null;
  status?: string | null;
  direction?: 'UPSTREAM' | 'EXECUTION' | 'DOWNSTREAM' | string;
  relation?: string | null;
  occurredAt?: string | null;
  /**
   * 该单据的关键字段，供抽屉里**就地展开**（客户 2026-07-31：追踪里看详情不跳页）。
   *
   * 由后端在构建链路时顺手带回 —— 那些实体本来就已经读出来了，不必前端按 15 种单据类型
   * 各请求一次。value 已经是可直接显示的字符串（金额/日期在后端格式化）。
   * 拿不到的字段后端**直接不放**，所以前端不会渲染出一行空标签。
   */
  details?: Array<{ label: string; value: string }> | null;
}

/** 后端 `BusinessDocumentTraceResponse` 原样。 */
export interface BusinessDocumentTrace {
  anchorType: string;
  anchorId: string;
  anchorNumber?: string | null;
  anchorStatus?: string | null;
  documents: TraceDocument[];
  missingLinks: string[];
}

/** 抽屉组件消费的归一形状 (= BusinessDocumentTrace)。 */
export type DocumentTrace = BusinessDocumentTrace;
