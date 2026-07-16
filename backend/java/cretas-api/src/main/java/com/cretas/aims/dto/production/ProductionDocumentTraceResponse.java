package com.cretas.aims.dto.production;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Read-only business-document lineage anchored at one production plan. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionDocumentTraceResponse {

    private String productionPlanId;
    private String planNumber;
    private String planStatus;

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
        private String direction;
        private String relation;
        private LocalDateTime occurredAt;
    }
}
