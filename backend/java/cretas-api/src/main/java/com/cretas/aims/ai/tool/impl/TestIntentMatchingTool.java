package com.cretas.aims.ai.tool.impl;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractTool;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.AIIntentService;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 测试意图匹配工具
 *
 * 测试用户输入与指定意图的匹配度，返回匹配得分、匹配原因、置信度等信息。
 * 帮助LLM验证新创建的意图是否有效，以及优化现有意图的关键词。
 *
 * 适用场景：
 * - 验证新创建的意图能否正确匹配
 * - 测试关键词优化效果
 * - 排查意图匹配问题
 * - 对比多个意图的匹配优先级
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
@Slf4j
@Component
public class TestIntentMatchingTool extends AbstractTool {

    @Autowired
    @Lazy
    private AIIntentService aiIntentService;

    @Override
    public String getToolName() {
        return "test_intent_matching";
    }

    @Override
    public String getDescription() {
        return "测试用户输入与指定意图的匹配度，返回匹配得分、匹配原因、置信度等信息。" +
                "可以测试单个意图，也可以对比多个意图的匹配情况。" +
                "适用场景：验证新意图、优化关键词、排查匹配问题、对比意图优先级。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // userInput: 用户输入（必需）
        Map<String, Object> userInput = new HashMap<>();
        userInput.put("type", "string");
        userInput.put("description", "要测试的用户输入文本");
        userInput.put("minLength", 1);
        properties.put("userInput", userInput);

        // intentCode: 意图代码（可选，不提供则测试所有意图）
        Map<String, Object> intentCode = new HashMap<>();
        intentCode.put("type", "string");
        intentCode.put("description", "要测试的意图代码。如果不提供，将返回所有匹配的意图排名");
        properties.put("intentCode", intentCode);

        // topN: 返回Top N个候选意图（可选，默认3）
        Map<String, Object> topN = new HashMap<>();
        topN.put("type", "integer");
        topN.put("description", "返回的候选意图数量，默认3");
        topN.put("minimum", 1);
        topN.put("maximum", 10);
        topN.put("default", 3);
        properties.put("topN", topN);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("userInput"));

        return schema;
    }

    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
        logExecutionStart(toolCall, context);
        validateContext(context);

        try {
            // 1. 解析参数
            Map<String, Object> arguments = parseArguments(toolCall);
            String userInput = getRequiredParam(arguments, "userInput");
            String intentCode = getOptionalParam(arguments, "intentCode", null);
            String factoryId = getFactoryId(context);

            // 获取topN参数
            int topN = 3;
            if (arguments.containsKey("topN")) {
                Object topNObj = arguments.get("topN");
                if (topNObj instanceof Integer) {
                    topN = (Integer) topNObj;
                } else if (topNObj instanceof String) {
                    topN = Integer.parseInt((String) topNObj);
                }
            }

            // 2. 执行匹配测试
            if (intentCode != null) {
                // 测试指定意图
                return testSpecificIntent(userInput, intentCode, factoryId);
            } else {
                // 测试所有意图，返回Top N
                return testAllIntents(userInput, factoryId, topN);
            }

        } catch (IllegalArgumentException e) {
            log.warn("⚠️  参数验证失败: {}", e.getMessage());
            return buildErrorResult("参数验证失败: " + e.getMessage());

        } catch (Exception e) {
            logExecutionFailure(toolCall, e);
            return buildErrorResult("测试意图匹配失败: " + e.getMessage());
        }
    }

    /**
     * 测试指定意图
     */
    private String testSpecificIntent(String userInput, String intentCode, String factoryId) throws Exception {
        // 1. 获取意图配置
        Optional<AIIntentConfig> intentOpt = aiIntentService.getIntentByCode(factoryId, intentCode);
        if (!intentOpt.isPresent()) {
            return buildErrorResult("意图配置不存在: " + intentCode);
        }

        AIIntentConfig intent = intentOpt.get();

        // 2. 解析关键词
        List<String> keywords = parseKeywords(intent.getKeywords());

        // 3. 计算匹配度
        Map<String, Object> matchResult = calculateMatch(userInput, intent, keywords);

        // 4. 构建返回结果
        Map<String, Object> resultData = new HashMap<>();
        resultData.put("userInput", userInput);
        resultData.put("intentCode", intentCode);
        resultData.put("intentName", intent.getIntentName());
        resultData.put("category", intent.getIntentCategory());
        resultData.put("active", intent.getIsActive());
        resultData.put("matchResult", matchResult);

        // 添加建议
        List<String> suggestions = generateSuggestions(matchResult, keywords, userInput);
        resultData.put("suggestions", suggestions);

        return buildSuccessResult(resultData);
    }

    /**
     * 测试所有意图
     */
    private String testAllIntents(String userInput, String factoryId, int topN) throws Exception {
        // 使用AIIntentService的增强识别方法 (传递null作为userId和userRole，因为这是测试工具)
        IntentMatchResult matchResult = aiIntentService.recognizeIntentWithConfidence(userInput, factoryId, topN, null, null);

        // 构建返回结果
        Map<String, Object> resultData = new HashMap<>();
        resultData.put("userInput", userInput);
        resultData.put("totalCandidates", matchResult.getTopCandidates().size());
        resultData.put("matchMethod", matchResult.getMatchMethod().name());
        resultData.put("matchMethodDescription", getMatchMethodDescription(matchResult.getMatchMethod()));

        // 转换候选意图为简化格式
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (IntentMatchResult.CandidateIntent candidate : matchResult.getTopCandidates()) {
            Map<String, Object> candidateInfo = new HashMap<>();
            candidateInfo.put("intentCode", candidate.getIntentCode());
            candidateInfo.put("intentName", candidate.getIntentName());
            candidateInfo.put("category", candidate.getIntentCategory());
            candidateInfo.put("confidence", candidate.getConfidence());
            candidateInfo.put("matchedKeywords", candidate.getMatchedKeywords());
            candidates.add(candidateInfo);
        }
        resultData.put("candidates", candidates);

        // 最佳匹配
        if (!matchResult.getTopCandidates().isEmpty()) {
            IntentMatchResult.CandidateIntent best = matchResult.getTopCandidates().get(0);
            Map<String, Object> bestMatch = new HashMap<>();
            bestMatch.put("intentCode", best.getIntentCode());
            bestMatch.put("intentName", best.getIntentName());
            bestMatch.put("confidence", best.getConfidence());
            bestMatch.put("matchedKeywords", best.getMatchedKeywords());
            resultData.put("bestMatch", bestMatch);

            // 如果置信度较低，提供建议
            if (best.getConfidence() < 0.7) {
                List<String> suggestions = Arrays.asList(
                        "匹配置信度较低（< 0.7），可能需要优化关键词",
                        "建议添加更多与用户输入相关的关键词",
                        "考虑使用近义词或常见表达方式"
                );
                resultData.put("suggestions", suggestions);
            }
        } else {
            resultData.put("bestMatch", null);
            resultData.put("suggestions", Arrays.asList(
                    "未找到匹配的意图",
                    "建议创建新的意图配置",
                    "或检查用户输入是否为有效的业务请求"
            ));
        }

        return buildSuccessResult(resultData);
    }

    /**
     * 计算匹配度
     */
    private Map<String, Object> calculateMatch(String userInput, AIIntentConfig intent, List<String> keywords) {
        Map<String, Object> result = new HashMap<>();

        String normalizedInput = userInput.toLowerCase().trim();
        List<String> matchedKeywords = new ArrayList<>();
        int totalKeywords = keywords.size();
        int matchedCount = 0;

        // 检查每个关键词是否匹配
        for (String keyword : keywords) {
            if (normalizedInput.contains(keyword.toLowerCase())) {
                matchedKeywords.add(keyword);
                matchedCount++;
            }
        }

        // 计算置信度
        double confidence = totalKeywords > 0 ? (double) matchedCount / totalKeywords : 0.0;

        result.put("matched", matchedCount > 0);
        result.put("matchedKeywords", matchedKeywords);
        result.put("matchedKeywordCount", matchedCount);
        result.put("totalKeywordCount", totalKeywords);
        result.put("confidence", Math.round(confidence * 100) / 100.0);

        // 匹配质量评估
        String quality;
        if (confidence >= 0.8) {
            quality = "EXCELLENT";
        } else if (confidence >= 0.5) {
            quality = "GOOD";
        } else if (confidence >= 0.3) {
            quality = "FAIR";
        } else if (confidence > 0) {
            quality = "WEAK";
        } else {
            quality = "NO_MATCH";
        }
        result.put("matchQuality", quality);

        return result;
    }

    /**
     * 生成优化建议
     */
    private List<String> generateSuggestions(Map<String, Object> matchResult, List<String> keywords, String userInput) {
        List<String> suggestions = new ArrayList<>();

        double confidence = (Double) matchResult.get("confidence");
        int matchedCount = (Integer) matchResult.get("matchedKeywordCount");
        @SuppressWarnings("unchecked")
        List<String> matchedKeywords = (List<String>) matchResult.get("matchedKeywords");

        if (confidence == 0) {
            suggestions.add("未匹配到任何关键词，建议检查关键词列表是否完整");
            suggestions.add("用户输入可能包含的关键词：" + extractPotentialKeywords(userInput));
            suggestions.add("考虑添加用户常用的表达方式到关键词列表");
        } else if (confidence < 0.5) {
            suggestions.add("匹配度偏低（" + Math.round(confidence * 100) + "%），建议优化关键词");
            suggestions.add("当前匹配关键词：" + String.join(", ", matchedKeywords));
            suggestions.add("考虑添加更多相关关键词以提高匹配准确率");
        } else if (confidence < 0.8) {
            suggestions.add("匹配度中等（" + Math.round(confidence * 100) + "%），可进一步优化");
            suggestions.add("当前匹配关键词：" + String.join(", ", matchedKeywords));
        } else {
            suggestions.add("匹配度良好（" + Math.round(confidence * 100) + "%），关键词配置合理");
            suggestions.add("匹配关键词：" + String.join(", ", matchedKeywords));
        }

        return suggestions;
    }

    /**
     * 提取潜在关键词
     */
    private String extractPotentialKeywords(String userInput) {
        // 简单的分词（按空格和标点分割）
        String[] words = userInput.split("[\\s，。！？、]+");
        List<String> potentialKeywords = new ArrayList<>();

        for (String word : words) {
            word = word.trim();
            if (word.length() >= 2 && !isStopWord(word)) {
                potentialKeywords.add(word);
            }
        }

        return String.join(", ", potentialKeywords);
    }

    /**
     * 判断是否为停用词
     */
    private boolean isStopWord(String word) {
        Set<String> stopWords = new HashSet<>(Arrays.asList(
                "的", "了", "和", "是", "就", "都", "而", "及", "与", "或",
                "在", "有", "我", "他", "她", "它", "们", "这", "那", "些",
                "个", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"
        ));
        return stopWords.contains(word);
    }

    /**
     * 解析关键词JSON字符串为列表
     */
    private List<String> parseKeywords(String keywordsJson) {
        if (keywordsJson == null || keywordsJson.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(keywordsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("⚠️  解析关键词失败: {}", keywordsJson, e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取匹配方法描述
     */
    private String getMatchMethodDescription(IntentMatchResult.MatchMethod method) {
        switch (method) {
            case EXACT:
                return "精确匹配 - 使用hash表精确匹配";
            case REGEX:
                return "正则表达式匹配 - 使用配置的正则模式精确匹配";
            case KEYWORD:
                return "关键词匹配 - 基于配置的关键词列表匹配";
            case SEMANTIC:
                return "语义匹配 - 使用向量相似度匹配";
            case FUSION:
                return "融合匹配 - 语义+关键词加权匹配";
            case SIMILAR:
                return "相似匹配 - 使用编辑距离匹配";
            case LLM:
                return "LLM兜底 - 使用大语言模型理解意图";
            case NONE:
                return "无匹配 - 未找到合适的意图";
            default:
                return "未知匹配方法";
        }
    }

    /**
     * 此工具不需要特殊权限
     */
    @Override
    public boolean requiresPermission() {
        return false;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
