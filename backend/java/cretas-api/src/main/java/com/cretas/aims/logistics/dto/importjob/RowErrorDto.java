package com.cretas.aims.logistics.dto.importjob;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 逐行/逐列导入错误 — 前端 {@code web-admin/src/api/logistics.ts} {@code RowError} 的
 * 后端镜像 (字段名一致: rowNumber/column/message)。
 *
 * <p>{@code rowNumber} 是 1-based 数据行号 (不含表头，文件第一条业务数据 = 1)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RowErrorDto {
    private Integer rowNumber;
    private String column;
    private String message;
}
