package com.cretas.aims.dto.workflow;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * raw-centric 多成品 (2026-07-13): 生产计划「多选成品 → 解析共用 raw workflow」的解析结果。
 *
 * <p>单选优先该成品自己的图 (SELF_WORKFLOW); 多选只匹配以原料为锚、终端覆盖所选全部成品的图
 * (RAW_OWNED); 无覆盖图 = NONE (空候选, 不报错 —— 报错留写路径守卫)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowOutputResolutionDTO {

    private List<String> requestedProductTypeIds;

    /** SELF_WORKFLOW(单选命中成品自有图) / RAW_OWNED(原料图候选) / NONE(0 候选)。 */
    private String resolutionMode;

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
