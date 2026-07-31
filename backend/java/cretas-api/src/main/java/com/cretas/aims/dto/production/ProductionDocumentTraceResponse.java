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

        /**
         * 该单据的关键字段, 供前端**就地展开** (2026-07-31 客户拍板: 追踪里看详情不跳页)。
         *
         * <p>与 {@code BusinessDocumentTraceResponse.TraceDocument#details} 同一契约。
         * 构建链路时这些实体本来就已经从库里读出来了, 顺手带回 —— 零新增接口、零 N+1。
         * 拿不到的字段直接不放, 不塞占位符。</p>
         */
        @Builder.Default
        private java.util.List<Field> details = new java.util.ArrayList<>();
    }

    /** 一个展示字段。value 已是可直接显示的字符串, 格式化在后端做。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Field {
        private String label;
        private String value;
    }
}
