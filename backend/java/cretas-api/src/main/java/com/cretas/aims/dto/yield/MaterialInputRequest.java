package com.cretas.aims.dto.yield;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 领料环节记 出库量+投料量 (张权 A1) — PUT /material-input */
@Data
public class MaterialInputRequest {
    private Long workProcessTaskId;       // 首道工序任务
    private BigDecimal warehouseOutQuantity;  // 出库量 998
    private BigDecimal feedInQuantity;        // 投料量 935.5
    private String inputUnit;              // kg
    /** A2b: 领料关联原料批次列表 (1 或 N; 可选 — 仓管员不填时退化为人工结清路径) */
    private List<MaterialBatchRef> materialBatchRefs;
    /**
     * F4 补录时效锁: 领料业务日期 (可选; null = 今日实时领料, 不拦)。
     * 与 YieldReportRequest.businessDate 同语义 — 传入时经 BackdateWindowValidator 校验。
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate businessDate;
}
