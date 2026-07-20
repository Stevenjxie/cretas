import { get, post } from './request';
import type { ApiResponse } from '@/types/api';

export interface EvalCase {
  caseId: string;
  expectedRoute: 'GROSS_MARGIN_DECLINE_ATTRIBUTION';
  requiredTools: string[];
  numericTruthRefs: Record<string, string>;
  maxRounds: number;
  maxToolCalls: number;
  inputSnapshot?: { startDate: string; endDate: string; storeTopN: number; dishTopN: number };
  sourceRunId?: string;
  evidenceDigests?: Record<string, string>;
}

export interface EvalSetSummary {
  evalSetId: string;
  name: string;
  version: number;
  description: string;
  caseCount: number;
  contentDigest: string;
  createdBy: string;
  createdAt: string;
}

export interface PageInfo {
  offset: number;
  returned: number;
  total: number;
  hasMore: boolean;
  nextOffset: number | null;
}

export interface EvalSetDetail extends EvalSetSummary { cases: EvalCase[]; page: PageInfo }

export interface ExperimentSummary {
  experimentId: string;
  evalSetId: string;
  evalSetName: string;
  evalSetVersion: number;
  evaluatorVersion: string;
  evaluatorBuild: string;
  snapshotDigest: string;
  operationKind: 'RUN' | 'RERUN' | 'RUNTIME_SHADOW';
  sourceExperimentId: string | null;
  configSnapshot: SnapshotDigests;
  runnerBounds: RunnerBounds;
  aggregate: {
    caseCount: number;
    passedCount: number;
    failedCount: number;
    passRate: string;
    routePassCount: number;
    trajectoryPassCount: number;
    numericTruthPassCount: number;
  };
  createdBy: string;
  createdAt: string;
}

export interface ExperimentCompare {
  experimentId: string;
  baselineExperimentId: string;
  sameEvalSetVersion: boolean;
  currentEvaluatorVersion: string;
  baselineEvaluatorVersion: string;
  evaluatorChanged: boolean;
  currentEvaluatorBuild: string;
  baselineEvaluatorBuild: string;
  evaluatorBuildChanged: boolean;
  currentEvalSetVersion: number;
  baselineEvalSetVersion: number;
  passRateDelta: string;
  improvedCaseIds: string[];
  regressedCaseIds: string[];
  sharedCaseCount: number;
  promptSnapshotChanged: boolean;
  modelSnapshotChanged: boolean;
  toolSnapshotChanged: boolean;
}

export interface SnapshotDigests {
  promptSnapshotDigest: string;
  modelSnapshotDigest: string;
  toolSnapshotDigest: string;
}

export interface ActualSnapshot {
  routeCode: string;
  tools: string[];
  numericTruthRefs: Record<string, string>;
  roundsUsed: number;
  toolCallsUsed: number;
}

export interface RunnerBounds {
  maxCases: number;
  maxConcurrency: number;
  perCaseTimeoutMs: number;
}

export interface RunExperimentRequest {
  schemaVersion: '1.0';
  requestId: string;
  evalSetId: string;
  configSnapshot: SnapshotDigests;
  actualSnapshots: Record<string, ActualSnapshot>;
  bounds: RunnerBounds;
}

export interface ImportRuntimeCorpusRequest {
  schemaVersion: '1.0';
  requestId: string;
  name: string;
  version: number;
  description: string;
  maxCases: number;
}

export interface RunRuntimeShadowRequest {
  schemaVersion: '1.0';
  requestId: string;
  evalSetId: string;
  configSnapshot: SnapshotDigests;
  bounds: RunnerBounds;
}

export interface ExperimentDetail extends ExperimentSummary {
  caseResults: Array<Record<string, unknown>>;
  actualSnapshots: Record<string, ActualSnapshot>;
  page: PageInfo;
}

export interface AgentRunTrace {
  runId: string;
  routeCode: string;
  state: string;
  correlationId: string;
  inputSummary: Record<string, string | number>;
  outcome: Record<string, unknown> | null;
  failureCode: string | null;
  counters: Record<string, number>;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
  events: Array<{
    sequence: number;
    eventType: string;
    stepId: string | null;
    toolName: string | null;
    payload: Record<string, unknown>;
    createdAt: string;
  }>;
  page: {
    afterSequence: number;
    limit: number;
    returned: number;
    hasMore: boolean;
    nextAfterSequence: number | null;
  };
}

export interface CreateEvalSetRequest {
  schemaVersion: '1.0';
  requestId: string;
  name: string;
  version: number;
  description: string;
  cases: EvalCase[];
}

export interface RerunExperimentRequest {
  schemaVersion: '1.0';
  requestId: string;
}

const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MAX_WRITE_BODY_BYTES = 4 * 1024 * 1024;

function base(factoryId: string): string {
  if (!SAFE_ID.test(factoryId)) throw new Error('FACTORY_ID_REQUIRED');
  return `/${encodeURIComponent(factoryId)}/agent-ops`;
}

function uuid(value: string): string {
  if (!UUID.test(value)) throw new Error('VALID_UUID_REQUIRED');
  return value.toLowerCase();
}

export const listEvalSets = (factoryId: string): Promise<ApiResponse<{ items: EvalSetSummary[] }>> =>
  get(`${base(factoryId)}/eval-sets`);

export const getEvalSet = (factoryId: string, id: string, offset = 0, limit = 25): Promise<ApiResponse<EvalSetDetail>> =>
  get(`${base(factoryId)}/eval-sets/${uuid(id)}`, { params: page(offset, limit) });

export const createEvalSet = (factoryId: string, body: CreateEvalSetRequest): Promise<ApiResponse<EvalSetSummary>> =>
  boundedPost(`${base(factoryId)}/eval-sets`, body);

export const importRuntimeCorpus = (
  factoryId: string,
  body: ImportRuntimeCorpusRequest,
): Promise<ApiResponse<EvalSetSummary>> =>
  boundedPost(`${base(factoryId)}/eval-sets/import-runtime-corpus`, body);

export const listExperiments = (factoryId: string): Promise<ApiResponse<{ items: ExperimentSummary[] }>> =>
  get(`${base(factoryId)}/experiments`);

export const runExperiment = (
  factoryId: string,
  body: RunExperimentRequest,
): Promise<ApiResponse<ExperimentSummary>> =>
  boundedPost(`${base(factoryId)}/experiments`, body);

export const runRuntimeShadow = (
  factoryId: string,
  body: RunRuntimeShadowRequest,
): Promise<ApiResponse<ExperimentSummary>> =>
  boundedPost(`${base(factoryId)}/experiments/runtime-shadow`, body);

export const getExperiment = (
  factoryId: string,
  id: string,
  offset = 0,
  limit = 25,
): Promise<ApiResponse<ExperimentDetail>> =>
  get(`${base(factoryId)}/experiments/${uuid(id)}`, { params: page(offset, limit) });

export const rerunExperiment = (
  factoryId: string,
  id: string,
  body: RerunExperimentRequest,
): Promise<ApiResponse<ExperimentSummary>> =>
  boundedPost(`${base(factoryId)}/experiments/${uuid(id)}/rerun`, body);

export const compareExperiments = (
  factoryId: string,
  experimentId: string,
  baselineId: string,
): Promise<ApiResponse<ExperimentCompare>> =>
  get(`${base(factoryId)}/experiments/${uuid(experimentId)}/compare`, {
    params: { baselineId: uuid(baselineId) },
  });

export const getRunTrace = (
  factoryId: string,
  runId: string,
  afterSequence = 0,
  limit = 100,
): Promise<ApiResponse<AgentRunTrace>> => {
  if (!Number.isSafeInteger(afterSequence) || afterSequence < 0) throw new Error('TRACE_CURSOR_INVALID');
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new Error('TRACE_LIMIT_INVALID');
  return get(`${base(factoryId)}/traces/${uuid(runId)}`, { params: { afterSequence, limit } });
};

function page(offset: number, limit: number): { offset: number; limit: number } {
  if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isInteger(limit) || limit < 1 || limit > 50) {
    throw new Error('PAGE_INVALID');
  }
  return { offset, limit };
}

function boundedPost<T>(
  url: string,
  body: { schemaVersion: '1.0'; requestId: string },
): Promise<ApiResponse<T>> {
  if (body.schemaVersion !== '1.0') throw new Error('AGENT_OPS_SCHEMA_VERSION_UNSUPPORTED');
  uuid(body.requestId);
  const serialized = JSON.stringify(body);
  if (new TextEncoder().encode(serialized).byteLength > MAX_WRITE_BODY_BYTES) {
    throw new Error('AGENT_OPS_REQUEST_PAYLOAD_TOO_LARGE');
  }
  return post(url, body);
}
