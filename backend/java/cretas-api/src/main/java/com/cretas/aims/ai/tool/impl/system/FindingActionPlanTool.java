package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.grounding.GroundedNumberValidator;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发现 → 行动建议 —— 第 ④ 块「策划案生成」的最小切片。
 *
 * <p>这是发现层（{@link FindingService}）的<b>第二个出口</b>：第一个出口是查询时
 * 的顺带提示（模板渲染），这一个把同一批结构化发现交给 LLM 写成一段可执行的建议。
 * 当初把发现独立成一层、并规定 {@code Finding} 只装数据不装话术，就是为了这一刻
 * ——两个出口共用同一套规则，不会各写各的口径。
 *
 * <h2>LLM 的职责边界</h2>
 *
 * <ul>
 *   <li><b>LLM 做</b>：把干巴巴的事实组织成人话、排出先后、给出可执行动作</li>
 *   <li><b>LLM 不做</b>：产生任何数字。所有数量、缺口、比例都来自发现层</li>
 * </ul>
 *
 * <p>这条边界不靠嘱咐，靠 {@link GroundedNumberValidator} 在事后卡住：产出里出现
 * 事实之外的数字 → <b>抛异常拒绝这次生成</b>，不把文案发出去。一个编了数字的行动
 * 建议比没有建议更糟——它会被照着执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FindingActionPlanTool extends AbstractBusinessTool {

    private static final String DOMAIN = "inventory";

    /**
     * 提示词刻意要求「·」分点且禁用数字编号。
     *
     * <p>不是排版偏好：序号会让「第 1 步」里的 1 混进数字流，逼校验给小整数开
     * 后门，而后门一开「缺 12kg」这类真该管的数字也会漏过去。约束格式是让严格
     * 校验成为可能的前提。
     */
    private static final String SYSTEM_PROMPT = """
            你是食品加工厂的生产助理。根据给出的异常事实，写一段简短的行动建议。

            硬性要求：
            1. 只能使用事实中已给出的数字。禁止计算、估算、推断任何新数字，
               禁止出现事实里没有的金额、百分比、天数。
            2. 用「·」开头分点，每点一行。禁止使用「1.」「2.」「第3步」这类数字编号。
            3. 不确定的地方就不写，不要用「大约」「预计」来掩饰。
            4. 每点不超过 30 字，总共不超过 4 点。
            """;

    private final FindingService findingService;
    private final DashScopeClient dashScopeClient;
    private final GroundedNumberValidator groundedNumberValidator;

    @Override
    public String getToolName() {
        return "system_finding_action_plan";
    }

    @Override
    public String getDescription() {
        return "把当前库存异常整理成一段可执行的行动建议（先做什么、再做什么）。"
                + "数字全部来自系统检测结果，不做估算。"
                + "适用场景：'现在该干什么'、'给我个处理方案'、'这些问题怎么办'。";
    }

    @Override
    public ActionType getActionType() {
        return ActionType.GENERATE;
    }

    /** 只读发现层 + 调用模型，不写任何业务数据。 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        FindingService.Result findingResult = findingService.detectInline(factoryId, DOMAIN);

        // 发现层没跑完就生成建议 = 拿不完整的事实去指挥行动。宁可不给建议。
        if (!findingResult.complete()) {
            throw new IllegalStateException(
                    "异常检测未完整执行, 拒绝据此生成行动建议: 失败规则=" + findingResult.failedRules());
        }

        List<Finding> findings = findingResult.findings();
        if (findings.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("hasPlan", false);
            empty.put("plan", null);
            empty.put("message", "当前没有需要处理的库存异常，无需行动建议。");
            return empty;
        }

        List<Object> facts = buildFacts(findings);
        String plan = dashScopeClient.chatLowTemp(SYSTEM_PROMPT, renderFacts(facts));

        if (plan == null || plan.isBlank()) {
            throw new IllegalStateException("模型未产出行动建议, 拒绝返回空方案");
        }

        // 事后卡住 LLM 编数字。一个编了数字的行动建议比没有建议更糟 —— 它会被照着执行。
        List<String> ungrounded = groundedNumberValidator.findUngroundedNumbers(plan, facts);
        if (!ungrounded.isEmpty()) {
            log.warn("行动建议含无据数字, 已拒绝: factoryId={}, ungrounded={}", factoryId, ungrounded);
            throw new IllegalStateException(
                    "生成的行动建议里出现了系统数据中不存在的数字 " + ungrounded + "，已拒绝返回");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasPlan", true);
        result.put("plan", plan);
        result.put("basedOnFindings", facts.size());
        result.put("facts", facts);
        result.put("message", plan);
        return result;
    }

    /** 只把渲染要用到的字段交给模型，减少它顺手编造的空间。 */
    private List<Object> buildFacts(List<Finding> findings) {
        List<Object> facts = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("对象", f.subjectName());
            item.putAll(f.facts());
            facts.add(item);
        }
        return facts;
    }

    private String renderFacts(List<Object> facts) {
        StringBuilder sb = new StringBuilder("当前异常事实：\n");
        for (Object fact : facts) {
            sb.append(fact).append('\n');
        }
        return sb.toString();
    }
}
