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
     * 默认责任小组长 ID（主/AI 草稿值）。
     * <ul>
     *   <li>null (omitted) — 不修改现有值 (update 语义: no-change)</li>
     *   <li>-1L (sentinel) — 清空，wire-only，不持久化</li>
     *   <li>正数 — 设置为对应用户 ID</li>
     * </ul>
     */
    private Long responsibleWorkerId;

    /**
     * T121: 多人负责列表。
     *
     * <p><b>写操作 (create/update)</b>: 前端传 worker id 数组，后端对 join 表做 upsert（删旧加新）。
     * 若为 null 或空 → 行为回退到 responsibleWorkerId 单值语义（向后兼容）。
     * 若非空 → assigneeWorkerIds[0] 同时被写入 responsible_worker_id（primary 保持同步）。
     *
     * <p><b>读操作 (list/get)</b>: 后端填充 join 表查询结果，供前端多选下拉回显。
     */
    private List<Long> assigneeWorkerIds;

    private Boolean isActive;

    /**
     * 是否需要报工 (六扇门 Wave2 — 可配置报工粒度)。
     * <ul>
     *   <li>null (omitted) — create 取默认 true; update 不修改现有值 (no-change 语义)</li>
     *   <li>true — 逐道报 (默认)</li>
     *   <li>false — 该工序免报 (spawn 跳过, 不生成报工任务; 配置保留供溯源)</li>
     * </ul>
     */
    private Boolean reportingRequired;

    /**
     * 工序成本配置 (报工自动继承, 防呆: 操作员不手填会计类别/明细)。
     * partial update 语义: null → 不修改现有值。
     */
    private String defaultCostCategory;           // CALC-003 默认成本类别
    private java.util.List<java.util.Map<String, Object>> packagingTemplate;  // AUDIT-002 默认包装明细模板 [{name,cost}]
    private String auxAllocMethod;                 // AUDIT-004 辅料分摊方式 BY_OUTPUT/FIXED_RATIO
    private java.math.BigDecimal standardYieldRate; // 段2(B) 标准出成率 (配方率, 0.85=85%); 投料-产出对账基准
    private java.math.BigDecimal auxUnitPrice;      // 段2(B) 辅料标准单价 元/kg; null 视为 0 不崩
    private String auxBasis;                         // 段2(B) 元/kg 乘哪侧 INPUT|OUTPUT (保水工序必须显式)

    // Read-only fields populated from joined WorkProcess
    private String processName;
    private String processCategory;
    private String defaultUnit;
    private Integer defaultEstimatedMinutes;

    /**
     * T135: Read-only display name for the responsible worker (primary assignee).
     * Populated by listByProduct only; not sent on writes.
     */
    private String responsibleWorkerName;

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
