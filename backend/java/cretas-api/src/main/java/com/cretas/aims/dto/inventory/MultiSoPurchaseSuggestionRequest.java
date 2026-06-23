package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 多销售订单合并生成采购建议的请求体.
 *
 * <p>转录行3650: 多个 SO 用"加号"逐个追加合并成一张采购单。
 * 前端把勾选/追加的 SO ID 列表传上来, 后端跨 SO 聚合 BOM 需求。
 *
 * @author Cretas Team
 * @since 2026-06-23
 */
@Data
public class MultiSoPurchaseSuggestionRequest {

    /** 参与合并的销售订单 ID 列表 (至少一张) */
    @NotEmpty(message = "请至少选择一张销售订单")
    private List<String> salesOrderIds;
}
