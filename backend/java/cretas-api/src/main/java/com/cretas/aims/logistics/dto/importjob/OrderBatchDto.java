package com.cretas.aims.logistics.dto.importjob;

import com.cretas.aims.logistics.entity.enums.OrderBatchStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 镜像前端 {@code web-admin/src/api/logistics.ts} {@code OrderBatch}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderBatchDto {
    private String id;
    private String factoryId;
    private LocalDate businessDate;
    private String batchNumber;
    private String sourceFilename;
    private OrderBatchStatus status;
    private Integer totalRows;
    private Integer validRows;
    private Integer errorRows;
    private String createdBy;
    private LocalDateTime createdAt;
    private Long version;
}
