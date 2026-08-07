package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.finding.FindingActionPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
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

    private final FindingActionPlanService findingActionPlanService;

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
        // ⚠️ 这里的领域**固定是 inventory**, 而且必须是: 本 Tool 的 name/description
        // 都写着「库存异常」, 语义路由是按那段描述选中它的。改成跟随租户类型会让
        // 一个自称管库存的工具悄悄去答餐饮问题 —— 名字与行为对不上。
        //
        // 餐饮走的是另一条路: FindingController 的 REST 出口(带 domain 参数)。
        // 之所以不靠 Tool, 是因为餐饮提问在到达 Java Tool 之前就被 tiered 路由
        // 委派给 Python 了 —— 挂在 Tool 上等于挂在没人走的路上。
        return findingActionPlanService.generate(factoryId, DOMAIN);
    }
}
