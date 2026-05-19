package com.cretas.aims.dto.sales;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sprint 5 Track C-2 (G12-1 inline link counter) — per-order counts of attached
 * resources, returned alongside the list payload so the list view can render
 * inline {@code 📎 N / 🖼 N / 📄 N} chips without per-row N+1 calls.
 *
 * <p><b>Source</b>: Round 12 §B.6 X2 — HJ 销售单 list 行内 link counter, Cretas
 * 缺。Sprint 5 plan §C C-2 (P1 4d → MVP slice). Spec
 * {@code docs/superpowers/specs/2026-05-19-print-categories-coverage.md}
 * sister spec for backlog tracking.
 *
 * <p><b>Phase-1 mapping</b> (this PR):
 * <ul>
 *   <li>{@code outboundLinkCount} = {@link com.cretas.aims.entity.common.BusinessLink}
 *       count where SO is the {@code owner_type=SALES_ORDER} (我 link 了谁).</li>
 *   <li>{@code inboundLinkCount} = BusinessLink count where SO is the
 *       {@code target_type=SALES_ORDER} (谁 link 了我 — e.g., ReturnOrder,
 *       Invoice, DeliveryRecord).</li>
 * </ul>
 *
 * <p><b>Phase-2 enrich</b> (Sprint 6, depends on §H-1 AttachmentRecord split):
 * add {@code fileCount / imageCount / contractCount} per HJ 3-category split.
 * When AttachmentRecord lands with a dedicated {@code SALES_ORDER} EntityType,
 * the front-end chip row will pivot from {@code 链:N} → {@code 文件:N 图片:N 合同:N}.
 *
 * <p>Field naming follows {@code field-naming-convention.md}: camelCase
 * JSON / DTO, snake_case nowhere (this DTO has no DB column).
 *
 * @author Cretas Team — Sprint 5 Track C
 * @since 2026-05-19 (Sprint 5 plan §C C-2)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesOrderLinkCountsDTO {

    /**
     * Owning SalesOrder.id. Echoed back so the caller can dict-merge without
     * extra index lookup.
     */
    private String orderId;

    /**
     * BusinessLink count where this SO is the {@code owner_type=SALES_ORDER}
     * (this SO outbound-linked to N other business docs). Zero if none.
     */
    private long outboundLinkCount;

    /**
     * BusinessLink count where this SO is the {@code target_type=SALES_ORDER}
     * (N other business docs inbound-linked here). Includes ReturnOrder,
     * Invoice, DeliveryRecord references. Zero if none.
     */
    private long inboundLinkCount;
}
