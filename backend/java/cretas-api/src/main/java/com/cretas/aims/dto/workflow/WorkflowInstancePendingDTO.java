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

    /**
     * 业务类型中文名, 取自权威表 {@code DecisionTypeMetadataRegistry}。
     *
     * <p>前端曾自行维护一份 4 个码的 MODULE_LABELS, 其余 20 多个码全落「未知状态（X）」兜底
     * (客户截图里的「未知状态（BUDGET）」即此)。改由后端按权威表下发, 前端不再维护第二份。
     * 解析不出来时为 null, 由前端兜底 —— 后端不编造。
     */
    @Schema(description = "业务类型中文名 (取自权威表; 解析不出时为 null, 前端兜底)",
            example = "会计期间结账")
    private String moduleLabel;

    /**
     * 该实例是否由系统流程发起(无人类申请人)。
     *
     * <p>判据是 {@code initiatedBy == null}, <b>不是</b> username 为空 —— 用户被删同样会让
     * username 为空, 那种情况该显示「—」而非「系统自动发起」。
     * 客户截图里申请人空白的那条是凌晨 2 点定时任务发起的月度会计期间结账审批。
     */
    @Schema(description = "是否系统自动发起 (无人类申请人)", example = "true")
    private boolean systemInitiated;
}
