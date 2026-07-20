package com.cretas.aims.dto.factory;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发起盘点任务请求 DTO (SP7 T1).
 */
@Data
public class CreateStocktakeRequest {

    @NotBlank(message = "仓库 ID 不能为空")
    private String warehouseId;

    /**
     * Legacy compatibility only. The server ignores this value and derives the accounting
     * period from inventoryCutoffAt, which is always generated inside initiate().
     */
    private String periodMonth;

    /** LAST_APPLIED(default), LAST_7_DAYS, MONTH, QUARTER, YEAR, CUSTOM. */
    private String reconciliationPreset;

    /** Accepted only for CUSTOM and only as the review-window start. */
    private LocalDateTime reconciliationStartAt;

    private String notes;

    /**
     * 临时/专项盘点 (fool-proof Rule 5: 月底约束无例外的 dead-end 修复, 2026-07).
     * 为 true 时跳过月底约束门 (与 OPENING 期初建账同待遇), 用于疑似失窃/货物损坏等
     * 需要立即清点的场景, 不等到月底。默认 false = 常规盘点仍受月底约束。
     */
    private boolean adHoc = false;

    /** 临时盘点原因 (adHoc=true 时前端要求必填, 标准下拉 + 其他)。仅记日志, 不落库 (保持改动轻量)。 */
    private String adHocReason;
}
