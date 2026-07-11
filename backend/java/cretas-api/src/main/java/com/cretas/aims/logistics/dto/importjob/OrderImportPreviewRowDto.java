package com.cretas.aims.logistics.dto.importjob;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * preview 阶段单行解析结果 (含校验状态，不代表已写库) — 镜像前端
 * {@code web-admin/src/api/logistics.ts} {@code OrderImportPreviewRow}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderImportPreviewRowDto {
    private Integer rowNumber;
    private String storeCode;
    private String storeName;
    private String address;
    private String areaCode;
    private Integer pieces;
    private Integer boxes;
    private BigDecimal weightKg;
    private BigDecimal volumeCbm;
    private String windowStart;
    private String windowEnd;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private boolean valid;
    private List<RowErrorDto> errors;
}
