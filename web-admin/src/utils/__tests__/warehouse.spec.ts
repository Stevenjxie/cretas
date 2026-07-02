/**
 * D1 UI label policy unit tests.
 *
 * Verifies the warehouse code → customer-facing display label mapping
 * defined in `utils/warehouse.ts`. Customer transcript (PR #346 audit)
 * uses 线边仓 / 总仓; internal DB / Java code stays WH-WKS / WH-LOG.
 *
 * 2026-07-02 (LIUSHANMEN "同仓库多名字" fix): `warehouseDisplayName` priority
 * flipped — DB `name` is now authoritative and wins over the hardcoded
 * WH-WKS/WH-LOG label (previously the hardcoded label always won, which hid
 * customer-renamed warehouses). `warehouseDisplayLabel` is now documented as
 * a fallback-only helper. Added `warehouseNameByCode` for code-only call
 * sites that resolve against a loaded warehouse list.
 */
import { describe, it, expect } from 'vitest';
import { warehouseDisplayLabel, warehouseDisplayName, warehouseNameByCode } from '../warehouse';

describe('warehouseDisplayLabel (legacy fallback-only)', () => {
  it('maps WH-WKS to 线边仓 (factory-side daily clear)', () => {
    expect(warehouseDisplayLabel('WH-WKS')).toBe('线边仓');
  });

  it('maps WH-LOG to 总仓 (persistent inventory)', () => {
    expect(warehouseDisplayLabel('WH-LOG')).toBe('总仓');
  });

  it('surfaces unknown codes verbatim (forward-compat fallback)', () => {
    expect(warehouseDisplayLabel('WH-FROZEN')).toBe('WH-FROZEN');
  });

  it('returns "-" for null / undefined / empty', () => {
    expect(warehouseDisplayLabel(null)).toBe('-');
    expect(warehouseDisplayLabel(undefined)).toBe('-');
    expect(warehouseDisplayLabel('')).toBe('-');
  });
});

describe('warehouseDisplayName (DB name authoritative)', () => {
  it('prefers server-provided DB name over the hardcoded label for known codes', () => {
    // 2026-07-02 fix: a customer may rename WH-LOG's DB name away from the
    // default (e.g. LIUSHANMEN renamed it to "外仓"). The UI must show
    // whatever the customer configured, not a hardcoded "总仓".
    expect(warehouseDisplayName('鲜棉仓', 'WH-WKS')).toBe('鲜棉仓');
    expect(warehouseDisplayName('外仓', 'WH-LOG')).toBe('外仓');
  });

  it('uses server name when code is unknown', () => {
    expect(warehouseDisplayName('冷冻仓 A', 'WH-FROZEN-A')).toBe('冷冻仓 A');
  });

  it('falls back to hardcoded label when name is missing but code is known (WH-WKS/WH-LOG)', () => {
    expect(warehouseDisplayName(null, 'WH-WKS')).toBe('线边仓');
    expect(warehouseDisplayName('', 'WH-LOG')).toBe('总仓');
    expect(warehouseDisplayName(undefined, 'WH-LOG')).toBe('总仓');
  });

  it('uses raw code when name is missing and code is unknown/non-mapped', () => {
    expect(warehouseDisplayName(null, 'WH-FROZEN-A')).toBe('WH-FROZEN-A');
  });

  it('returns "-" when both name and code are missing', () => {
    expect(warehouseDisplayName(null, null)).toBe('-');
    expect(warehouseDisplayName(undefined, undefined)).toBe('-');
    expect(warehouseDisplayName('', '')).toBe('-');
  });
});

describe('warehouseNameByCode (resolve against a loaded warehouse list)', () => {
  const warehouses = [
    { code: 'WH-LOG', name: '外仓' }, // LIUSHANMEN-style customer rename
    { code: 'WH-WKS', name: '线边仓' },
    { code: 'WH-COLD', name: '' }, // loaded record but no name set
  ];

  it('resolves DB name from the loaded list when the code matches', () => {
    expect(warehouseNameByCode(warehouses, 'WH-LOG')).toBe('外仓');
    expect(warehouseNameByCode(warehouses, 'WH-WKS')).toBe('线边仓');
  });

  it('falls back to the hardcoded label when the matched record has no name', () => {
    expect(warehouseNameByCode(warehouses, 'WH-COLD')).toBe('WH-COLD');
  });

  it('falls back to the hardcoded label when the code is not found in the list (e.g. not loaded yet)', () => {
    expect(warehouseNameByCode(warehouses, 'WH-LOG')).toBe('外仓');
    expect(warehouseNameByCode([], 'WH-LOG')).toBe('总仓');
    expect(warehouseNameByCode(null, 'WH-WKS')).toBe('线边仓');
    expect(warehouseNameByCode(undefined, 'WH-FROZEN-A')).toBe('WH-FROZEN-A');
  });

  it('returns "-" when code is missing', () => {
    expect(warehouseNameByCode(warehouses, null)).toBe('-');
    expect(warehouseNameByCode(warehouses, undefined)).toBe('-');
    expect(warehouseNameByCode(warehouses, '')).toBe('-');
  });
});
