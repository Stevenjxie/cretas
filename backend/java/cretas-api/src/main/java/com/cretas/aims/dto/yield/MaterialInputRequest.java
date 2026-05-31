package com.cretas.aims.dto.yield;

import lombok.Data;

import java.math.BigDecimal;

/** 领料环节记 出库量+投料量 (张权 A1) — PUT /material-input */
@Data
public class MaterialInputRequest {
    private Long workProcessTaskId;       // 首道工序任务
    private BigDecimal warehouseOutQuantity;  // 出库量 998
    private BigDecimal feedInQuantity;        // 投料量 935.5
    private String inputUnit;              // kg
}
