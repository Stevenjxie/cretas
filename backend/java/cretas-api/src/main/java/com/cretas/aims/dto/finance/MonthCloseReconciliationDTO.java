package com.cretas.aims.dto.finance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Wave2 月结对账校验结果 DTO.
 *
 * <p>月结前 ({@code previewClose}) 对各项做校验, 给前端 "预先显示边界" (防呆 Rule 1):
 * 用户在点"一键月结"之前就能看到本月对账状态, 而不是点完才报错.
 *
 * <ul>
 *   <li>{@code canClose}: 是否允许结账. 任一 BLOCKING check 失败 → false.</li>
 *   <li>{@code reconciliationStatus}: PASS (无问题) | WARNING (有非阻塞项, 仍可结账)</li>
 *   <li>{@code checks}: 逐项校验明细, 前端逐条展示</li>
 * </ul>
 *
 * @since 2026-06-04 Wave2 月结自动闭环
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MonthCloseReconciliationDTO {

    private String factoryId;
    private Integer year;
    private Integer month;

    /** 是否允许结账 — 任一 BLOCKING check 失败则 false. */
    private boolean canClose;

    /** 对账总结论: PASS | WARNING. */
    private String reconciliationStatus;

    /** 逐项校验明细. */
    private List<CheckItem> checks;

    /** 对账摘要文本 (审计留痕, 写入 period.reconciliation_summary). */
    private String summary;

    /**
     * 单项对账校验结果.
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CheckItem {
        /** 校验项名称, e.g. "未审批的应收/应付调整". */
        private String name;
        /** 是否通过. */
        private boolean passed;
        /** 严重级别: BLOCKING (失败则不可结账) | WARNING (失败仍可结账, 仅提示). */
        private String severity;
        /** 明细说明文本 (给用户看). */
        private String detail;
        /** 关联数值 (e.g. 待审批调整笔数), 可为 null. */
        private Object value;
    }
}
