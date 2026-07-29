package com.cretas.aims.ai.tool.impl.sop;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SOP 复杂度分析工具
 *
 * <p>分析SOP文档的复杂度，评估1-5级。
 *
 * <p>评估维度:
 * <ul>
 *   <li>步骤数量: {@literal <}5步=1级, 5-10=2级, 10-15=3级, 15-20=4级, {@literal >}20=5级</li>
 *   <li>技能要求: 基础操作=1级, 需要培训=2-3级, 需要认证=4-5级</li>
 *   <li>特殊设备: 无=+0, 有=+1</li>
 *   <li>质检点数: {@literal <}2=+0, 2-5=+1, {@literal >}5=+2</li>
 * </ul>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-21
 */
@Slf4j
@Component
public class SopAnalyzeComplexityTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private DashScopeClient dashScopeClient;

    @Override
    public String getToolName() {
        return "sop_analyze_complexity";
    }

    @Override
    public String getDescription() {
        return "分析SOP文档的复杂度，评估1-5级。" +
               "基于步骤数量、技能要求、特殊设备、质检点等维度综合评估。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // sopContent: SOP内容（必需）
        Map<String, Object> sopContent = new HashMap<>();
        sopContent.put("type", "string");
        sopContent.put("description", "SOP文档的内容文本或解析后的JSON结构");
        properties.put("sopContent", sopContent);

        // steps: 步骤列表（可选，如果已解析）
        Map<String, Object> steps = new HashMap<>();
        steps.put("type", "array");
        steps.put("description", "已解析的步骤列表（JSON数组）");
        properties.put("steps", steps);

        // useAi: 是否使用AI分析（可选）
        Map<String, Object> useAi = new HashMap<>();
        useAi.put("type", "boolean");
        useAi.put("description", "是否使用AI进行复杂度分析。默认为true。");
        useAi.put("default", true);
        properties.put("useAi", useAi);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("sopContent"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("sopContent");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String sopContent = getString(params, "sopContent");
        Boolean useAi = getBoolean(params, "useAi", true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) params.get("steps");

        log.info("开始分析SOP复杂度: factoryId={}, contentLength={}, useAi={}",
                factoryId, sopContent.length(), useAi);

        ComplexityAnalysisResult result;

        if (useAi) {
            // 使用AI分析
            result = analyzeWithAi(sopContent, steps);
        } else {
            // 使用规则分析
            result = analyzeWithRules(sopContent, steps);
        }

        log.info("SOP复杂度分析完成: level={}, reason={}", result.getLevel(), result.getReason());

        // 构建返回结果
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("level", result.getLevel());
        response.put("levelDescription", getLevelDescription(result.getLevel()));
        response.put("reason", result.getReason());
        response.put("stepCount", result.getStepCount());
        response.put("skillRequired", result.getSkillRequired());
        response.put("qualityCheckCount", result.getQualityCheckCount());
        response.put("specialEquipment", result.isSpecialEquipment());
        response.put("estimatedMinutes", result.getEstimatedMinutes());
        response.put("analysisMethod", useAi ? "AI" : "RULE");
        response.put("message", String.format("复杂度分析完成：%d级（%s）",
                result.getLevel(), getLevelDescription(result.getLevel())));

        return response;
    }

    /**
     * 使用AI分析复杂度
     */
    private ComplexityAnalysisResult analyzeWithAi(String sopContent, List<Map<String, Object>> steps) {
        String prompt = String.format("""
            分析以下SOP文档的复杂度，评估1-5级:

            评估维度:
            - 步骤数量: <5步=1级, 5-10=2级, 10-15=3级, 15-20=4级, >20=5级
            - 技能要求: 基础操作=1级, 需要培训=2-3级, 需要认证=4-5级
            - 特殊设备: 无=+0, 有=+1
            - 质检点数: <2=+0, 2-5=+1, >5=+2

            SOP内容:
            %s

            %s

            请以JSON格式输出:
            {
                "level": 1-5,
                "reason": "分析理由",
                "stepCount": N,
                "skillRequired": 1-5,
                "qualityCheckCount": N,
                "specialEquipment": true/false,
                "estimatedMinutes": N
            }
            """,
                sopContent,
                steps != null ? "步骤信息: " + steps.toString() : ""
        );

        try {
            String response = dashScopeClient.chat(
                    "你是一个SOP复杂度分析专家，专门评估食品加工厂的标准操作流程复杂程度。",
                    prompt
            );

            return parseAiResponse(response);
        } catch (Exception e) {
            log.error("AI分析失败，回退到规则分析: {}", e.getMessage());
            return analyzeWithRules(sopContent, steps);
        }
    }

    /**
     * 使用规则分析复杂度
     */
    private ComplexityAnalysisResult analyzeWithRules(String sopContent, List<Map<String, Object>> steps) {
        ComplexityAnalysisResult result = new ComplexityAnalysisResult();

        // 计算步骤数
        int stepCount = 0;
        if (steps != null && !steps.isEmpty()) {
            stepCount = steps.size();
        } else {
            // 从内容中估算步骤数
            stepCount = countStepsFromContent(sopContent);
        }
        result.setStepCount(stepCount);

        // 基于步骤数计算基础复杂度
        int baseLevel;
        if (stepCount < 5) baseLevel = 1;
        else if (stepCount < 10) baseLevel = 2;
        else if (stepCount < 15) baseLevel = 3;
        else if (stepCount < 20) baseLevel = 4;
        else baseLevel = 5;

        // 分析技能要求
        int skillRequired = analyzeSkillRequirement(sopContent, steps);
        result.setSkillRequired(skillRequired);

        // 分析质检点
        int qualityCheckCount = countQualityChecks(sopContent, steps);
        result.setQualityCheckCount(qualityCheckCount);

        // 检查特殊设备
        boolean specialEquipment = hasSpecialEquipment(sopContent, steps);
        result.setSpecialEquipment(specialEquipment);

        // 计算最终复杂度
        int adjustment = 0;
        if (skillRequired >= 4) adjustment += 1;
        if (specialEquipment) adjustment += 1;
        if (qualityCheckCount >= 2 && qualityCheckCount <= 5) adjustment += 1;
        else if (qualityCheckCount > 5) adjustment += 2;

        int finalLevel = Math.min(5, Math.max(1, baseLevel + adjustment));
        result.setLevel(finalLevel);

        // 估算工时
        int estimatedMinutes = stepCount * 5 + qualityCheckCount * 3;
        result.setEstimatedMinutes(estimatedMinutes);

        // 生成理由
        StringBuilder reason = new StringBuilder();
        reason.append(String.format("基于%d个步骤（基础%d级）", stepCount, baseLevel));
        if (skillRequired >= 4) reason.append("，需要高级技能(+1)");
        if (specialEquipment) reason.append("，需要特殊设备(+1)");
        if (qualityCheckCount > 2) reason.append(String.format("，%d个质检点(+%d)", qualityCheckCount, qualityCheckCount > 5 ? 2 : 1));
        reason.append("。");
        result.setReason(reason.toString());

        return result;
    }

    /**
     * 从内容中估算步骤数
     */
    private int countStepsFromContent(String content) {
        if (content == null || content.isEmpty()) return 0;

        // 尝试匹配步骤模式
        int count = 0;

        // 匹配 "步骤1"、"第一步"、"1."、"1)" 等模式
        Pattern stepPattern = Pattern.compile("(?:步骤|第)[一二三四五六七八九十\\d]+|\\d+[.、)）]");
        Matcher matcher = stepPattern.matcher(content);
        while (matcher.find()) {
            count++;
        }

        // 如果没有匹配到，按换行估算
        if (count == 0) {
            count = content.split("\n").length / 3; // 假设每3行一个步骤
        }

        return Math.max(1, count);
    }

    /**
     * 分析技能要求
     */
    private int analyzeSkillRequirement(String content, List<Map<String, Object>> steps) {
        if (steps != null && !steps.isEmpty()) {
            return steps.stream()
                    .filter(s -> s.get("skillLevel") != null)
                    .mapToInt(s -> {
                        Object skill = s.get("skillLevel");
                        return skill instanceof Number ? ((Number) skill).intValue() : 1;
                    })
                    .max()
                    .orElse(2);
        }

        // 从内容分析
        if (content.contains("认证") || content.contains("资质") || content.contains("专业")) {
            return 4;
        } else if (content.contains("培训") || content.contains("熟练")) {
            return 3;
        } else if (content.contains("基础") || content.contains("简单")) {
            return 1;
        }
        return 2;
    }

    /**
     * 统计质检点
     */
    private int countQualityChecks(String content, List<Map<String, Object>> steps) {
        if (steps != null && !steps.isEmpty()) {
            return (int) steps.stream()
                    .filter(s -> Boolean.TRUE.equals(s.get("isQualityCheckpoint")))
                    .count();
        }

        // 从内容分析
        int count = 0;
        String[] keywords = {"质检", "检验", "检测", "抽查", "确认", "核验", "品控"};
        for (String keyword : keywords) {
            int idx = 0;
            while ((idx = content.indexOf(keyword, idx)) != -1) {
                count++;
                idx++;
            }
        }
        return count;
    }

    /**
     * 检查是否需要特殊设备
     */
    private boolean hasSpecialEquipment(String content, List<Map<String, Object>> steps) {
        if (steps != null && !steps.isEmpty()) {
            return steps.stream()
                    .anyMatch(s -> {
                        Object equipment = s.get("equipmentRequired");
                        return equipment instanceof List && !((List<?>) equipment).isEmpty();
                    });
        }

        // 从内容分析
        String[] specialEquipments = {
                "冷冻设备", "速冻机", "真空包装", "金属探测", "X光",
                "自动化", "机器人", "PLC", "传感器", "温控设备"
        };
        for (String equipment : specialEquipments) {
            if (content.contains(equipment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析AI响应
     */
    private ComplexityAnalysisResult parseAiResponse(String response) {
        ComplexityAnalysisResult result = new ComplexityAnalysisResult();

        try {
            // 提取JSON
            String jsonStr = extractJson(response);
            if (jsonStr != null) {
                Map<String, Object> parsed = objectMapper.readValue(jsonStr,
                        new TypeReference<Map<String, Object>>() {});

                result.setLevel(getIntValue(parsed, "level", 3));
                result.setReason((String) parsed.getOrDefault("reason", "AI分析结果"));
                result.setStepCount(getIntValue(parsed, "stepCount", 0));
                result.setSkillRequired(getIntValue(parsed, "skillRequired", 2));
                result.setQualityCheckCount(getIntValue(parsed, "qualityCheckCount", 0));
                result.setSpecialEquipment(Boolean.TRUE.equals(parsed.get("specialEquipment")));
                result.setEstimatedMinutes(getIntValue(parsed, "estimatedMinutes", 30));

                // 确保level在1-5范围内
                result.setLevel(Math.min(5, Math.max(1, result.getLevel())));
            }
        } catch (Exception e) {
            log.warn("AI响应解析失败: {}", e.getMessage());
            result.setLevel(3);
            result.setReason("AI分析结果解析失败，使用默认值");
        }

        return result;
    }

    /**
     * 从文本中提取JSON
     */
    private String extractJson(String text) {
        Pattern jsonPattern = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```|\\{[\\s\\S]*\\}");
        Matcher matcher = jsonPattern.matcher(text);
        if (matcher.find()) {
            String match = matcher.group(1) != null ? matcher.group(1) : matcher.group();
            return match.trim();
        }
        return null;
    }

    /**
     * 安全获取整数值
     */
    private int getIntValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 获取复杂度等级描述
     */
    private String getLevelDescription(int level) {
        switch (level) {
            case 1: return "简单";
            case 2: return "较简单";
            case 3: return "中等";
            case 4: return "较复杂";
            case 5: return "复杂";
            default: return "未知";
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("sopContent".equals(paramName)) {
            return "请提供SOP文档内容或解析后的步骤信息。";
        }
        return super.getParameterQuestion(paramName);
    }

    /**
     * 复杂度分析结果内部类
     */
    private static class ComplexityAnalysisResult {
        private int level;
        private String reason;
        private int stepCount;
        private int skillRequired;
        private int qualityCheckCount;
        private boolean specialEquipment;
        private int estimatedMinutes;

        public int getLevel() { return level; }
        public void setLevel(int level) { this.level = level; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public int getStepCount() { return stepCount; }
        public void setStepCount(int stepCount) { this.stepCount = stepCount; }
        public int getSkillRequired() { return skillRequired; }
        public void setSkillRequired(int skillRequired) { this.skillRequired = skillRequired; }
        public int getQualityCheckCount() { return qualityCheckCount; }
        public void setQualityCheckCount(int qualityCheckCount) { this.qualityCheckCount = qualityCheckCount; }
        public boolean isSpecialEquipment() { return specialEquipment; }
        public void setSpecialEquipment(boolean specialEquipment) { this.specialEquipment = specialEquipment; }
        public int getEstimatedMinutes() { return estimatedMinutes; }
        public void setEstimatedMinutes(int estimatedMinutes) { this.estimatedMinutes = estimatedMinutes; }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
