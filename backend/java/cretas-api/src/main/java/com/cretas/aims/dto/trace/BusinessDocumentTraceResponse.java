package com.cretas.aims.dto.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only business-document lineage anchored at one ordinary business document
 * (sales order / purchase order / internal transfer).
 *
 * <p>Same honesty contract as {@code ProductionDocumentTraceResponse}: every edge is
 * backed by a real persisted foreign key. Nothing is inferred from names, dates or
 * quantities, and a link that is recorded but no longer resolvable is reported in
 * {@link #missingLinks} instead of being silently dropped.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessDocumentTraceResponse {

    /** The document the user is standing on: SALES_ORDER / PURCHASE_ORDER / INTERNAL_TRANSFER. */
    private String anchorType;
    private String anchorId;
    private String anchorNumber;
    private String anchorStatus;

    @Builder.Default
    private List<TraceDocument> documents = new ArrayList<>();

    /** Broken explicit links only; an optional business document simply not created is not an error. */
    @Builder.Default
    private List<String> missingLinks = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceDocument {
        private String documentType;
        private String documentId;
        private String documentNumber;
        private String status;
        /** UPSTREAM (来源) / EXECUTION (执行) / DOWNSTREAM (结算与出库). */
        private String direction;
        private String relation;
        private LocalDateTime occurredAt;
    }
}
