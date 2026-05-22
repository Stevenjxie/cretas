import { describe, it, expect } from 'vitest';
import {
  INLINE_ICONS,
  computeInlineIconStates,
  type InlineIconId,
} from '../inlineIcons';
import type { RowAction } from '../rowActions';

describe('INLINE_ICONS catalog', () => {
  // 2026-05-18 (PR #859): print-pdf removed (duplicate of top-row PDF button).
  // forward + audit originally marked pending; audit un-pended in PR #861,
  // forward un-pended in PR #872 — ForwardShareDialog + ShareLinkController live.
  it('exposes 6 icons in canonical order (print-pdf removed)', () => {
    expect(INLINE_ICONS.map((d) => d.id)).toEqual([
      'copy',
      'mark',
      'lock',
      'forward',
      'delete',
      'audit',
    ]);
  });

  it('marks 3 ids as inline-only (mark/forward/audit)', () => {
    const inlineOnly = INLINE_ICONS.filter((d) => d.inlineOnly).map((d) => d.id);
    expect(inlineOnly.sort()).toEqual(['audit', 'forward', 'mark']);
  });

  it('flags delete as danger + requiresConfirm', () => {
    const del = INLINE_ICONS.find((d) => d.id === 'delete')!;
    expect(del.danger).toBe(true);
    expect(del.requiresConfirm).toBe(true);
  });

  it('flags no static icons as pending (audit shipped #861, forward shipped #872)', () => {
    const pending = INLINE_ICONS.filter((d) => d.pending).map((d) => d.id);
    expect(pending).toEqual([]);
  });
});

describe('computeInlineIconStates', () => {
  function makeAction(id: string, disabled = false): RowAction {
    return { id, icon: '?', label: id, disabled };
  }

  it('always enables 3 inline-only ids regardless of rowActions', () => {
    const states = computeInlineIconStates([]);
    const inlineIds: InlineIconId[] = ['mark', 'forward', 'audit'];
    for (const id of inlineIds) {
      const s = states.find((x) => x.def.id === id)!;
      expect(s.enabled).toBe(true);
    }
  });

  it('disables known ids missing from rowActions', () => {
    const states = computeInlineIconStates([makeAction('copy')]);
    const copy = states.find((x) => x.def.id === 'copy')!;
    const del = states.find((x) => x.def.id === 'delete')!;
    expect(copy.enabled).toBe(true);
    expect(del.enabled).toBe(false);
    expect(del.disabledReason).toBe('当前状态不允许此操作');
  });

  it('disables known id when rowActions flagged disabled', () => {
    const states = computeInlineIconStates([makeAction('copy', true)]);
    const copy = states.find((x) => x.def.id === 'copy')!;
    expect(copy.enabled).toBe(false);
  });

  it('preserves canonical 6-icon order', () => {
    const states = computeInlineIconStates([]);
    expect(states.map((s) => s.def.id)).toEqual([
      'copy',
      'mark',
      'lock',
      'forward',
      'delete',
      'audit',
    ]);
  });

  it('propagates pending flag from INLINE_ICONS defs (no static pending after PR #872)', () => {
    const states = computeInlineIconStates([]);
    const fwd = states.find((s) => s.def.id === 'forward')!;
    const aud = states.find((s) => s.def.id === 'audit')!;
    const cpy = states.find((s) => s.def.id === 'copy')!;
    expect(fwd.pending).toBe(false); // un-pended in PR #872 — ForwardShareDialog live
    expect(aud.pending).toBe(false); // un-pended in PR #861 — AuditLogDrawer live
    expect(cpy.pending).toBe(false);
  });

  it('propagates pending flag from RowAction.pending (copy via COMMON_ACTIONS)', () => {
    // COMMON_ACTIONS.COPY is now marked pending; simulate the action arriving
    // from useRowActions with pending=true.
    const states = computeInlineIconStates([
      { id: 'copy', icon: '', label: '复制', pending: true } as RowAction,
    ]);
    const cpy = states.find((s) => s.def.id === 'copy')!;
    expect(cpy.enabled).toBe(true);
    expect(cpy.pending).toBe(true);
  });
});
