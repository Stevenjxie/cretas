// 开始采购弹窗 — 从 SO 一键生成 PO 明细 (t2b 行1867-1902 Friday 请求).
export { default as StartPurchaseDialog } from './StartPurchaseDialog.vue';
// 合并采购弹窗 — 多张 SO 合并成一张采购单 (转录行3650 "加号逐个追加合并").
export { default as MergePurchaseDialog } from './MergePurchaseDialog.vue';
// U-NEW-1 Sprint 4 Wave 2 Chat L — create-mode selector + batch-create dialog.
// P1 #58 (U-NEW-1) — add QuickCreate (一维) + BomExpansion (BOM 展开) to finish the 4-mode set.
export { default as CreateModeSelector } from './CreateModeSelector.vue';
export { default as BatchCreateDialog } from './BatchCreateDialog.vue';
export { default as QuickCreateDialog } from './QuickCreateDialog.vue';
export { default as BomExpansionDialog } from './BomExpansionDialog.vue';
// U-DESKTOP-MODAL-1 Sprint 4 Wave 2 Chat L — desktop-class draggable / resizable dialog.
export { default as EnhancedDialog } from './EnhancedDialog.vue';
// U-DESKTOP-MODAL-1 Sprint 4 Wave 2 followup — el-dialog wrapper variant
// (delegates a11y/animation to ElDialog) + global multi-modal dock.
export { default as DesktopModal } from './DesktopModal.vue';
export { default as ModalDock } from './ModalDock.vue';
export {
  useModalDock,
  generateModalId,
  registerMinimized,
  unregisterMinimized,
  type MinimizedModalEntry,
} from './useModalDock';
