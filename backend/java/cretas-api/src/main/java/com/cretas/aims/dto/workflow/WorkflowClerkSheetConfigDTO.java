package com.cretas.aims.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.math.BigDecimal;

/**
 * 2B Task B2: 把 workflow 快照投影成文员逐工序过程单 (clerk 过程单) 可消费的结构。
 *
 * <p>Thin projection — 不引入新的报工引擎, FE {@code resolveProcesses()} 拿到这份配置后
 * 复用现有 saveRow → materializeBatch → interim-settle 全部库存/成本/结转逻辑不变。
 *
 * <p>legacy (非 workflow) 计划: service 返回 {@code null}, controller 用
 * {@code ApiResponse.success(null)} 包装, FE 据此回落原 {@code getProductWorkProcesses} 路径。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowClerkSheetConfigDTO {

    private Long workflowBatchId;
    private Long workflowInstanceId;
    private String productTypeId;
    private List<ProcessDescriptor> processes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessDescriptor {
        private String workflowNodeId;
        private String workProcessId;
        private String processName;
        /** 工序类别 (WorkProcess.processCategory, 如 熟制/注射/加工) — 调料配方按工序: 报工据此驱动锅数录入。 */
        private String processCategory;
        private String defaultCostCategory;
        private Integer processOrder;
        private String plannedUnit;
        private Boolean allowMultipleUpstreamSources;
        private Boolean allowFinishedGoodsSource;
        private Object customFieldSchema;
        private List<PortDescriptor> inputs;
        /**
         * 首个产出端口。向后兼容单产出 FE (2B); 多产出时 == {@code outputs.get(0)}。
         */
        private PortDescriptor output;
        /**
         * 2B.2: 全部产出端口 (按 ordinal 排序)。单产出时 size==1。多产出时 FE 逐端口录入 N 条产出。
         */
        private List<PortDescriptor> outputs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PortDescriptor {
        private String workflowPortId;
        /** 2B.2 端口身份: 对应 workflow 物料 Cell (节点) id (供 FE 回填 output line 的端口身份)。 */
        private String materialNodeId;
        private String materialKind;
        private String skuId;
        /**
         * Exact material candidates authorized for this logical input by the
         * production plan's pinned BOM (main material plus structured substitutes).
         * Empty is never interpreted as "all materials".
         */
        private List<String> allowedSkuIds;
        private String materialName;
        private String unit;
        /** Workflow 运行时物化时锁定的 SKU 每基本单位净重（克）。 */
        private BigDecimal gramsPerUnit;
        private Boolean required;
        private String selectionGroupId;
        private String selectionGroupLabel;
        private String selectionGroupMode;
        private Integer selectionGroupMinSelections;
        private Integer selectionGroupMaxSelections;
        /** false 时 skuId 已无法解析 (物料/产品被删除) — FE 应显示 "SKU 已失效, 请回 Workflow 配置" 提示。 */
        private Boolean skuResolved;
        /** 仅 output 端口有意义: materialKind == FINISHED_GOOD。 */
        private Boolean finished;
    }
}
