package com.cretas.aims.dto.disposal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 拒绝报废记录请求 DTO (F-BUG-2)。
 *
 * <p>此前无 reject 端点, 前端 "拒绝" 错误地走 approve (传 {@code {approved:false, rejectReason}})
 * → 后端无条件批准+扣库存 (语义反转)。新增独立 reject 端点 + 本 DTO。
 *
 * <p>审批人身份从 JWT token 取 (见 {@code DisposalController.rejectDisposal}), 不在请求体。
 * 拒绝原因必填 (防呆: 拒绝须有据可查)。
 *
 * @author Cretas Team
 * @since 2026-06-22
 */
@Data
@Schema(description = "拒绝报废记录请求")
public class RejectDisposalRequest {

    @Schema(description = "拒绝原因", example = "证据不足, 数量与实际不符", required = true)
    @NotBlank(message = "拒绝原因不能为空")
    @Size(max = 500, message = "拒绝原因长度不能超过500个字符")
    private String reason;
}
