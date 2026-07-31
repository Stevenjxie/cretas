export interface ProductionTraceDocument {
  documentType: string;
  documentId: string;
  documentNumber?: string | null;
  status?: string | null;
  direction?: 'UPSTREAM' | 'EXECUTION' | 'DOWNSTREAM' | string;
  relation?: string | null;
  occurredAt?: string | null;
  /**
   * 该单据的关键字段，供抽屉里就地展开（客户 2026-07-31：追踪里看详情不跳页）。
   * 与 businessDocumentTrace 的同名字段同一契约 —— 后端构建链路时顺手带回，
   * 拿不到的字段直接不放，所以前端不会渲染出空标签。
   */
  details?: Array<{ label: string; value: string }> | null;
}

export interface ProductionDocumentTrace {
  productionPlanId: string;
  planNumber: string;
  planStatus?: string | null;
  documents: ProductionTraceDocument[];
  missingLinks: string[];
}
