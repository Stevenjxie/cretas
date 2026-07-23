// UX-A2 Track H — RowActionMenu (列表行末"操作 ▾").
// UX-A3 Track I — TableFooter (el-pagination + summary stats + 📊/📤).
// U-VIEW-1 Sprint 4 Wave 2 Chat L — ViewModeSwitcher + GridView/KanbanView + 2 placeholder.
// Coordinate: see 宏见竞品分析/04-最终决策/STATUS/TRACK_{H,I}_STATUS.md.
export { default as RowActionMenu } from './RowActionMenu.vue';
export { default as TableFooter } from './TableFooter.vue';
export { default as ViewModeSwitcher } from './ViewModeSwitcher.vue';
export { default as GridView } from './GridView.vue';
export { default as KanbanView } from './KanbanView.vue';
export { default as TimelinePlaceholder } from './TimelinePlaceholder.vue';
export { default as CalendarPlaceholder } from './CalendarPlaceholder.vue';
// U-ICON-1 Sprint 4 Wave 2 Chat L — inline 7-icon hover toolbar.
export { default as InlineRowIcons } from './InlineRowIcons.vue';
// U-MARKER-1 Sprint 4 Wave 2 Chat L — 5-color row marker cell + popover.
export { default as RowMarkerCell } from './RowMarkerCell.vue';
export { default as ListColumnSelector } from './ListColumnSelector.vue';
export { useBusinessTableColumns } from './businessTableColumns';
export type { BusinessTableColumn } from './businessTableColumns';

// Sprint 4 W1 U-CHIP-MULTI-1 — 行内多 chip 状态垂直堆 (e.g. 库存/质检/采购/发货 状态).
export { default as MultiChipStack } from './MultiChipStack.vue';
export type { ChipDef, ChipType } from './MultiChipStack.vue';

// Sprint 6 W3-A — 行内 3-chip 链接计数 (文件 / 图片 / 合同) per HJ baseline.
// Used in 4 list (sales/PO/inventory/voucher); wires via useLinkChipCounts composable.
export { default as LinkChipCell } from './LinkChipCell.vue';
