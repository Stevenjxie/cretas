package com.cretas.aims.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
        private String materialKind;
        private String skuId;
        private String materialName;
        private String unit;
        private Boolean required;
        /** false 时 skuId 已无法解析 (物料/产品被删除) — FE 应显示 "SKU 已失效, 请回 Workflow 配置" 提示。 */
        private Boolean skuResolved;
        /** 仅 output 端口有意义: materialKind == FINISHED_GOOD。 */
        private Boolean finished;
    }
}
