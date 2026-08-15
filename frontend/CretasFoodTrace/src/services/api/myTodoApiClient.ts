/**
 * OA 我的待办 API 客户端
 *
 * 端点:
 *   GET /api/mobile/{factoryId}/my-todos          → ApiResponse<List<TodoItemDTO>>
 *   GET /api/mobile/{factoryId}/my-todos/count     → ApiResponse<Integer>
 *
 * 审批动作: 调各域现有端点（不重写）。
 *
 * 角色: finance_manager → 采购财审/销售财审/价格异常/盘点审批
 *        cashier        → 已审付款确认
 *
 * @since 2026-06-12 (OA 待办 R1)
 */

import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

// ──────────────────────────────────────────────────────────────────────────────
// DTO 类型定义 (mirror 后端 TodoItemDTO.java)
// ──────────────────────────────────────────────────────────────────────────────

/** 待办类型 — 与后端 TodoItemDTO.TodoType 对齐(勿手写个数, 加一个就在这里补一行) */
export type TodoType =
  | 'PURCHASE_FINANCE_REVIEW'  // 采购单待财务审核
  | 'SALES_FINANCE_REVIEW'     // 销售单待财务审核
  | 'PRICE_ANOMALY'            // 送货单价格异常待审批
  | 'STOCKTAKE_APPROVAL'       // 盘点任务待财务审批
  | 'RETURN_FINANCE_REVIEW'    // 退货单待财务审批
  | 'WASTAGE_APPROVAL'         // 报损单待审批
  | 'PAYMENT_DISBURSE';        // 已审批付款请求（出纳执行）

export interface TodoItemDTO {
  type: TodoType;
  refId: string;
  refNumber: string;
  title: string;
  /** 金额（可 null，null 时 needDetail 保守为 true） */
  amount: number | null;
  counterparty: string;
  submittedBy: string;
  submittedAt: string;   // ISO datetime string
  needDetail: boolean;
  detailPath: string | null;
  /** OA 审批实例 ID —— 仅 OA 驱动的类型有值(当前: PURCHASE_FINANCE_REVIEW) */
  instanceId?: string | null;
  /** 当前节点 ID(乐观锁), 调 OA 动作端点必带 */
  expectedNodeId?: string | null;
}

// ──────────────────────────────────────────────────────────────────────────────
// 拒绝原因下拉选项（防呆 Rule 3: 标准 dropdown + 其他才用 textarea）
// ──────────────────────────────────────────────────────────────────────────────

export const REJECT_REASON_OPTIONS = [
  '价格异常，需重新核价',
  '供应商资质存疑，需审查',
  '单据信息不完整，需补充',
  '超出预算范围，需重新申请',
  '审批程序不合规',
  '数量与实际不符',
  '其他',
] as const;

export type RejectReasonOption = typeof REJECT_REASON_OPTIONS[number];

// ──────────────────────────────────────────────────────────────────────────────
// 主 API 客户端
// ──────────────────────────────────────────────────────────────────────────────

class MyTodoApiClient {
  private getBasePath(factoryId?: string): string {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) throw new Error('factoryId 是必需的，请先登录');
    return `/api/mobile/${fid}/my-todos`;
  }

  /** 获取我的待办列表（按角色过滤） */
  async getMyTodos(factoryId?: string): Promise<{ success: boolean; data: TodoItemDTO[]; message?: string }> {
    return apiClient.get(this.getBasePath(factoryId));
  }

  /** 获取待办数量（RN 导航 tab 徽标用） */
  async getMyTodoCount(factoryId?: string): Promise<{ success: boolean; data: number; message?: string }> {
    return apiClient.get(this.getBasePath(factoryId) + '/count');
  }
}

// ──────────────────────────────────────────────────────────────────────────────
// 各域审批动作（调现有端点，不重写）
// ──────────────────────────────────────────────────────────────────────────────

class TodoApprovalApiClient {
  private fid(factoryId?: string): string {
    const id = getCurrentFactoryId(factoryId);
    if (!id) throw new Error('factoryId 是必需的，请先登录');
    return id;
  }

  // ─── OA 统一动作入口 ─────────────────────────────────────────────────────────

  /**
   * 在 OA 审批中心处理当前节点。
   *
   * <p>⚠️ 采购的 `/finance-approve` `/finance-reject` 已于 2026-07-21 (#1557) 停用,
   * 直接抛 410「采购审批只能在 OA 审批中心处理」。凡是 OA 驱动的待办, 通过/驳回
   * **必须**走这个入口, 否则就是一个点了必失败的按钮。
   *
   * @param expectedNodeId 乐观锁: 与实例当前节点不符时后端返 409 OA_TASK_NODE_CHANGED
   *                       (「审批节点已变化, 请刷新待办后重试」) —— 防两个人同时批。
   */
  async oaAction(
    instanceId: string,
    action: 'APPROVE' | 'REJECT',
    expectedNodeId: string,
    notes?: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: unknown; message?: string }> {
    return apiClient.post(
      `/api/mobile/${this.fid(factoryId)}/workflow/instances/${instanceId}/actions`,
      {
        action,
        notes,
        expectedNodeId,
        // 每次点击一个新键。重复点不会重复执行 —— 第一次成功后节点已推进,
        // 第二次会被 expectedNodeId 的乐观锁挡成 409, 而不是批两次。
        idempotencyKey: `oa-${instanceId}-${expectedNodeId}-${action}-${Date.now()}`,
      },
    );
  }

  // ─── 采购财务审核 ───────────────────────────────────────────────────────────

  /** 采购单财务审核通过 */
  async purchaseFinanceApprove(
    orderId: string,
    notes?: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: unknown; message?: string }> {
    return apiClient.post(
      `/api/mobile/${this.fid(factoryId)}/purchase/orders/${orderId}/finance-approve`,
      { notes },
    );
  }

  /** 采购单财务驳回（notes 必填） */
  async purchaseFinanceReject(
    orderId: string,
    notes: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: unknown; message?: string }> {
    return apiClient.post(
      `/api/mobile/${this.fid(factoryId)}/purchase/orders/${orderId}/finance-reject`,
      { notes },
    );
  }

  // ─── 销售财务审核 ───────────────────────────────────────────────────────────

  /** 销售单财务审核通过 */
  async salesFinanceApprove(
    orderId: string,
    notes?: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: unknown; message?: string }> {
    return apiClient.post(
      `/api/mobile/${this.fid(factoryId)}/sales/orders/${orderId}/finance-approve`,
      { notes },
    );
  }

  /** 销售单财务驳回（notes 必填） */
  async salesFinanceReject(
    orderId: string,
    notes: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: unknown; message?: string }> {
    return apiClient.post(
      `/api/mobile/${this.fid(factoryId)}/sales/orders/${orderId}/finance-reject`,
      { notes },
    );
  }

  // ─── 价格异常审批 ───────────────────────────────────────────────────────────

  /** 价格异常审批通过 */
  async priceAnomalyApprove(
    noteId: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: unknown; message?: string }> {
    return apiClient.post(
      `/api/mobile/${this.fid(factoryId)}/warehouse/supplier-delivery-notes/${noteId}/price-anomaly/approve`,
    );
  }

  /** 价格异常驳回 */
  async priceAnomalyReject(
    noteId: string,
    notes: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: unknown; message?: string }> {
    return apiClient.post(
      `/api/mobile/${this.fid(factoryId)}/warehouse/supplier-delivery-notes/${noteId}/price-anomaly/reject`,
      { notes },
    );
  }

  // ─── 盘点审批 ───────────────────────────────────────────────────────────────

  /** 盘点任务审批通过 */
  async stocktakeApprove(
    stocktakeId: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: unknown; message?: string }> {
    return apiClient.post(
      `/api/mobile/${this.fid(factoryId)}/stocktakes/${stocktakeId}/approve`,
    );
  }

  /** 盘点任务驳回 */
  async stocktakeReject(
    stocktakeId: string,
    notes: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: unknown; message?: string }> {
    return apiClient.post(
      `/api/mobile/${this.fid(factoryId)}/stocktakes/${stocktakeId}/reject`,
      { notes },
    );
  }

  /** 报损单审批通过 */
  async wastageApprove(
    reportId: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: unknown; message?: string }> {
    return apiClient.post(
      `/api/mobile/${this.fid(factoryId)}/wastage-reports/${reportId}/approve`,
    );
  }

  /** 报损单驳回 */
  async wastageReject(
    reportId: string,
    reason: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: unknown; message?: string }> {
    return apiClient.post(
      `/api/mobile/${this.fid(factoryId)}/wastage-reports/${reportId}/reject`,
      { reason },
    );
  }

  // ─── 退货财务审核 ─────────────────────────────────────────────────────────

  /** 退货单财务审核通过 */
  async returnFinanceApprove(
    returnOrderId: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: unknown; message?: string }> {
    return apiClient.post(
      `/api/mobile/${this.fid(factoryId)}/return-orders/${returnOrderId}/finance-approve`,
    );
  }

  /** 退货单财务驳回 */
  async returnFinanceReject(
    returnOrderId: string,
    notes: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: unknown; message?: string }> {
    return apiClient.post(
      `/api/mobile/${this.fid(factoryId)}/return-orders/${returnOrderId}/finance-reject`,
      { notes },
    );
  }

  // ─── 付款确认（出纳） ────────────────────────────────────────────────────────

  /** 出纳确认付款 */
  async paymentMarkPaid(
    paymentRequestId: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: unknown; message?: string }> {
    return apiClient.put(
      `/api/mobile/${this.fid(factoryId)}/payment-requests/${paymentRequestId}/mark-paid`,
      {},
    );
  }
}

export const myTodoApiClient = new MyTodoApiClient();
export const todoApprovalApiClient = new TodoApprovalApiClient();
