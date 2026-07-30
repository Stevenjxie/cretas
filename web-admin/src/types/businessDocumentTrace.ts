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
