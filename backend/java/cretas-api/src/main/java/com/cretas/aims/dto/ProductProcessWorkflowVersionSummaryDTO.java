package com.cretas.aims.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 产品工序 Workflow 版本历史摘要（只读）。
 *
 * <p>覆盖某个产品的全部版本行（DRAFT + PUBLISHED），供版本历史列表展示。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductProcessWorkflowVersionSummaryDTO {

    private Integer definitionVersion;
    private String status;
    private LocalDateTime updatedAt;
    private boolean active;
}
