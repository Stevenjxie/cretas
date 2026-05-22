// U-ICON-1 (Sprint 4 Wave 2 Chat L) — inline row action chips.
//
// 2026-05-18 redesign (PR #858 + #859):
//   - Was 7 emoji icons + tooltip; now plain text chips (per F006 admin feedback)
//   - 'print-pdf' removed — duplicates the dedicated PDF button at top of 操作 col
//   - 'forward' was marked `pending: true`; un-pended in PR #872 — backend
//     ShareLinkController + V20260518_01__share_links migration + frontend
//     ForwardShareDialog are live, chip now opens the dialog (generates token
//     + clipboard URL). See parent list.vue handleInlineIconClick('forward').
//   - 'audit' un-pended in PR #861: backend OperationLog / @Loggable AOP /
//     OperationLogController + V20260525_03 migration are live; the chip
//     opens AuditLogDrawer scoped to the row's entity (purchaseOrder /
//     salesOrder). See parent list.vue handleInlineIconClick('audit', ...).
//
// Remaining palette (6 chips):
//   复制 (copy) / 标记 (mark) / 锁定 (lock) / 转发 (forward, pending) /
//   删除 (delete) / 审计 (audit)
//
// 3 ids reuse existing COMMON_ACTIONS so RBAC/status filters compose cleanly:
//   copy / lock / delete  → ids in COMMON_ACTIONS
// 3 ids are inline-specific (no COMMON_ACTIONS equivalent yet):
//   mark / forward / audit  → handled in parent click handler

import type { RowAction, EntityType } from './rowActions';

export type InlineIconId = 'copy' | 'mark' | 'lock' | 'forward' | 'delete' | 'audit';

export interface InlineIconDef {
  id: InlineIconId;
  icon: string; // emoji (legacy field; current renderer is text-only)
  label: string;
  /** When true, parent owns the click handler; component doesn't try to map to COMMON_ACTIONS. */
  inlineOnly: boolean;
  danger?: boolean;
  requiresConfirm?: boolean;
  /**
   * Feature defined but backing API not implemented. Renderer shows as
   * disabled chip with " (开发中)" suffix instead of letting the user click
   * into a "待接 API" toast. Remove when API ships.
   */
  pending?: boolean;
}

export const INLINE_ICONS: readonly InlineIconDef[] = [
  { id: 'copy', icon: '', label: '复制', inlineOnly: false },
  { id: 'mark', icon: '', label: '标记', inlineOnly: true },
  { id: 'lock', icon: '', label: '锁定', inlineOnly: false },
  { id: 'forward', icon: '↗️', label: '转发', inlineOnly: true },
  { id: 'delete', icon: '', label: '删除', inlineOnly: false, danger: true, requiresConfirm: true },
  { id: 'audit', icon: '', label: '审计', inlineOnly: true },
];

export interface InlineIconState {
  def: InlineIconDef;
  enabled: boolean;
  /** API not implemented yet — show as disabled + "(开发中)" suffix. */
  pending: boolean;
  /** Reason shown on tooltip when disabled. */
  disabledReason?: string;
}

/**
 * Compose 7 inline-icon states from the existing computed RowAction[] (output of
 * useRowActions.computeRowActions). The 3 inline-only ids (mark/forward/audit)
 * are always enabled (parent decides their semantics).
 *
 * Disabled rule for known ids: id is NOT in the rowActions array OR is flagged
 * disabled in the rowActions entry.
 */
export function computeInlineIconStates(
  rowActions: RowAction[],
  // Reserved for future per-entity overrides (e.g. salesOrder forwarding 不可用).
  _entityType?: EntityType
): InlineIconState[] {
  const byId = new Map(rowActions.map((a) => [a.id, a]));
  return INLINE_ICONS.map<InlineIconState>((def) => {
    const pending = def.pending === true;
    if (def.inlineOnly) {
      return { def, enabled: true, pending };
    }
    const match = byId.get(def.id);
    if (!match) {
      return {
        def,
        enabled: false,
        pending,
        disabledReason: '当前状态不允许此操作',
      };
    }
    return {
      def,
      enabled: !match.disabled,
      pending: pending || match.pending === true,
      disabledReason: match.disabled ? match.disabledReason : undefined,
    };
  });
}
