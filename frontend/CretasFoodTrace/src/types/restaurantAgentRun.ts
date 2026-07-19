export const RESTAURANT_AGENT_RUN_ROUTE = 'GROSS_MARGIN_DECLINE_ATTRIBUTION' as const;

// UI visibility only. Keep this local copy aligned with the server-side
// PRICE_VIEW_ROLES contract; Java/Python authorization remains authoritative.
export const RESTAURANT_AGENT_PRICE_VIEW_ROLES: ReadonlySet<string> = new Set([
  'factory_super_admin',
  'platform_admin',
  'procurement_manager',
  'finance_manager',
  'sales_manager',
  'dispatcher',
  'production_manager',
  'restaurant_manager',
  'restaurant_owner',
  'restaurant_purchaser',
  'permission_admin',
  'department_admin',
]);

export type RestaurantAgentEventType =
  | 'RUN_STARTED'
  | 'ROUTE_SELECTED'
  | 'PLAN_CREATED'
  | 'STEP_STARTED'
  | 'STEP_COMPLETED'
  | 'STEP_FAILED'
  | 'EVIDENCE_RECORDED'
  | 'EVIDENCE_GAP'
  | 'REPLAN'
  | 'CLARIFICATION'
  | 'CANCEL_REQUESTED'
  | 'BUDGET_EXCEEDED'
  | 'RUN_CANCELLED'
  | 'RUN_COMPLETED'
  | 'RUN_FAILED';

export type RestaurantAgentRunState =
  | 'RUNNING'
  | 'COMPLETED'
  | 'PARTIAL'
  | 'FAILED'
  | 'CANCELLED'
  | 'BUDGET_EXCEEDED';

export interface GrossMarginDeclineRunRequest {
  schemaVersion: '1.0';
  routeCode: typeof RESTAURANT_AGENT_RUN_ROUTE;
  startDate: string;
  endDate: string;
  storeTopN?: number;
  dishTopN?: number;
}

export interface RestaurantAgentEventV1 {
  schemaVersion: '1.0';
  runId: string;
  sequence: number;
  eventType: RestaurantAgentEventType;
  stepId: string | null;
  toolName: string | null;
  payload: Record<string, unknown>;
}

export interface RestaurantAgentClaim {
  statementCode: string;
  metric: string;
  value: string;
  unit: string | null;
  evidenceId: string;
  factId: string;
}

export interface RestaurantAgentEvidenceReference {
  evidenceId: string;
  factId: string;
}

export interface RestaurantAgentActionProposal {
  proposalCode: string;
  actionCode: string;
  rationaleCodes: string[];
  evidenceReferences: RestaurantAgentEvidenceReference[];
  executionMode: 'READ_ONLY_PROPOSAL';
}

export interface RestaurantAgentEvidenceFactReference {
  factId: string;
  metric: string;
  value: string;
  unit: string | null;
  dimensions: Record<string, string>;
  provenanceRefs: string[];
}

export interface RestaurantAgentEvidenceProvenance {
  refId: string;
  sourceType: string;
  asset: string;
  queryId: string;
  sourceVersion: string;
}

export interface RestaurantAgentEvidenceDrilldown {
  evidenceId: string;
  evidenceStatus: string;
  factReferences: RestaurantAgentEvidenceFactReference[];
  provenance: RestaurantAgentEvidenceProvenance[];
  warningCodes: string[];
  drilldownTruncated: boolean;
  toolName: string | null;
}

export interface RestaurantAgentTerminalOutcome {
  status: 'COMPLETE' | 'PARTIAL' | 'NOT_COMPUTABLE' | 'FAILED' | 'CANCELLED' | 'BUDGET_EXCEEDED';
  routeCode: typeof RESTAURANT_AGENT_RUN_ROUTE;
  claims: RestaurantAgentClaim[];
  blockers: string[];
  observations: string[];
  actionProposals: RestaurantAgentActionProposal[];
  attributionSupported: boolean;
}

export interface RestaurantAgentRunCancelResponse {
  schemaVersion: '1.0';
  runId: string;
  result: 'REQUESTED' | 'ALREADY_REQUESTED' | 'ALREADY_TERMINAL';
  state: RestaurantAgentRunState;
  nextEventSequence: number;
}

export interface RestaurantAgentRunReplayV1 {
  schemaVersion: '1.0';
  runId: string;
  state: RestaurantAgentRunState;
  routeCode: typeof RESTAURANT_AGENT_RUN_ROUTE;
  nextEventSequence: number;
  events: RestaurantAgentEventV1[];
  terminalOutcome: RestaurantAgentTerminalOutcome | null;
  failureCode: string | null;
}

const EVENT_TYPES: ReadonlySet<string> = new Set<RestaurantAgentEventType>([
  'RUN_STARTED', 'ROUTE_SELECTED', 'PLAN_CREATED', 'STEP_STARTED', 'STEP_COMPLETED',
  'STEP_FAILED', 'EVIDENCE_RECORDED', 'EVIDENCE_GAP', 'REPLAN', 'CLARIFICATION',
  'CANCEL_REQUESTED', 'BUDGET_EXCEEDED', 'RUN_CANCELLED', 'RUN_COMPLETED', 'RUN_FAILED',
]);
const RUN_STATES: ReadonlySet<string> = new Set<RestaurantAgentRunState>([
  'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED', 'BUDGET_EXCEEDED',
]);
const RUN_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string';
}

export function isRestaurantAgentRunId(value: unknown): value is string {
  return typeof value === 'string' && RUN_ID_PATTERN.test(value);
}

export function parseRestaurantAgentEventV1(value: unknown): RestaurantAgentEventV1 {
  if (
    !isRecord(value) || value.schemaVersion !== '1.0' || !isRestaurantAgentRunId(value.runId)
    || !Number.isSafeInteger(value.sequence) || (value.sequence as number) < 1
    || typeof value.eventType !== 'string' || !EVENT_TYPES.has(value.eventType)
    || !isNullableString(value.stepId) || !isNullableString(value.toolName) || !isRecord(value.payload)
  ) {
    throw new Error('RESTAURANT_AGENT_EVENT_CONTRACT_INVALID');
  }
  const event = value as unknown as RestaurantAgentEventV1;
  if (event.eventType === 'EVIDENCE_RECORDED') parseRestaurantAgentEvidenceDrilldown(event);
  return event;
}

function parseClaim(value: unknown): RestaurantAgentClaim {
  if (
    !isRecord(value) || typeof value.statementCode !== 'string' || typeof value.metric !== 'string'
    || typeof value.value !== 'string' || !isNullableString(value.unit)
    || typeof value.evidenceId !== 'string' || typeof value.factId !== 'string'
  ) throw new Error('RESTAURANT_AGENT_OUTCOME_CONTRACT_INVALID');
  return value as unknown as RestaurantAgentClaim;
}

function parseStringArray(value: unknown): string[] {
  if (!Array.isArray(value) || !value.every((item) => typeof item === 'string')) {
    throw new Error('RESTAURANT_AGENT_OUTCOME_CONTRACT_INVALID');
  }
  return value;
}

function parseEvidenceReference(value: unknown): RestaurantAgentEvidenceReference {
  if (!isRecord(value) || typeof value.evidenceId !== 'string' || typeof value.factId !== 'string') {
    throw new Error('RESTAURANT_AGENT_OUTCOME_CONTRACT_INVALID');
  }
  return { evidenceId: value.evidenceId, factId: value.factId };
}

function parseActionProposal(value: unknown): RestaurantAgentActionProposal {
  if (
    !isRecord(value) || typeof value.proposalCode !== 'string' || typeof value.actionCode !== 'string'
    || value.executionMode !== 'READ_ONLY_PROPOSAL' || !Array.isArray(value.evidenceReferences)
  ) throw new Error('RESTAURANT_AGENT_OUTCOME_CONTRACT_INVALID');
  return {
    proposalCode: value.proposalCode,
    actionCode: value.actionCode,
    rationaleCodes: parseStringArray(value.rationaleCodes),
    evidenceReferences: value.evidenceReferences.map(parseEvidenceReference),
    executionMode: 'READ_ONLY_PROPOSAL',
  };
}

function parseTerminalOutcome(value: unknown): RestaurantAgentTerminalOutcome | null {
  if (value === null) return null;
  if (
    !isRecord(value)
    || !['COMPLETE', 'PARTIAL', 'NOT_COMPUTABLE', 'FAILED', 'CANCELLED', 'BUDGET_EXCEEDED'].includes(String(value.status))
    || value.routeCode !== RESTAURANT_AGENT_RUN_ROUTE || !Array.isArray(value.claims)
    || typeof value.attributionSupported !== 'boolean'
  ) throw new Error('RESTAURANT_AGENT_OUTCOME_CONTRACT_INVALID');
  return {
    status: value.status as RestaurantAgentTerminalOutcome['status'],
    routeCode: RESTAURANT_AGENT_RUN_ROUTE,
    claims: value.claims.map(parseClaim),
    blockers: parseStringArray(value.blockers),
    observations: parseStringArray(value.observations),
    actionProposals: Array.isArray(value.actionProposals)
      ? value.actionProposals.map(parseActionProposal)
      : [],
    attributionSupported: value.attributionSupported,
  };
}

export function parseRestaurantAgentEvidenceDrilldown(
  event: RestaurantAgentEventV1,
): RestaurantAgentEvidenceDrilldown | null {
  if (event.eventType !== 'EVIDENCE_RECORDED') return null;
  const value = event.payload;
  if (
    typeof value.evidenceId !== 'string' || typeof value.evidenceStatus !== 'string'
    || !Array.isArray(value.factReferences) || !Array.isArray(value.provenance)
    || !Array.isArray(value.warningCodes) || typeof value.drilldownTruncated !== 'boolean'
  ) throw new Error('RESTAURANT_AGENT_EVIDENCE_CONTRACT_INVALID');
  const factReferences = value.factReferences.map((item): RestaurantAgentEvidenceFactReference => {
    if (
      !isRecord(item) || typeof item.factId !== 'string' || typeof item.metric !== 'string'
      || typeof item.value !== 'string' || !isNullableString(item.unit)
      || !isRecord(item.dimensions) || !Array.isArray(item.provenanceRefs)
      || !item.provenanceRefs.every((reference) => typeof reference === 'string')
      || !Object.values(item.dimensions).every((dimension) => typeof dimension === 'string')
    ) throw new Error('RESTAURANT_AGENT_EVIDENCE_CONTRACT_INVALID');
    return {
      factId: item.factId,
      metric: item.metric,
      value: item.value,
      unit: item.unit,
      dimensions: item.dimensions as Record<string, string>,
      provenanceRefs: item.provenanceRefs as string[],
    };
  });
  const provenance = value.provenance.map((item): RestaurantAgentEvidenceProvenance => {
    if (
      !isRecord(item) || typeof item.refId !== 'string' || typeof item.sourceType !== 'string'
      || typeof item.asset !== 'string' || typeof item.queryId !== 'string'
      || typeof item.sourceVersion !== 'string'
    ) throw new Error('RESTAURANT_AGENT_EVIDENCE_CONTRACT_INVALID');
    return item as unknown as RestaurantAgentEvidenceProvenance;
  });
  return {
    evidenceId: value.evidenceId,
    evidenceStatus: value.evidenceStatus,
    factReferences,
    provenance,
    warningCodes: parseStringArray(value.warningCodes),
    drilldownTruncated: value.drilldownTruncated,
    toolName: event.toolName,
  };
}

export function parseRestaurantAgentRunCancelResponse(
  value: unknown,
): RestaurantAgentRunCancelResponse {
  if (
    !isRecord(value) || value.schemaVersion !== '1.0' || !isRestaurantAgentRunId(value.runId)
    || !['REQUESTED', 'ALREADY_REQUESTED', 'ALREADY_TERMINAL'].includes(String(value.result))
    || typeof value.state !== 'string' || !RUN_STATES.has(value.state)
    || !Number.isSafeInteger(value.nextEventSequence) || (value.nextEventSequence as number) < 0
  ) throw new Error('RESTAURANT_AGENT_CANCEL_CONTRACT_INVALID');
  return value as unknown as RestaurantAgentRunCancelResponse;
}

export function parseRestaurantAgentRunReplayV1(value: unknown): RestaurantAgentRunReplayV1 {
  if (
    !isRecord(value) || value.schemaVersion !== '1.0' || !isRestaurantAgentRunId(value.runId)
    || typeof value.state !== 'string' || !RUN_STATES.has(value.state)
    || value.routeCode !== RESTAURANT_AGENT_RUN_ROUTE
    || !Number.isSafeInteger(value.nextEventSequence) || (value.nextEventSequence as number) < 0
    || !Array.isArray(value.events) || !isNullableString(value.failureCode)
  ) throw new Error('RESTAURANT_AGENT_REPLAY_CONTRACT_INVALID');
  const events = value.events.map(parseRestaurantAgentEventV1);
  let previousSequence = 0;
  for (const event of events) {
    if (event.runId !== value.runId || event.sequence <= previousSequence) {
      throw new Error('RESTAURANT_AGENT_REPLAY_CONTRACT_INVALID');
    }
    previousSequence = event.sequence;
  }
  if ((value.nextEventSequence as number) < previousSequence) {
    throw new Error('RESTAURANT_AGENT_REPLAY_CONTRACT_INVALID');
  }
  return {
    schemaVersion: '1.0',
    runId: value.runId,
    state: value.state as RestaurantAgentRunState,
    routeCode: RESTAURANT_AGENT_RUN_ROUTE,
    nextEventSequence: value.nextEventSequence as number,
    events,
    terminalOutcome: parseTerminalOutcome(value.terminalOutcome),
    failureCode: value.failureCode,
  };
}
