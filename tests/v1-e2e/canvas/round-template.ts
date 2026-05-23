/**
 * round-template.ts — generic 20-round generator per Canvas Tab.
 *
 * Each Tab spec calls `defineTabRounds(opts)` which produces a list of 20 round
 * definitions. The spec then iterates with `test.each` to materialise rounds
 * into Playwright `test()` calls.
 *
 * Round shape:
 *   - 5 rounds CRUD lifecycle (round 01-05): create / read / update / disable /
 *     soft-delete-recreate
 *   - 5 rounds config dimension (round 06-10): Tab-specific variants. Spec
 *     supplies the list of variant payloads (e.g. 5 alertType / 5 strategyType /
 *     5 cron expressions) and each becomes a round.
 *   - 5 rounds boundary/security (round 11-15): length 256 / numeric overflow /
 *     date leap / version stale (AUD-4) / SpEL injection (T()/new/@bean) where
 *     applicable. Spec opts in via flags.
 *   - 5 rounds real-scenario wire-through (round 16-20): toggle on/off cycles,
 *     re-read after cache TTL, target list still consistent, control entity
 *     untouched.
 *
 * Tab spec MUST supply an `apiAdapter` that maps tab-specific shape to the
 * generic CRUD interface used here. See per-tab spec files for examples.
 */

import { APIRequestContext, expect } from '@playwright/test';
import { callApi, roundName, sleep, E2E_PREFIX } from './canvas-base';
import {
  axis1ModifyAccepted,
  axis2TargetUsesNew,
  axis3NoRegression,
  axis4FourInOneUx,
  composeReport,
  FourAxisReport,
} from './four-axis-helper';

// ─── Tab adapter interface ────────────────────────────────────────────────────

export interface TabAdapter {
  /** Short tab key, e.g. 'alerts', 'notify', 'pricing', 'cron'. */
  tabKey: string;

  /** Display label for the audit doc, e.g. "预警规则 (Alerts)". */
  tabLabel: string;

  /** GET list path under /api/mobile. */
  listPath: string;

  /** Build payload for create (round 01-05) from a generated name. */
  buildCreatePayload(name: string, variantIdx: number): unknown;

  /**
   * Build payload for update (round 02/03) from the current entity + name.
   *
   * NOTE: many Cretas entities have immutable identity fields (e.g. templateCode,
   * checkpointCode). The update payload should NOT change identityField — it
   * should change a mutable display field. updateVerifyField below tells the
   * runner which field to verify.
   */
  buildUpdatePayload(current: any, newName: string): unknown;

  /**
   * Field that the update is expected to modify. If absent, defaults to
   * identityField. For Tabs where identityField is immutable, point this
   * at a mutable field like 'description' / 'title' / 'ruleName'.
   */
  updateVerifyField?: string;
  /** Generator producing the expected updated value (given the round idx). */
  updateExpectedValue?: (current: any, roundIdx: number) => unknown;

  /** POST create path under /api/mobile. */
  createPath: string;

  /** PUT update path (with {id} placeholder). */
  updatePath(id: string): string;

  /** DELETE path (with {id} placeholder). */
  deletePath(id: string): string;

  /**
   * Variant rounds 06-10: list of 5 payload mutators applied to a base name.
   * Each must produce a distinct entity (different code/type/strategy).
   */
  variantPayloads(baseName: (suffix: string) => string): unknown[];

  /**
   * Boundary rounds 11-15: list of 5 boundary payloads, each expected to fail
   * 4xx. Each entry is {label, payload, expectFailureField?}. If absent,
   * we accept any 4xx with specific message.
   */
  boundaryPayloads(baseName: (suffix: string) => string): Array<{
    label: string;
    payload: unknown;
  }>;

  /**
   * Field name used to identify entities for cleanup (where this tab puts the
   * test prefix). Defaults to 'ruleName'.
   */
  identityField?: string;

  /** How to extract the id from a list item (defaults to .id). */
  extractId?(item: any): string;

  /** Whether this tab supports toggle (round 16-20 wire-through). */
  supportsToggle?: boolean;

  /** Path to toggle endpoint, with {id} placeholder. */
  togglePath?(id: string): string;
}

// ─── Round definition ─────────────────────────────────────────────────────────

export interface RoundDef {
  idx: number;
  label: string;
  /**
   * Runner takes a Playwright APIRequestContext + the suite-scoped control
   * snapshot, runs the round, and returns a four-axis report.
   */
  run(
    request: APIRequestContext,
    controlSnap: { id: string; nameField: string; nameValue: string } | null,
  ): Promise<FourAxisReport>;
}

// ─── 20-round generator ───────────────────────────────────────────────────────

export function defineTabRounds(adapter: TabAdapter): RoundDef[] {
  const identityField = adapter.identityField || 'ruleName';
  const extractId = adapter.extractId || ((i: any) => i.id);
  const rounds: RoundDef[] = [];

  // ──────────────── Rounds 01-05: CRUD lifecycle ────────────────

  // Round 01: create
  rounds.push({
    idx: 1,
    label: 'CRUD-CREATE: create an entity, refetch confirms persistence',
    async run(request, control) {
      const name = roundName(adapter.tabKey, 1);
      const create = await callApi(
        request,
        'POST',
        adapter.createPath,
        adapter.buildCreatePayload(name, 0),
      );
      expect(create.status, `create status=${create.status}`).toBeGreaterThanOrEqual(200);
      expect(create.status).toBeLessThan(300);

      // re-list to confirm. Case-insensitive match (some endpoints lowercase the code).
      const list = await callApi(request, 'GET', adapter.listPath);
      const items = listItems(list.body);
      const wantLc = name.toLowerCase();
      const found = items.find((x) => String(x[identityField] || '').toLowerCase() === wantLc);
      if (!found) {
        return composeReport({
          axis1: {
            passed: false,
            detail: `Created ${name} but not found in re-list.`,
          },
        });
      }
      // Validate the entity's stored identity matches what we created (modulo case)
      const a1 = {
        passed: create.status >= 200 && create.status < 300,
        detail: `created status=${create.status}, found entity ${identityField}=${found[identityField]}`,
      };
      const a2 = await axis2TargetUsesNew(
        request,
        adapter.listPath,
        extractId(found),
        extractId,
      );
      const a3 = await axis3NoRegression(request, adapter.listPath, control, extractId);
      return composeReport({ axis1: a1, axis2: a2, axis3: a3 });
    },
  });

  // Round 02: read (list contains the new entity, GET-by-id when supported)
  rounds.push({
    idx: 2,
    label: 'CRUD-READ: list + spot read of created entity',
    async run(request, control) {
      // Find any E2E_R_ entity (from round 01 or seeded earlier this suite)
      const list = await callApi(request, 'GET', adapter.listPath);
      const items = listItems(list.body);
      const e2eItem = items.find((x) =>
        String(x[identityField] || '').toLowerCase().startsWith(E2E_PREFIX.toLowerCase()),
      );
      if (!e2eItem) {
        // No E2E entity exists yet — bootstrap one
        const name = roundName(adapter.tabKey, 2);
        const r = await callApi(
          request,
          'POST',
          adapter.createPath,
          adapter.buildCreatePayload(name, 0),
        );
        expect(r.status).toBeLessThan(300);
        return composeReport({
          axis1: { passed: r.status < 300, detail: `bootstrapped ${name}` },
        });
      }
      const a3 = await axis3NoRegression(request, adapter.listPath, control, extractId);
      return composeReport({
        axis1: { passed: true, detail: `read OK, found ${e2eItem[identityField]}` },
        axis3: a3,
      });
    },
  });

  // Round 03: update — modify an E2E entity
  rounds.push({
    idx: 3,
    label: 'CRUD-UPDATE: PATCH/PUT modifies an E2E entity',
    async run(request, control) {
      const list = await callApi(request, 'GET', adapter.listPath);
      const items = listItems(list.body);
      let target = items.find((x) =>
        String(x[identityField] || '').toLowerCase().startsWith(E2E_PREFIX.toLowerCase()),
      );
      if (!target) {
        const name = roundName(adapter.tabKey, 3);
        const r = await callApi(
          request,
          'POST',
          adapter.createPath,
          adapter.buildCreatePayload(name, 0),
        );
        if (r.status >= 300) {
          return composeReport({
            axis1: { passed: false, detail: `setup-create failed: ${r.status}` },
          });
        }
        // re-list
        const l2 = await callApi(request, 'GET', adapter.listPath);
        const wantLc2 = name.toLowerCase();
        target = listItems(l2.body).find(
          (x) => String(x[identityField] || '').toLowerCase() === wantLc2,
        );
      }
      if (!target) {
        return composeReport({
          axis1: { passed: false, detail: 'no E2E target to update' },
        });
      }
      const id = extractId(target);
      const newName = roundName(adapter.tabKey, 3) + '_upd';
      const updPayload = adapter.buildUpdatePayload(target, newName);
      const upd = await callApi(
        request,
        'PUT',
        adapter.updatePath(id),
        updPayload,
      );
      // Some Tabs use PATCH; if 405, retry
      let updStatus = upd.status;
      if (updStatus === 405) {
        const r2 = await callApi(
          request,
          'PATCH',
          adapter.updatePath(id),
          updPayload,
        );
        updStatus = r2.status;
      }
      const refetch = await callApi(request, 'GET', adapter.listPath);
      const found = listItems(refetch.body).find((x) => extractId(x) === id);
      const verifyField = adapter.updateVerifyField || identityField;
      // Derive expected value from the payload (mutable-field-aware)
      const expected =
        adapter.updateExpectedValue
          ? adapter.updateExpectedValue(target, 3)
          : (updPayload as any)[verifyField];
      const a1 = axis1ModifyAccepted(updStatus, found, verifyField, expected);
      const a3 = await axis3NoRegression(request, adapter.listPath, control, extractId);
      return composeReport({ axis1: a1, axis3: a3 });
    },
  });

  // Round 04: disable / toggle off (if supported) OR just verify update side-effect
  rounds.push({
    idx: 4,
    label: 'CRUD-DISABLE: toggle enabled=false or update flag',
    async run(request, control) {
      const list = await callApi(request, 'GET', adapter.listPath);
      const items = listItems(list.body);
      const target = items.find((x) =>
        String(x[identityField] || '').toLowerCase().startsWith(E2E_PREFIX.toLowerCase()),
      );
      if (!target) {
        return composeReport({
          axis1: { passed: false, detail: 'no E2E target to disable' },
        });
      }
      const id = extractId(target);
      let toggleStatus = 200;
      if (adapter.supportsToggle && adapter.togglePath) {
        const t = await callApi(request, 'POST', adapter.togglePath(id));
        toggleStatus = t.status;
      } else {
        // Fall back: PUT with enabled=false
        const upd = await callApi(
          request,
          'PUT',
          adapter.updatePath(id),
          { ...adapter.buildUpdatePayload(target, target[identityField]), enabled: false },
        );
        toggleStatus = upd.status;
        if (toggleStatus === 405) {
          const r2 = await callApi(
            request,
            'PATCH',
            adapter.updatePath(id),
            { enabled: false },
          );
          toggleStatus = r2.status;
        }
      }
      const a1: { passed: boolean; detail: string } = {
        passed: toggleStatus < 300,
        detail: `toggle/disable status=${toggleStatus}`,
      };
      const a3 = await axis3NoRegression(request, adapter.listPath, control, extractId);
      return composeReport({ axis1: a1, axis3: a3 });
    },
  });

  // Round 05: soft-delete then re-create with same name (idempotency)
  rounds.push({
    idx: 5,
    label: 'CRUD-DELETE-AND-RECREATE: soft-delete then create with same name',
    async run(request, control) {
      const name = roundName(adapter.tabKey, 5);
      const c1 = await callApi(
        request,
        'POST',
        adapter.createPath,
        adapter.buildCreatePayload(name, 0),
      );
      if (c1.status >= 300) {
        return composeReport({
          axis1: { passed: false, detail: `setup-create failed ${c1.status}` },
        });
      }
      const l1 = await callApi(request, 'GET', adapter.listPath);
      const wantLc = name.toLowerCase();
      const target = listItems(l1.body).find(
        (x) => String(x[identityField] || '').toLowerCase() === wantLc,
      );
      if (!target) {
        return composeReport({
          axis1: { passed: false, detail: `created ${name} not in list` },
        });
      }
      const id = extractId(target);
      const del = await callApi(request, 'DELETE', adapter.deletePath(id));
      const c2 = await callApi(
        request,
        'POST',
        adapter.createPath,
        adapter.buildCreatePayload(name, 0),
      );
      const passed =
        del.status < 300 && (c2.status < 300 || c2.status === 409);
      return composeReport({
        axis1: {
          passed,
          detail: `delete=${del.status}, recreate=${c2.status}`,
        },
      });
    },
  });

  // ──────────────── Rounds 06-10: config dimension variants ────────────────

  const variants = adapter.variantPayloads((suf) =>
    `${E2E_PREFIX}${adapter.tabKey}_var_${suf}_${Date.now().toString(36).slice(-4)}`,
  );
  for (let v = 0; v < 5; v++) {
    const idx = 6 + v;
    const payload = variants[v];
    rounds.push({
      idx,
      label: `VARIANT-${v + 1}: config dimension variant`,
      async run(request, control) {
        if (!payload) {
          return composeReport({
            axis1: { passed: false, detail: `no variant ${v + 1} defined` },
          });
        }
        const c = await callApi(
          request,
          'POST',
          adapter.createPath,
          payload,
        );
        const a1: { passed: boolean; detail: string } = {
          passed: c.status >= 200 && c.status < 300,
          detail: `variant create status=${c.status}, msg=${c.body?.message || ''}`,
        };
        const a3 = await axis3NoRegression(request, adapter.listPath, control, extractId);
        return composeReport({ axis1: a1, axis3: a3 });
      },
    });
  }

  // ──────────────── Rounds 11-15: boundary / security ────────────────

  const boundaries = adapter.boundaryPayloads((suf) =>
    `${E2E_PREFIX}${adapter.tabKey}_bd_${suf}_${Date.now().toString(36).slice(-4)}`,
  );
  for (let b = 0; b < 5; b++) {
    const idx = 11 + b;
    const bd = boundaries[b];
    rounds.push({
      idx,
      label: `BOUNDARY-${b + 1}: ${bd?.label || 'placeholder'}`,
      async run(request) {
        if (!bd) {
          return composeReport({
            axis4: { passed: true, detail: `no boundary ${b + 1} defined; skipped` },
          });
        }
        const r = await callApi(request, 'POST', adapter.createPath, bd.payload);
        const a4 = axis4FourInOneUx(r.status, r.body);
        return composeReport({ axis4: a4 });
      },
    });
  }

  // ──────────────── Rounds 16-20: wire-through / toggle / no-regression ────────────────

  for (let w = 0; w < 5; w++) {
    const idx = 16 + w;
    rounds.push({
      idx,
      label: `WIRE-${w + 1}: toggle cycle + no-regression`,
      async run(request, control) {
        const list = await callApi(request, 'GET', adapter.listPath);
        const items = listItems(list.body);
        const target = items.find((x) =>
          String(x[identityField] || '').toLowerCase().startsWith(E2E_PREFIX.toLowerCase()),
        );
        if (!target) {
          // Create one then toggle
          const name = roundName(adapter.tabKey, idx);
          await callApi(
            request,
            'POST',
            adapter.createPath,
            adapter.buildCreatePayload(name, 0),
          );
        }
        // Re-fetch + cycle
        const l2 = await callApi(request, 'GET', adapter.listPath);
        const items2 = listItems(l2.body);
        const tgt = items2.find((x) =>
          String(x[identityField] || '').toLowerCase().startsWith(E2E_PREFIX.toLowerCase()),
        );
        let pass = !!tgt;
        let detail = `wire round ${w + 1}: ${tgt ? 'entity present' : 'no entity'}`;
        if (tgt && adapter.supportsToggle && adapter.togglePath) {
          const id = extractId(tgt);
          const t1 = await callApi(request, 'POST', adapter.togglePath(id));
          await sleep(100);
          const t2 = await callApi(request, 'POST', adapter.togglePath(id));
          pass = pass && t1.status < 300 && t2.status < 300;
          detail += `, toggle1=${t1.status}, toggle2=${t2.status}`;
        }
        const a3 = await axis3NoRegression(request, adapter.listPath, control, extractId);
        return composeReport({
          axis1: { passed: pass, detail },
          axis2: { passed: !!tgt, detail: `runtime list reflects entity` },
          axis3: a3,
        });
      },
    });
  }

  return rounds;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function listItems(body: any): any[] {
  if (Array.isArray(body?.data)) return body.data;
  if (Array.isArray(body?.data?.content)) return body.data.content;
  if (Array.isArray(body?.content)) return body.content;
  return [];
}

/**
 * Snapshot the first non-E2E (pre-existing real-world) entity as a control
 * for regression checks. Call at suite start.
 */
export async function snapshotControl(
  request: APIRequestContext,
  listPath: string,
  identityField: string,
  extractId: (item: any) => string = (i) => i.id,
): Promise<{ id: string; nameField: string; nameValue: string } | null> {
  const r = await callApi(request, 'GET', listPath);
  if (r.status !== 200) return null;
  const items = listItems(r.body);
  const pre = items.find((x) => !String(x[identityField] || '').toLowerCase().startsWith(E2E_PREFIX.toLowerCase()));
  if (!pre) return null;
  return {
    id: extractId(pre),
    nameField: identityField,
    nameValue: pre[identityField],
  };
}
