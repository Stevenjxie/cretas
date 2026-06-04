package com.cretas.aims.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductWorkProcessDTO {

    private Long id;

    @NotBlank(message = "产品类型ID不能为空")
    private String productTypeId;

    @NotBlank(message = "工序ID不能为空")
    private String workProcessId;

    private Integer processOrder;

    private String unitOverride;

    private Integer estimatedMinutesOverride;

    /**
     * 默认责任小组长 ID。
     * <ul>
     *   <li>null (omitted) — 不修改现有值 (update 语义: no-change)</li>
     *   <li>-1L (sentinel) — 清空，wire-only，不持久化</li>
     *   <li>正数 — 设置为对应用户 ID</li>
     * </ul>
     */
    private Long responsibleWorkerId;

    private Boolean isActive;

    // Read-only fields populated from joined WorkProcess
    private String processName;
    private String processCategory;
    private String defaultUnit;
    private Integer defaultEstimatedMinutes;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchSortRequest {
        private List<SortItem> items;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SortItem {
        private Long id;
        private Integer processOrder;
    }
}
