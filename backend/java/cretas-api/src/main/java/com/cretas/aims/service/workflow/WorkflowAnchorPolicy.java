package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 「这张画布应该归在谁名下」——把 {@link WorkflowTopology} 的研判结论翻译成归属对象(存放位置)。
 *
 * <p><b>为什么需要它</b>: 研判早就能认出「原料分流」, 顶部也把结论显示出来了, 但**没有任何代码
 * 拿这个结论去改归属对象** —— 一张单原料分流成 C、D 两个成品的图, 归属对象还钉在成品 C 上,
 * 让人以为这张图是成品 C 的。标注不等于根治 (Steve 2026-08-11)。
 *
 * <p><b>规则</b> (与 Steve 定的一致):
 * <table>
 *   <tr><th>研判</th><th>归属对象</th></tr>
 *   <tr><td>单成品 (SINGLE_OUTPUT_PRODUCT)</td><td>那个成品</td></tr>
 *   <tr><td>单原料多成品 (RAW_MATERIAL_SPLIT)</td><td><b>那个共享原料</b></td></tr>
 *   <tr><td>多原料多成品 (JOINT_PRODUCTION)</td><td><b>不动</b> —— 见下</td></tr>
 *   <tr><td>INVALID</td><td>不动</td></tr>
 * </table>
 *
 * <p><b>为什么联产不自动搬</b>: 联产是 N 进 M 出, 既没有唯一的原料也没有唯一的成品, 任选一个
 * 当归属都是**编出来的**。系统在这里随便挑一个, 比留在原地更误导 —— 用户会以为那个选择有含义。
 * 所以联产保持现状, 由顶部的「系统研判: 联产」如实说明。要给联产一个真正的锚点是产品决策,
 * 不是这里补一行代码能定的。
 *
 * <p><b>替代料</b>: 一组互为替代的根原料 (substituteOfNodeId 串起来) 算一个逻辑投入,
 * 所以 RAW_MATERIAL_SPLIT 下 {@code rootInputSkuIds} 可能有多个。取那个**不是别人替代品**的
 * 作为主原料 —— 它是这一组的原点。全都互指(不该发生, validator 拦了)时退回排序首位, 保证确定性。
 */
public final class WorkflowAnchorPolicy {

    private WorkflowAnchorPolicy() {
    }

    /**
     * @return 这张图应该归属的对象 id; {@link Optional#empty()} 表示**不要动**当前归属对象
     *         (联产 / 结构不完整 / 拿不到可靠结论)。
     */
    public static Optional<String> desiredOwner(
            ProductProcessWorkflowDTO definition, WorkflowTopology topology) {
        if (definition == null || topology == null) return Optional.empty();
        return switch (topology.type()) {
            case SINGLE_OUTPUT_PRODUCT -> topology.terminalOutputSkuIds().stream().findFirst();
            case RAW_MATERIAL_SPLIT -> primaryRootSku(definition, topology);
            // 联产与结构不完整一律不动 —— 见类注释。
            case JOINT_PRODUCTION, INVALID -> Optional.empty();
        };
    }

    /**
     * 替代组里的主原料 —— 没有 {@code substituteOfNodeId} 的那个根原料。
     *
     * <p>只在 RAW_MATERIAL_SPLIT 下调用, 此时 logicalRootCount == 1, 即所有根原料同属一组。
     */
    private static Optional<String> primaryRootSku(
            ProductProcessWorkflowDTO definition, WorkflowTopology topology) {
        if (topology.rootInputSkuIds().isEmpty()) return Optional.empty();
        if (topology.rootInputSkuIds().size() == 1) {
            return Optional.of(topology.rootInputSkuIds().get(0));
        }
        Set<String> rootSkus = new HashSet<>(topology.rootInputSkuIds());
        if (definition.getNodes() != null) {
            for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
                if (node == null || node.getData() == null) continue;
                String skuId = stringValue(node.getData(), "skuId");
                if (skuId == null || !rootSkus.contains(skuId)) continue;
                if (stringValue(node.getData(), "substituteOfNodeId") == null) {
                    return Optional.of(skuId);
                }
            }
        }
        // 全都互指(validator 应已拦下): 退回排序首位, 只求确定性, 不猜语义。
        return Optional.of(topology.rootInputSkuIds().get(0));
    }

    private static String stringValue(Map<String, Object> data, String key) {
        Object raw = data.get(key);
        if (!(raw instanceof String text)) return null;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
