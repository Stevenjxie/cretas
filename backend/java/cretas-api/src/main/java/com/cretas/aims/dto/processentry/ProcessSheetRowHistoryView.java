package com.cretas.aims.dto.processentry;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * SP-G P3 — 逐工序电子表格行级操作记录读回视图 (行级 diff 时间线一项)。
 *
 * <p>通过 {@link com.cretas.aims.service.processentry.ProcessSheetService#getRowHistory}
 * 按时间倒序返回某一行的全部变更，供前端「操作记录」时间线展示。
 */
@Data
@AllArgsConstructor
public class ProcessSheetRowHistoryView {

    /** 变更记录 id。 */
    private Long id;

    /** 操作类型: "CREATE" | "UPDATE" | "DELETE"。 */
    private String operation;

    /** 变更前字段快照 (CREATE 时为 null)。 */
    private Map<String, Object> beforeValue;

    /** 变更后字段快照 (DELETE 时为 null)。 */
    private Map<String, Object> afterValue;

    /** 人类可读摘要: "字段: 旧→新" 列表。 */
    private String diffSummary;

    /** 操作人 userId (可能为 null)。 */
    private Long operatorId;

    /** 变更发生时间。 */
    private LocalDateTime createdAt;
}
