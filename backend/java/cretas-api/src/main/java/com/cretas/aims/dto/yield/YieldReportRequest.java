package com.cretas.aims.dto.yield;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 工人逐道报工 投入+产出双量 — POST /reports */
@Data
public class YieldReportRequest {
    private Long workProcessTaskId;
    private BigDecimal inputQuantity;     // 本道投入 (前端预填上道产出, 可改)
    private String inputUnit;
    private BigDecimal outputQuantity;    // 本道产出
    private String outputUnit;
    private Integer workMinutes;          // 选填工时
    private Boolean forceSubmit;          // A4 超收软告警后强制提交
    /** 跨批来源 (张权 A3), 见 ProductionReport.sourceBatchRefs 形状 */
    private List<Map<String, Object>> sourceBatchRefs;
    private String reporterName;
    private Long targetWorkerId;          // 代报工 (主管替工人提交)
    /** A2b: 首道领料批次引用 (与 materialBatchRefs 字段合并到报工单, 不再单独调用 recordMaterialInput) */
    private List<MaterialBatchRef> materialBatchRefs;
}
