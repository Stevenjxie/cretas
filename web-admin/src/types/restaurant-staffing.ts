export type StaffingHorizon = 'tomorrow' | 'week' | 'month';

export interface StaffingSource {
  source: string;
  isSimulated: boolean;
  updatedAt: string | null;
  eventCount?: number;
}

export interface StaffingLiveStreamMinute {
  minute: string;
  eventCount: number;
  guestCount: number;
}

export interface StaffingLiveStreamEvent {
  externalRef: string;
  storeId: number;
  storeName: string;
  reservationDate: string;
  daypart: string;
  tableCount: number;
  guestCount: number;
  status: string;
  source: string;
  isSimulated: boolean;
  sourceUpdatedAt: string;
}

export interface StaffingLiveStream {
  windowMinutes: number;
  pollIntervalSeconds: number;
  eventCount: number;
  guestCount: number;
  latestEventAt: string | null;
  minuteBuckets: StaffingLiveStreamMinute[];
  recentEvents: StaffingLiveStreamEvent[];
}

export interface StaffingRolePlan {
  roleCode: string;
  roleName: string;
  requiredSkill: string;
  shiftHours: number;
  targetGuestsPerLaborHour: number;
  minimumStaff: number;
  currentStaff: number;
  availableSkilledStaff: number;
  recommendedStaff: number;
  gap: number;
  skillGap: number;
  maxHoursPerPersonWeek: number;
  policySource: string;
  policyIsSimulated: boolean;
  policyVersion: number;
  planFingerprint: string;
  adjustedStaff: number | null;
  effectiveStaff: number;
  adjustmentId: number | null;
  adjustedAt: string | null;
}

export interface StaffingDailyRow {
  date: string;
  storeId: number;
  storeName: string;
  daypart: string;
  reservedGuests: number;
  activeReservedGuests: number;
  weightedReservedGuests: number;
  reservedTables: number;
  reservationOrders?: number;
  predictedGuests: number;
  baselineGuests: number | null;
  reservationImpliedGuests: number;
  reservationCoverage: number;
  reservationCoveragePct: number;
  confidence: number;
  confidencePct: number;
  confidenceLabel: string;
  currentStaff: number;
  effectiveStaff: number;
  recommendedStaff: number;
  gap: number;
  positiveGap: number;
  skillGap: number;
  roles: StaffingRolePlan[];
}

export interface StaffingSummaryRow {
  storeId: number;
  storeName: string;
  daypart: string;
  serviceDays: number;
  predictedGuests: number;
  avgDailyPredictedGuests: number;
  peakDailyGuests: number;
  reservedGuests: number;
  reservedTables: number;
  reservationOrders?: number;
  reservationCoveragePct: number;
  recommendedStaff: number;
  currentStaff: number;
  gap: number;
  positiveGap: number;
  skillGap: number;
  confidencePct: number;
  partTimePeople: number;
  partTimeShiftHours: number;
  weeklyCapacityGapHours: number;
  workHourRule: 'daily_concurrency_plus_skill_and_weekly_hour_caps';
  evidenceLabel: string;
  trend7Vs30Pct: number | null;
  trend30Vs365Pct: number | null;
  historicalProductivity: Array<Record<string, unknown>>;
  trends: {
    guestTraffic: Record<string, number | null>;
    posOrders: Record<string, number | null>;
    historicalProductivity: Record<string, number | string | null>;
  };
}

export interface StaffingSummary {
  predictedGuests: number;
  reservedGuests: number;
  reservationOrders?: number;
  reservationCoveragePct: number;
  recommendedStaff: number;
  currentStaff: number;
  positiveGap: number;
  partTimePeople: number;
  confidencePct: number;
  storeCount: number;
}

export interface StaffingDashboard {
  factoryId: string;
  horizon: StaffingHorizon;
  horizonLabel: string;
  windowStart: string;
  windowEnd: string;
  generatedAt: string;
  asOf: string;
  numericSource: 'forecast_factbook_only';
  historicalProductivityRule: 'evidence_only_not_gap_input';
  sources: StaffingSource[];
  liveStream?: StaffingLiveStream;
  summary: StaffingSummary;
  summaryRows: StaffingSummaryRow[];
  dailyRows: StaffingDailyRow[];
}

export interface StaffingAdjustmentPayload {
  storeId: number;
  targetDate: string;
  daypart: '午市' | '下午茶' | '晚市' | '夜宵';
  roleCode: string;
  predictedGuests: number;
  policyVersion: number;
  priorStaff: number;
  recommendedStaff: number;
  adjustedStaff: number;
  planFingerprint: string;
  reason: string;
  idempotencyKey: string;
}

export interface StaffingAdjustmentReceipt {
  adjustmentId: number;
  createdAt: string;
  businessWrite: boolean;
  idempotentReplay: boolean;
  factoryId: string;
  storeId: number;
  targetDate: string;
  daypart: string;
  roleCode: string;
  predictedGuests: number;
  policyVersion: number;
  recommendedStaff: number;
  priorStaff: number;
  adjustedStaff: number;
}
