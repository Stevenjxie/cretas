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

export interface RestaurantAgentTerminalOutcome {
  status: 'COMPLETE' | 'PARTIAL' | 'NOT_COMPUTABLE' | 'FAILED' | 'CANCELLED' | 'BUDGET_EXCEEDED';
  routeCode: typeof RESTAURANT_AGENT_RUN_ROUTE;
  claims: RestaurantAgentClaim[];
  blockers: string[];
  observations: string[];
  attributionSupported: boolean;
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
  'STEP_FAILED', 'BUDGET_EXCEEDED', 'RUN_CANCELLED', 'RUN_COMPLETED', 'RUN_FAILED',
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
  return value as unknown as RestaurantAgentEventV1;
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
    attributionSupported: value.attributionSupported,
  };
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
