package com.cretas.aims.dto.production;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 核对结单"自动预填"响应 (Phase 2A — 报工→核算自动化).
 *
 * <p>从该计划已录的逐道报工 (YIELD reports) derive 出一个 {@link ProductionSettlementRequest}
 * 形状的预填表单 + 审计结果, 供前端核对结单 dialog 一次性带入, 省去文员二次手敲。
 *
 * <p><b>安全边界</b>: 本 DTO 只承载"预填建议 + 审计提示"。它<b>不</b>触发任何库存扣减 —
 * 真扣库存仍由文员在 dialog 上核对后点确认调 {@code POST /{planId}/settle} 才发生。
 * derive 宁可少填不可瞎填: 不确定的字段留空并在 {@code audit.issues} 标出让人补。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionSettlementPrefillResponse {

    /** 预填表单 (ProductionSettlementRequest 形状; idempotencyKey 留空由前端生成)。 */
    private ProductionSettlementRequest prefill;

    /** 审计结果 (clean=true → 可一键确认; 否则列 issues 让人补全)。 */
    private Audit audit;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Audit {
        /**
         * 审计是否全通过 (无任何 BLOCKER 级 issue)。
         * <p>false → 存在阻塞项, 前端应禁用一键确认直到人工补全。
         * INFO 级 issue (如"确认是否跨计划领半成品") 不影响 clean — 仅作提示。
         */
        @Builder.Default
        private boolean clean = true;

        /** 审计问题列表 (空 = 干净; 含 BLOCKER + INFO 两级, severity 区分)。 */
        @Builder.Default
        private List<Issue> issues = new ArrayList<>();
    }

    /** 问题级别。 */
    public enum Severity {
        /** 阻塞: 必须人工补全才能提交 (clean=false)。 */
        BLOCKER,
        /** 提示: 仅提醒人核对, 不阻塞一键确认 (clean 不受影响)。 */
        INFO
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Issue {
        /** 机器可读问题码 (前端按此高亮对应字段 / 弹原因选择)。 */
        private String code;
        /** 人类可读说明 (直接展示给文员; 含 next-action 提示, 防呆 4 位一体)。 */
        private String message;
        /** 关联字段 (前端定位高亮; 可空)。 */
        private String field;
        /** 级别 BLOCKER/INFO; 仅 BLOCKER 令 clean=false。默认 BLOCKER (保守)。 */
        @Builder.Default
        private Severity severity = Severity.BLOCKER;
    }
}
