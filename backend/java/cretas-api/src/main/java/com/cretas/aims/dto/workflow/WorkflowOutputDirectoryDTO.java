package com.cretas.aims.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 配置侧「按产出成品反查工艺图」的只读结果。
 *
 * <p>⛔ 与 {@link WorkflowOutputResolutionDTO} 是**两个入口, 两种语义**, 不要合并:
 * <ul>
 *   <li>本 DTO(配置侧) = **包含**语义 —— 只要这张图的终端产出里有这个成品就返回, 不做优先层收敛,
 *       不筛「最小超集」。目的是回答「哪些图会产出它」, 用来把用户领到那张图上。</li>
 *   <li>{@code WorkflowOutputResolutionDTO}(计划侧) = **精确**语义 —— 勾选集合必须等于终端集合,
 *       并且只返回最高优先层。目的是回答「这次生产计划该用哪张图」。</li>
 * </ul>
 * spec §4.5: 「这次是哪种语义」不能取决于调用方记得传什么布尔开关, 所以是两个接口。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowOutputDirectoryDTO {

    /** 被反查的成品 productTypeId。 */
    private String finishedGoodProductTypeId;

    /** 产出它的全部已启用工艺图; 为空表示没有任何图产出它(前端据此给明确空态, 不是空白画布)。 */
    private List<Entry> workflows;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        private Long workflowId;
        private Integer definitionVersion;

        /**
         * 这张图的**存放锚点**(product_process_workflows.product_type_id)。
         * 它只是存放位置 —— 打开这张图要用它, 但它不代表这张图只产出它。
         */
        private String ownerProductTypeId;
        private String ownerProductName;

        /** SINGLE_OUTPUT_PRODUCT / RAW_MATERIAL_SPLIT / JOINT_PRODUCTION。 */
        private String workflowType;

        /** 这张图的全部终端产出(副产已剔除), 顺序稳定。 */
        private List<TerminalOutput> terminalOutputs;

        /**
         * 锚点本身是否也是这张图的终端产出之一。
         *
         * <p>false = 用户真机看到的那种「顶部研判说原料分流、归属对象却写着某个成品」的图
         * (2026-08-11 wf=158)。前端据此把归属对象降级成次要信息并注明它只是存放位置。
         */
        private boolean anchorIsTerminalOutput;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TerminalOutput {
        private String productTypeId;
        private String productName;
    }
}
