package com.cretas.aims.dto.processentry;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * SP-F Task 2.2 — 已保存行的读回视图 (供前端电子表格重载使用)。
 *
 * <p>通过 {@link com.cretas.aims.service.processentry.ProcessSheetService#getRows} 返回。
 * payload 字段是原始录入 JSON 的反序列化结果，可直接回填前端表单。
 */
@Data
@AllArgsConstructor
public class ProcessSheetRowView {

    /** 客户端稳定行 id (与前端电子表格行键一致)。 */
    private String clientRowId;

    /** 物化批次号；DRAFT 行为 null。 */
    private String batchNumber;

    /** 物化 ProductionBatch.id；DRAFT 行为 null。 */
    private Long batchId;

    /** 行状态: "SAVED" | "DRAFT"。 */
    private String rowStatus;

    /** LEGACY / DRAFT / SUBMITTED。 */
    private String submissionStatus;

    /** batchId != null → true (已物化)。 */
    private boolean materialized;

    /** 原始录入数据 (从 row_payload JSON 反序列化)，可回填前端表单。 */
    private ProcessSheetRowRequest payload;

    /**
     * BY_STOCK 小结时间戳 (Task 3)。
     * null = 未小结 (可编辑); 非 null = 已小结转结到批次 (前端折叠为已小结区块，只读显示)。
     */
    private java.time.LocalDateTime interimSettledAt;
}
