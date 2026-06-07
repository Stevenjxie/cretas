package com.cretas.aims.dto.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * T126 Phase 1 — PUT /finished-goods/{id} 编辑请求 DTO
 *
 * <p>仅允许修改元数据字段。
 * <strong>⛔ producedQuantity 和 unit 不在此 DTO 中</strong> —
 * producedQuantity 通过 /adjust 端点修改；unit 一旦创建不可变（RestockBoard 按 unit 聚合，
 * 变更会破坏看板汇总）。
 *
 * <p>Agent B 类型化契约（前端 interface）：
 * <pre>
 * interface EditFinishedGoodsRequest {
 *   remark?: string;
 *   storageLocation?: string;
 *   expireDate?: string;       // ISO date "YYYY-MM-DD"
 *   unitPrice?: number;        // 需要 sales:read_write 权限才会生效
 * }
 * </pre>
 */
public record EditFinishedGoodsRequest(
        String remark,
        String storageLocation,
        LocalDate expireDate,
        BigDecimal unitPrice
) {
}
