package com.cretas.aims.dto.disposal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 审批报废记录请求 DTO.
 *
 * <p>Rule 17.1 cleanup (Issue #384 batch 5): replaces the
 * untyped {@code Map<String, Object>} body of
 * {@code PUT /api/mobile/{factoryId}/disposal-records/{id}/approve} with
 * a typed DTO.
 *
 * <p><b>F-BUG-1 / F-BUG-4 修复 (审计 round1)</b>: 此前 {@code approverId} / {@code approverName}
 * 带 {@code @NotNull} / {@code @NotBlank} 校验, 前端却只传 {@code {approved:true}} →
 * {@code @Valid} 触发 400, 审批 100% 失败。同时审批人身份取自请求体 (可伪造)。
 *
 * <p>修复后: 审批人 {@code approverId} / {@code approverName} 改由后端从 JWT token 取
 * (见 {@code DisposalController.approveDisposal} 的 {@code extractUserId} / role),
 * <b>不再信任请求体</b>。本 DTO 仅保留可选 {@code remark} (审批备注), 无任何必填约束 →
 * 空 body ({@code {}}) 也能通过 {@code @Valid}。
 *
 * @author Cretas Team
 * @since 2026-05-11
 */
@Data
@Schema(description = "审批报废记录请求 (审批人从 token 取, body 仅可选备注)")
public class ApproveDisposalRequest {

    @Schema(description = "审批备注 (可选)", example = "核实无误, 同意报废")
    @Size(max = 500, message = "审批备注长度不能超过500个字符")
    private String remark;
}
