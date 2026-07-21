package com.cretas.aims.dto.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * "我待审" widget 单行 DTO — issue #20.
 *
 * <p>由 {@code WorkflowInstanceController.getPendingForUser} 返回. 前端用来在
 * Dashboard 顶部显示 finance_manager / quality_manager / 等审批角色待审的
 * RUNNING workflow 实例.
 *
 * <p>businessSummary 由 controller 根据 moduleCode 现 hydrate:
 * <ul>
 *   <li>PURCHASE_ORDER → "PO-20260518-0123 ¥40000 (供应商甲)"</li>
 *   <li>SALES_ORDER → 类似 pattern (Phase 2 加)</li>
 *   <li>其他 → instanceId fallback</li>
 * </ul>
 *
 * @since 2026-05-18 (issue #20 / Phase 1 closure for ADR-001 AC-3)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "我待审 widget 单行 DTO")
public class WorkflowInstancePendingDTO {

    @Schema(description = "工作流实例 ID (UUID)")
    private String instanceId;

    @Schema(description = "业务模块 code", example = "PURCHASE_ORDER")
    private String moduleCode;

    @Schema(description = "业务实体 ID (PO id / SO id)")
    private String businessEntityId;

    @Schema(description = "前端显示用业务摘要", example = "PO-20260518-0123 ¥40000 (供应商甲)")
    private String businessSummary;

    @Schema(description = "当前 active 审批节点 ID")
    private String currentNodeId;

    @Schema(description = "当前节点显示名", example = "财务审批")
    private String currentNodeLabel;

    @Schema(description = "工作流启动时间 (RUNNING 实例的发起时间)")
    private LocalDateTime initiatedAt;

    @Schema(description = "发起人 username (PO 创建者). 系统触发时为 null.")
    private String initiatedByUsername;

    @Schema(description = "实例状态", example = "RUNNING")
    private String status;

    @Schema(description = "终态时间")
    private LocalDateTime completedAt;

    @Schema(description = "当前节点授权角色")
    private List<String> approverRoles;
}
