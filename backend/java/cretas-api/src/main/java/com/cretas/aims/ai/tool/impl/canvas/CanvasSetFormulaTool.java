package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.config.FactoryFormula;
import com.cretas.aims.repository.config.FactoryFormulaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas 聚合公式设置工具
 *
 * 设置模块级聚合公式，如按税率分组汇总金额、计算加权平均成本等。
 * 公式以 resultType=AGGREGATE 保存，供报表和统计引擎调用。
 *
 * Intent Code: CANVAS_SET_FORMULA
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-04-09
 */
@Slf4j
@Component
public class CanvasSetFormulaTool extends AbstractBusinessTool {

    @Autowired
    private FactoryFormulaRepository formulaRepo;

    @Override
    public String getToolName() {
        return "canvas_set_formula";
    }

    @Override
    public String getDescription() {
        return "设置聚合公式（如按税率分组汇总金额、计算加权平均成本）。适用场景：在 Canvas 配置界面定义自定义汇总逻辑，供报表和统计引擎使用。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> moduleCode = new HashMap<>();
        moduleCode.put("type", "string");
        moduleCode.put("description", "模块代码，如 PURCHASE、SALES、FINANCE 等");
        properties.put("moduleCode", moduleCode);

        Map<String, Object> formulaCode = new HashMap<>();
        formulaCode.put("type", "string");
        formulaCode.put("description", "公式代码，唯一标识，如 taxGroupSum、weightedAvgCost 等（建议英文驼峰）");
        properties.put("formulaCode", formulaCode);

        Map<String, Object> expression = new HashMap<>();
        expression.put("type", "string");
        expression.put("description", "公式表达式，如 SUM(amount) GROUP BY taxRate 或 SUM(cost*qty)/SUM(qty)");
        properties.put("expression", expression);

        Map<String, Object> description = new HashMap<>();
        description.put("type", "string");
        description.put("description", "（可选）公式说明，描述该公式的业务含义");
        properties.put("description", description);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("moduleCode", "formulaCode", "expression"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("moduleCode", "formulaCode", "expression");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String moduleCode = getString(params, "moduleCode");
        String formulaCode = getString(params, "formulaCode");
        String expression = getString(params, "expression");
        String description = params.containsKey("description") ? getString(params, "description") : null;

        log.info("Canvas 设置公式 - 工厂ID: {}, 模块: {}, 公式: {}", factoryId, moduleCode, formulaCode);

        FactoryFormula formula = FactoryFormula.builder()
                .factoryId(factoryId)
                .moduleCode(moduleCode)
                .formulaCode(formulaCode)
                .expression(expression)
                .resultType("AGGREGATE")
                .description(description)
                .build();

        FactoryFormula saved = formulaRepo.save(formula);

        log.info("Canvas 设置公式完成 - ID: {}, 公式: {}.{}", saved.getId(), moduleCode, formulaCode);

        Map<String, Object> result = new HashMap<>();
        result.put("id", saved.getId());
        result.put("factoryId", factoryId);
        result.put("moduleCode", moduleCode);
        result.put("formulaCode", formulaCode);
        result.put("expression", expression);
        result.put("resultType", "AGGREGATE");
        if (description != null) {
            result.put("description", description);
        }

        return buildSimpleResult(
                "聚合公式 " + formulaCode + " 已保存到模块 " + moduleCode,
                result
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "moduleCode", "请问要给哪个模块设置聚合公式？请提供模块代码（如 PURCHASE、SALES 等）。",
                "formulaCode", "请问公式的代码是什么？建议英文驼峰，如 taxGroupSum、weightedAvgCost。",
                "expression", "请问公式表达式是什么？如 SUM(amount) GROUP BY taxRate 或 SUM(cost*qty)/SUM(qty)。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "moduleCode", "模块代码",
                "formulaCode", "公式代码",
                "expression", "公式表达式",
                "description", "公式说明"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
