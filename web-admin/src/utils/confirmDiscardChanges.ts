/**
 * 关闭有未保存内容的 dialog/drawer 前二次确认
 *
 * 背景 (fool-proof-design.md headed audit, 六膳门低文化素质操作员场景):
 * 核对结单 dialog / 逐工序录入 drawer / 收款/开票 dialog 等消耗性写操作 dialog
 * 点 X / 点遮罩 / 按 ESC / 点"取消"都会静默丢弃已录入数据 — 没有任何提示,
 * 操作员发现"数据没了"往往是提交后才察觉, 只能重新录一遍。
 *
 * 约定: 每个消耗性 dialog 在打开(含预填完成)时快照表单初始态, 关闭前
 * (before-close / 取消按钮) 都用本 helper 比对是否 dirty, dirty 才二次确认。
 */
import { ElMessageBox } from 'element-plus';

/**
 * @param isDirty 表单当前是否有相对快照的未保存改动
 * @returns true = 允许关闭 (用户确认丢弃或本就无改动), false = 用户选择继续编辑，调用方不应关闭
 */
export async function confirmDiscardIfDirty(isDirty: boolean): Promise<boolean> {
  if (!isDirty) return true;
  try {
    await ElMessageBox.confirm('有未保存内容，确认关闭？', '提示', {
      type: 'warning',
      confirmButtonText: '确认关闭',
      cancelButtonText: '继续编辑',
    });
    return true;
  } catch {
    return false;
  }
}
