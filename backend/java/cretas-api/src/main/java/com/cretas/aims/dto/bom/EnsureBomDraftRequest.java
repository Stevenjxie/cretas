package com.cretas.aims.dto.bom;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Request for the idempotent BOM draft lifecycle entry point. */
@Data
public class EnsureBomDraftRequest {

    @NotBlank(message = "productTypeId 不能为空")
    @Size(max = 100, message = "productTypeId 长度不能超过 100")
    private String productTypeId;

    /**
     * Exact saved Workflow revision supplied by the Workflow canvas.
     *
     * <p>When present this is the authority for the BOM workspace. The backend must never
     * silently fall back to an older ACTIVE BOM lineage or another Workflow containing the
     * same terminal SKU.
     */
    private Long workflowRevisionId;

    /**
     * 用户已在界面上逐行看过并确认「旧工艺遗留的投入行可以丢」。
     *
     * <p>缺省 false = 保持原行为。为 true 时后端**自己**重新算出哪几行是孤儿并软删,
     * 不接受客户端指定要删哪几行 —— 这个字段只表达"人已确认", 不表达"删什么"。
     */
    private Boolean dropObsoleteInputs;
}
