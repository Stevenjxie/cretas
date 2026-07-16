export interface ProductionTraceDocument {
  documentType: string;
  documentId: string;
  documentNumber?: string | null;
  status?: string | null;
  direction?: 'UPSTREAM' | 'EXECUTION' | 'DOWNSTREAM' | string;
  relation?: string | null;
  occurredAt?: string | null;
}

export interface ProductionDocumentTrace {
  productionPlanId: string;
  planNumber: string;
  planStatus?: string | null;
  documents: ProductionTraceDocument[];
  missingLinks: string[];
}
