package com.cretas.aims.dto.workflow;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 生产计划「选择成品 → 解析可用 Workflow 路线」的只读结果。
 *
 * <p>只返回当前最高优先层：有精确终端集合时返回全部精确候选；否则返回额外产出最少的
 * 同层超集候选。候选超过一条时由前端显式选择，不允许服务端静默取第一条。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowOutputResolutionDTO {

    private List<String> requestedProductTypeIds;

    /** SINGLE_OUTPUT / MULTI_OUTPUT / NONE。 */
    private String resolutionMode;

    /** Human-readable result for plan-creation UI. */
    private String message;

    private List<Candidate> candidates;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Candidate {
        private Long workflowId;
        private Integer definitionVersion;
        /** 建计划要锚定的 productTypeId (原料 或 成品自身)。 */
        private String ownerProductTypeId;
        private String ownerProductName;
        private String ownerProductCategory;
        /** owner 单位 (原料通常 kg) —— 前端投料量输入框单位提示。 */
        private String ownerUnit;
        /** 该 Workflow 下生产计划数量应使用的端口单位。 */
        private String plannedUnit;
        /** 该图全部终端成品。 */
        private List<TerminalOutput> terminalOutputs;
        /** 终端集合是否恰好等于所选集合。 */
        private boolean exactMatch;
        /** SINGLE_OUTPUT_PRODUCT / RAW_MATERIAL_SPLIT / JOINT_PRODUCTION. */
        private String workflowType;
        /** Root raw-material SKU set derived from the canvas, never guessed from the anchor. */
        private List<String> rootInputProductTypeIds;
        /** Root inputs after EXACTLY_ONE substitution groups collapse to one logical input. */
        private Integer logicalRootInputCount;
        /** Topologically ordered middle process names for fast candidate identification. */
        private List<String> processSteps;
        /** Sanitized read-only Cell graph for the plan-selection hover preview. */
        private List<PreviewNode> previewNodes;
        private List<PreviewEdge> previewEdges;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreviewNode {
        private String id;
        /** RAW_MATERIAL / PROCESS / SEMI_FINISHED / FINISHED_GOOD. */
        private String kind;
        private String label;
        /** Material base unit, or process input-to-output unit summary. */
        private String unit;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreviewEdge {
        private String id;
        private String source;
        private String target;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TerminalOutput {
        private String productTypeId;
        private String productName;
        private String unit;
    }

    /** 解析请求体 (POST /resolve-by-outputs)。 */
    @Data
    @NoArgsConstructor
    public static class Request {
        @NotEmpty(message = "productTypeIds 不能为空")
        private List<String> productTypeIds;
    }
}
