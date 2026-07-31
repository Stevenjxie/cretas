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

        /**
         * 该单据的关键字段, 供前端**就地展开**用 (2026-07-31 客户拍板: 追踪里看详情不跳页)。
         *
         * <p>为什么放在这里而不是让前端逐类型再请求一次: 构建链路时这些实体**本来就已经
         * 从库里读出来了**, 只是过去除单号/状态/日期外全被丢掉。前端另请求意味着 15 种
         * 单据类型 × 各自的详情接口 + N+1 往返, 而字段该显示什么本就该贴着实体定义。</p>
         *
         * <p>有序 (LinkedHashMap 语义由构造顺序保证), 只放**看一眼就能确认是不是这张单**的字段;
         * 不是完整详情页的替代品。拿不到的字段直接不放, **不塞占位符** —— 空值比"—"更诚实,
         * 前端也就不会渲染出一行什么都没有的标签。</p>
         */
        @Builder.Default
        private List<Field> details = new ArrayList<>();
    }

    /** 一个展示字段。value 已经是**可直接显示的字符串**, 格式化在后端做, 前端不再猜类型。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Field {
        private String label;
        private String value;
    }
}
