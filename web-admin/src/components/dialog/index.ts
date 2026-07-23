// 开始采购弹窗 — 从 SO 一键生成 PO 明细 (t2b 行1867-1902 Friday 请求).
export { default as StartPurchaseDialog } from './StartPurchaseDialog.vue';
// 合并采购弹窗 — 多张 SO 合并成一张采购单 (转录行3650 "加号逐个追加合并").
export { default as MergePurchaseDialog } from './MergePurchaseDialog.vue';
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
