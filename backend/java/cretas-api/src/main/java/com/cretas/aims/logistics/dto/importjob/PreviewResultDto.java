package com.cretas.aims.logistics.dto.importjob;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * {@code POST /order-import/preview} 响应体 — 镜像前端
 * {@code web-admin/src/api/logistics.ts} {@code PreviewResult}。
 *
 * <p>{@code jobId} = 幂等 upsert 后的 {@link com.cretas.aims.logistics.entity.LogisticsOrderBatch#getId()}，
 * 供 {@code POST /order-import/{jobId}/commit} 使用。preview 阶段已把有效行落库到
 * {@code logistics_delivery_orders}（批次 status=PREVIEWED，不计入"live"查询 —
 * 见 {@code LogisticsOrderImportServiceImpl} 查询规则说明），但不算已提交。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreviewResultDto {
    private String jobId;
    private String businessDate;
    private String sourceFilename;
    private int totalRows;
    private int validRows;
    private int errorRows;
    private List<RowErrorDto> rowErrors;
    private List<OrderImportPreviewRowDto> rows;
}
