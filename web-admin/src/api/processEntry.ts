import { post } from './request';

export interface RawInput { materialBatchId: string; quantity: number | null; }
export interface Byproduct { name: string; quantity: number | null; unit: string; unitPrice: number | null; }
export interface UpstreamSource { sourceClientBatchKey: string; feedQuantityKg: number | null; }

export interface StepEntry {
  processOrder: number;
  processName?: string;
  /** 该工序实际操作日 (跨天: 各道各日) → 后端成本报工按真实日期归集. ISO "YYYY-MM-DD". null=录入当天 */
  processDate?: string | null;
  processCategory?: string | null;   // RAW_MATERIAL | SEASONING | PACKAGING | null
  inputQuantity: number | null;
  outputQuantity: number | null;
  unit: string;
  laborStartTime?: string | null;    // "HH:mm"
  laborEndTime?: string | null;
  workerCount?: number | null;
  byproducts?: Byproduct[];
  wasteQuantity?: number | null;
  sampleRetainQuantity?: number | null;
  rawMaterialInputs?: RawInput[];
  potCount?: number | null;
  potRawKgs?: number[];
  upstreamSources?: UpstreamSource[];
}

export interface BatchEntry {
  clientBatchKey: string;
  productTypeId: string;
  batchNumber?: string | null;
  finished: boolean;
  steps: StepEntry[];
}

export interface ProcessChainEntryRequest {
  idempotencyKey: string;
  batches: BatchEntry[];
}

export interface ProcessChainEntryResult {
  idempotentReplay: boolean;
  batchIdsByKey: Record<string, number>;
  batchNumbersByKey: Record<string, string>;
  finishedBatchNumber: string;
  reportsWritten: number;
  consumptionsWritten: number;
  warnings: string[];
}

export function submitProcessChain(factoryId: string, planId: string, body: ProcessChainEntryRequest) {
  return post<ProcessChainEntryResult>(`/${factoryId}/production-plans/${planId}/process-entry`, body);
}
