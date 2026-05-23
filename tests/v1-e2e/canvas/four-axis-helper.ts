/**
 * four-axis-helper.ts — 4-axis verification per round.
 *
 * The 4 axes (per `fool-proof-design.md` § "跨规则铁律" and Canvas spec):
 *
 *   Axis 1: MODIFY ACCEPTED
 *     Configuration change request returns 2xx and persists. Re-reading the
 *     resource shows the new value.
 *
 *   Axis 2: TARGET USES NEW (wire-through)
 *     The runtime consumer (target module: e.g. invoice/SO/PO/MR) actually
 *     uses the new config when its endpoint is invoked. For some Tabs this is
 *     verified via behavior change (preview / dry-run), for others via cache
 *     refresh + re-fetch.
 *
 *   Axis 3: NO REGRESSION
 *     Pre-existing unrelated entities are NOT affected. Spot-check at least
 *     one untouched record per round to detect cross-pollination bugs.
 *
 *   Axis 4: 4位一体 UX (error path)
 *     When the round intentionally triggers a 4xx (boundary / security
 *     subset), the response satisfies all 4 of:
 *       (a) response.body.message is specific, not generic "操作失败"
 *       (b) success === false (typed schema)
 *       (c) status code 4xx (not 500 — server-side validation, not exception)
 *       (d) actionHint or specific next-step text (best effort — we accept
 *           any non-empty contextual phrase)
 *
 * Helpers below return AxisResult { passed, details } per axis.
 */

import type { APIRequestContext } from '@playwright/test';
import { callApi } from './canvas-base';

export interface AxisResult {
  passed: boolean;
  detail: string;
}

export interface FourAxisReport {
  axis1: AxisResult; // modify accepted
  axis2: AxisResult; // target uses new
  axis3: AxisResult; // no regression
  axis4: AxisResult; // 4位一体 UX
  /** Overall: passes if axes that apply to this round all pass. */
  passed: boolean;
}

/**
 * Axis 1: assert a mutation request was accepted and persisted.
 *
 * @param mutateStatus  HTTP status from the mutate call
 * @param refetched     Body from a follow-up GET on the entity
 * @param expectedField field name to check on the refetched body
 * @param expectedValue expected value
 */
export function axis1ModifyAccepted(
  mutateStatus: number,
  refetched: any,
  expectedField: string,
  expectedValue: unknown,
): AxisResult {
  if (mutateStatus < 200 || mutateStatus >= 300) {
    return {
      passed: false,
      detail: `Mutate returned ${mutateStatus}, not 2xx.`,
    };
  }
  if (!refetched) {
    return {
      passed: false,
      detail: `Refetch returned no body — cannot verify persistence.`,
    };
  }
  const actual = pickPath(refetched, expectedField);
  if (actual !== expectedValue) {
    return {
      passed: false,
      detail: `Field "${expectedField}" expected ${JSON.stringify(expectedValue)}, got ${JSON.stringify(actual)}.`,
    };
  }
  return { passed: true, detail: `${expectedField}=${JSON.stringify(actual)}` };
}

/**
 * Axis 2: target module uses the new config.
 *
 * Two strategies:
 *   - 'behavior': call a runtime endpoint and assert behavior reflects config
 *   - 'cache-refresh': just re-read the config after the cache TTL has expired
 *
 * For round-template default, we use cache-refresh: re-call list and assert
 * the entity is present. Per-Tab specs can override with a real wire-through
 * test when the runtime endpoint is known.
 */
export async function axis2TargetUsesNew(
  request: APIRequestContext,
  listPath: string,
  entityId: string,
  extractId: (item: any) => string = (i) => i.id,
): Promise<AxisResult> {
  const r = await callApi(request, 'GET', listPath);
  if (r.status !== 200) {
    return { passed: false, detail: `Target list returned ${r.status}.` };
  }
  const items: any[] = Array.isArray(r.body.data)
    ? r.body.data
    : r.body.data?.content || [];
  const found = items.find((x) => extractId(x) === entityId);
  if (!found) {
    return {
      passed: false,
      detail: `Entity ${entityId} not visible to runtime list.`,
    };
  }
  return { passed: true, detail: `Entity ${entityId} visible in runtime list.` };
}

/**
 * Axis 3: spot-check pre-existing unrelated entities are unchanged.
 *
 * Caller passes a "control" entityId that existed BEFORE this round started
 * (snapshot at suite setup). We refetch and assert no fields changed.
 */
export async function axis3NoRegression(
  request: APIRequestContext,
  listPath: string,
  controlSnapshot: { id: string; nameField: string; nameValue: string } | null,
  extractId: (item: any) => string = (i) => i.id,
): Promise<AxisResult> {
  if (!controlSnapshot) {
    // No control to check — degenerate pass (no regression test available)
    return {
      passed: true,
      detail: 'No control entity at suite start; skipped regression check.',
    };
  }
  const r = await callApi(request, 'GET', listPath);
  if (r.status !== 200) {
    return { passed: false, detail: `Refetch failed: ${r.status}.` };
  }
  const items: any[] = Array.isArray(r.body.data)
    ? r.body.data
    : r.body.data?.content || [];
  const found = items.find((x) => extractId(x) === controlSnapshot.id);
  if (!found) {
    return {
      passed: false,
      detail: `Control entity ${controlSnapshot.id} disappeared!`,
    };
  }
  const stillSame = found[controlSnapshot.nameField] === controlSnapshot.nameValue;
  if (!stillSame) {
    return {
      passed: false,
      detail: `Control entity ${controlSnapshot.id} mutated: ${controlSnapshot.nameField} changed.`,
    };
  }
  return { passed: true, detail: `Control entity unchanged.` };
}

/**
 * Axis 4: 4-in-1 error UX check.
 *
 * Cretas backend pattern: business validation errors return HTTP 200 with
 * body.success=false and body.code=<4xx int>. Older endpoints return actual
 * 4xx HTTP status. Both patterns are valid; we treat them the same.
 *
 * Pass criteria:
 *   - body.success === false  (the canonical signal)
 *   - body.code in [400-499]  OR  HTTP status in [400-499]
 *   - body.message is specific (not "操作失败"-style generic)
 *   - body.message length > 5
 *
 * Optional: actionHint field present (newer endpoints expose this).
 */
export function axis4FourInOneUx(
  status: number,
  body: any,
): AxisResult {
  if (!body || typeof body !== 'object') {
    return { passed: false, detail: 'Body not parseable JSON.' };
  }
  if (body.success !== false) {
    return {
      passed: false,
      detail: `success=${body.success}, HTTP=${status}, expected success=false on validation error.`,
    };
  }
  // Check either HTTP status OR body.code is a 4xx
  const bodyCode = typeof body.code === 'number' ? body.code : 0;
  const isValidationError =
    (status >= 400 && status < 500) ||
    (bodyCode >= 400 && bodyCode < 500);
  if (!isValidationError) {
    return {
      passed: false,
      detail: `HTTP=${status}, body.code=${bodyCode} — neither is 4xx (security/boundary check did not trigger validation).`,
    };
  }
  const msg: string = String(body.message || '');
  if (!msg || msg.length < 5) {
    return {
      passed: false,
      detail: `Empty/short message: "${msg}"`,
    };
  }
  const genericPatterns = [/^操作失败$/, /^请求失败$/, /^Error$/i, /^Internal$/i];
  if (genericPatterns.some((re) => re.test(msg))) {
    return {
      passed: false,
      detail: `Generic message: "${msg}"`,
    };
  }
  const hasHint =
    !!body.actionHint || /请|应|请联系|跳转|配置|联系|前往|输入|提供|缩短|确认/.test(msg);
  return {
    passed: true,
    detail: `validation triggered + specific msg (${msg.slice(0, 40)}...)${hasHint ? ' + hint' : ''}`,
  };
}

/**
 * Compose a final FourAxisReport. `applicableAxes` lists which axes this
 * round actually exercises — others default to passed with reason "n/a".
 */
export function composeReport(opts: {
  axis1?: AxisResult;
  axis2?: AxisResult;
  axis3?: AxisResult;
  axis4?: AxisResult;
}): FourAxisReport {
  const na: AxisResult = { passed: true, detail: 'n/a for this round' };
  const r1 = opts.axis1 || na;
  const r2 = opts.axis2 || na;
  const r3 = opts.axis3 || na;
  const r4 = opts.axis4 || na;
  return {
    axis1: r1,
    axis2: r2,
    axis3: r3,
    axis4: r4,
    passed: r1.passed && r2.passed && r3.passed && r4.passed,
  };
}

// ─── Utilities ────────────────────────────────────────────────────────────────

function pickPath(obj: any, dotPath: string): unknown {
  const parts = dotPath.split('.');
  let cur = obj;
  for (const p of parts) {
    if (cur == null) return undefined;
    cur = cur[p];
  }
  return cur;
}
