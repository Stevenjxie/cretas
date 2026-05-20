package com.cretas.aims.dto.attachment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sprint 6 W3-A (链 chip 拆 file/image/contract) — per-entity 3-chip counts of
 * attached files, returned alongside list payloads so 4 list views (sales /
 * procurement / inventory / voucher) can render inline
 * {@code 文件(N) 图片(N) 合同(N)} trio without N+1 queries.
 *
 * <p><b>Source</b>: Round 12 §B.6 X2 + Round 13 §3 — HJ baseline 销售单 list
 * 行内 link counter 是 3 类 (file/image/contract). Sprint 5 H-1 spec §3.1
 * (linkCounts inner type) lifted to its own DTO so all 4 list views can
 * share the same shape.
 *
 * <p><b>Category mapping</b>:
 * <ul>
 *   <li>{@code fileCount}    = file_category IN ('DOCUMENT', 'OTHER')</li>
 *   <li>{@code imageCount}   = file_category IN ('PHOTO', 'VIDEO')</li>
 *   <li>{@code contractCount}= file_category = 'CONTRACT'</li>
 * </ul>
 *
 * <p>{@code VOUCHER} and {@code SIGNATURE} categories are intentionally NOT
 * included in the 3-chip total — they're shown elsewhere (vouchers in the
 * 凭证 column, signatures embedded in row metadata). The
 * {@code totalCount} convenience field is the sum of the 3 chip values only.
 *
 * <p><b>Field naming</b> follows {@code field-naming-convention.md}:
 * camelCase JSON / DTO, snake_case nowhere (this DTO has no DB column).
 *
 * @author Cretas Team — Sprint 6 W3-A
 * @since 2026-05-19 (Sprint 6 plan §W3 W3-A)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkChipCountsDTO {

    /**
     * Owning entity id (e.g. SalesOrder.id, PurchaseOrder.id, Inventory.id,
     * PaymentVoucher.id). Echoed back so the caller can dict-merge without
     * extra index lookup.
     */
    private String entityId;

    /**
     * Count of attached files in {@code DOCUMENT} or {@code OTHER} category.
     * Maps to the 文件:N chip in the row.
     */
    private long fileCount;

    /**
     * Count of attached images in {@code PHOTO} or {@code VIDEO} category.
     * Maps to the 图片:N chip in the row.
     */
    private long imageCount;

    /**
     * Count of attached contracts in {@code CONTRACT} category.
     * Maps to the 合同:N chip in the row.
     */
    private long contractCount;
}
