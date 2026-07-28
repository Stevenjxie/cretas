package com.cretas.aims.ai.tool.impl.foodknowledge;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.client.PythonSmartBIClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 食品知识库查询工具
 *
 * 通过 RAG 向量检索查询食品安全知识，支持 NER 实体提取增强检索。
 *
 * Intent Code: FOOD_KNOWLEDGE_QUERY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class FoodKnowledgeQueryTool extends AbstractBusinessTool {

    @Autowired
    private PythonSmartBIClient pythonClient;

    private static final String FALLBACK_MESSAGE = "食品知识库服务暂时不可用，请稍后再试。" +
            "您也可以参考国家食品安全标准(GB标准)获取相关信息。";

    @Override
    public String getToolName() {
        return "food_knowledge_query";
    }

    @Override
    public String getDescription() {
        return "查询食品安全知识库。通过 RAG 向量检索获取食品安全标准、工艺要求、法规等知识。" +
                "适用场景：查询食品安全标准、了解加工工艺要求、查找法规依据。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> query = new HashMap<>();
        query.put("type", "string");
        query.put("description", "食品知识查询问题，例如「豆腐的保质期标准」「肉制品加工温度要求」");
        properties.put("query", query);

        Map<String, Object> topK = new HashMap<>();
        topK.put("type", "integer");
        topK.put("description", "返回的最相关文档数量");
        topK.put("default", 5);
        properties.put("topK", topK);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("query"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("query");
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行食品知识库查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String query = getString(params, "query");
        Integer topK = getInteger(params, "topK", 5);

        long startTime = System.currentTimeMillis();

        // Check Python service availability
        if (!pythonClient.isAvailable()) {
            log.warn("食品知识库Python服务不可用: query='{}'", query);
            Map<String, Object> result = new HashMap<>();
            result.put("query", query);
            result.put("fallback", true);
            result.put("message", FALLBACK_MESSAGE);
            return result;
        }

        // Step 1: NER entity extraction (non-blocking)
        Map<String, Object> nerResult = null;
        try {
            nerResult = pythonClient.extractFoodEntities(query);
            if (nerResult != null && Boolean.TRUE.equals(nerResult.get("success"))) {
                log.debug("NER提取到 {} 个实体", nerResult.get("entity_count"));
            }
        } catch (Exception e) {
            log.debug("NER提取失败（非致命）: {}", e.getMessage());
        }

        // Step 2: RAG vector search
        Map<String, Object> ragResult = pythonClient.queryFoodKnowledge(query, null, topK);
        if (ragResult == null || !Boolean.TRUE.equals(ragResult.get("success"))) {
            String errorMsg = ragResult != null ? String.valueOf(ragResult.get("message")) : "服务不可用";
            log.warn("RAG查询失败: {}", errorMsg);
            Map<String, Object> result = new HashMap<>();
            result.put("query", query);
            result.put("fallback", true);
            result.put("message", FALLBACK_MESSAGE);
            return result;
        }

        // Step 3: Extract search results
        List<Map<String, Object>> documents = (List<Map<String, Object>>) ragResult.get("data");
        int docCount = documents != null ? documents.size() : 0;

        if (docCount == 0) {
            Map<String, Object> result = new HashMap<>();
            result.put("query", query);
            result.put("documentCount", 0);
            result.put("message", "抱歉，知识库中暂未找到与\"" + query + "\"相关的内容。您可以尝试换一种表述方式。");
            return result;
        }

        // Step 4: Build formatted answer
        String formattedAnswer = buildFormattedAnswer(query, documents);
        List<Map<String, Object>> citations = buildCitations(documents);

        long latency = System.currentTimeMillis() - startTime;
        log.info("食品知识库查询完成: query='{}', docs={}, latency={}ms", query, docCount, latency);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", formattedAnswer);
        result.put("citations", citations);
        result.put("documentCount", docCount);
        result.put("query", query);
        result.put("latencyMs", latency);
        if (nerResult != null && nerResult.get("entities") != null) {
            result.put("entities", nerResult.get("entities"));
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private String buildFormattedAnswer(String query, List<Map<String, Object>> documents) {
        StringBuilder sb = new StringBuilder();

        Map<String, Object> topDoc = documents.get(0);
        double topScore = getDoubleValue(topDoc, "similarity");

        if (topScore >= 0.80) {
            sb.append(getStringValue(topDoc, "content"));
        } else {
            sb.append("根据食品安全知识库的相关资料：\n\n");
            for (int i = 0; i < Math.min(documents.size(), 3); i++) {
                Map<String, Object> doc = documents.get(i);
                String title = getStringValue(doc, "title");
                String content = getStringValue(doc, "content");
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }
                sb.append("**[文档").append(i + 1).append("] ").append(title).append("**\n");
                sb.append(content).append("\n\n");
            }
        }

        sb.append("\n---\n**参考来源：**\n");
        for (int i = 0; i < Math.min(documents.size(), 5); i++) {
            Map<String, Object> doc = documents.get(i);
            String title = getStringValue(doc, "title");
            String source = getStringValue(doc, "source");
            double score = getDoubleValue(doc, "similarity");
            sb.append(String.format("- [%d] %s", i + 1, title));
            if (!source.isEmpty()) {
                sb.append(" (").append(source).append(")");
            }
            sb.append(String.format(" [相似度: %.0f%%]", score * 100));
            sb.append("\n");
        }

        return sb.toString();
    }

    private List<Map<String, Object>> buildCitations(List<Map<String, Object>> documents) {
        List<Map<String, Object>> citations = new ArrayList<>();
        for (int i = 0; i < Math.min(documents.size(), 5); i++) {
            Map<String, Object> doc = documents.get(i);
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("index", i + 1);
            citation.put("title", getStringValue(doc, "title"));
            citation.put("source", getStringValue(doc, "source"));
            citation.put("category", getStringValue(doc, "category"));
            citation.put("similarity", getDoubleValue(doc, "similarity"));
            citations.add(citation);
        }
        return citations;
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }

    private double getDoubleValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        return 0.0;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("query".equals(paramName)) {
            return "请问您想了解什么食品安全知识？";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "query", "查询问题",
                "topK", "返回文档数"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
