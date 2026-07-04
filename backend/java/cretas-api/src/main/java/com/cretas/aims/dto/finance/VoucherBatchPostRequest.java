package com.cretas.aims.dto.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Request DTO for batch-posting DRAFT vouchers (人工审核制下逐张过账负担太大, follow-up to
 * #1228 finding: "批量过账目前只有单张 POST /{id}/post, 无 batch-post-selected 端点").
 *
 * @since 2026-07-04
 */
@Data
@Schema(description = "批量过账请求体")
public class VoucherBatchPostRequest {

    @Schema(description = "待过账凭证 id 列表", required = true)
    @NotEmpty(message = "voucherIds 不能为空")
    private List<String> voucherIds;
}
