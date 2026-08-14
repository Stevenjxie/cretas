package com.cretas.aims.service.governance;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool 相似度检测与合并建议服务
 *
 * 检测 ToolRegistry 中描述和参数高度相似的 Tool 对，
 * 生成合并建议供管理员人工确认。不自动执行任何合并操作。
 */
@Slf4j
@Service
public class ToolSimilarityService {

    /**
     * 🔴 2026-08-14: 这里原本是 {@code @RequiredArgsConstructor} + {@code private final ToolRegistry}
     * (构造注入), 而 {@link ToolRegistry} 在**自己的 {@code @PostConstruct} 里**调
     * {@code runSimilarityGateCheck()}。此刻 ToolRegistry 还在创建中, Spring 无法把它
     * 构造进本服务 → `Requested bean is currently in creation` → 异常被 registry 那侧的
     * catch 吞成一行 WARN。
     *
     * ⚠️ ToolRegistry 那侧**已经写了 `@Autowired @Lazy`**, 但 `@Lazy` 只延后代理的创建,
     * 挡不住「代理方法被调用时对端仍在构造」这一步 —— 所以只在一侧加 @Lazy 是无效的,
     * 必须让**被依赖的那一侧**也别在构造期就要求对方就绪。
     *
     * 生产实证: 日志里 354 次全是同一条失败, 成功那行(`no highly similar tool pairs found`
     * 或 `found N similar tool pairs`)**一次都没出现过** —— 这道治理闸自引入起从未执行。
     */
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;

    public ToolSimilarityService(@Lazy ObjectProvider<ToolRegistry> toolRegistryProvider) {
        this.toolRegistryProvider = toolRegistryProvider;
    }

    private static final double DESCRIPTION_SIMILARITY_THRESHOLD = 0.85;
    private static final double PARAM_OVERLAP_THRESHOLD = 0.70;

    /**
     * 检测所有相似 Tool 对
     *
     * 算法：Jaccard 相似度（基于描述分词 bigram）+ 参数名重叠度
     * 综合相似度 = 0.6 * descSimilarity + 0.4 * paramOverlap
     */
    public List<SimilarToolPair> detectSimilarTools() {
        Collection<ToolExecutor> executors = toolRegistryProvider.getObject().getAllExecutors();
        List<ToolExecutor> executorList = new ArrayList<>(executors);
        List<SimilarToolPair> results = new ArrayList<>();

        for (int i = 0; i < executorList.size(); i++) {
            ToolExecutor a = executorList.get(i);
            for (int j = i + 1; j < executorList.size(); j++) {
                ToolExecutor b = executorList.get(j);

                double descSim = jaccardBigram(a.getDescription(), b.getDescription());
                double paramOverlap = parameterOverlap(a.getParametersSchema(), b.getParametersSchema());
                double combined = 0.6 * descSim + 0.4 * paramOverlap;

                if (combined >= DESCRIPTION_SIMILARITY_THRESHOLD) {
                    String recommendation = generateQuickRecommendation(a, b, descSim, paramOverlap);
                    results.add(SimilarToolPair.builder()
                            .toolA(a.getToolName())
                            .toolB(b.getToolName())
                            .descriptionSimilarity(Math.round(descSim * 1000.0) / 1000.0)
                            .paramOverlap(Math.round(paramOverlap * 1000.0) / 1000.0)
                            .combinedSimilarity(Math.round(combined * 1000.0) / 1000.0)
                            .mergeRecommendation(recommendation)
                            .build());
                }
            }
        }

        results.sort(Comparator.comparingDouble(SimilarToolPair::getCombinedSimilarity).reversed());
        log.info("Tool similarity scan complete: {} tools checked, {} similar pairs found",
                executorList.size(), results.size());
        return results;
    }

    /**
     * 为指定 Tool 检查相似性（用于新 Tool 注册时的 gate-keeping）
     */
    public List<SimilarToolPair> checkSimilarityForTool(String toolName) {
        Optional<ToolExecutor> targetOpt = toolRegistryProvider.getObject().getExecutor(toolName);
        if (targetOpt.isEmpty()) return Collections.emptyList();

        ToolExecutor target = targetOpt.get();
        List<SimilarToolPair> results = new ArrayList<>();

        for (ToolExecutor other : toolRegistryProvider.getObject().getAllExecutors()) {
            if (other.getToolName().equals(toolName)) continue;

            double descSim = jaccardBigram(target.getDescription(), other.getDescription());
            double paramOverlap = parameterOverlap(target.getParametersSchema(), other.getParametersSchema());
            double combined = 0.6 * descSim + 0.4 * paramOverlap;

            if (combined >= DESCRIPTION_SIMILARITY_THRESHOLD) {
                results.add(SimilarToolPair.builder()
                        .toolA(toolName)
                        .toolB(other.getToolName())
                        .descriptionSimilarity(Math.round(descSim * 1000.0) / 1000.0)
                        .paramOverlap(Math.round(paramOverlap * 1000.0) / 1000.0)
                        .combinedSimilarity(Math.round(combined * 1000.0) / 1000.0)
                        .mergeRecommendation(generateQuickRecommendation(target, other, descSim, paramOverlap))
                        .build());
            }
        }

        results.sort(Comparator.comparingDouble(SimilarToolPair::getCombinedSimilarity).reversed());
        return results;
    }

    /**
     * 生成两个 Tool 的合并方案
     */
    public MergeProposal generateMergeProposal(String toolNameA, String toolNameB) {
        Optional<ToolExecutor> aOpt = toolRegistryProvider.getObject().getExecutor(toolNameA);
        Optional<ToolExecutor> bOpt = toolRegistryProvider.getObject().getExecutor(toolNameB);
        if (aOpt.isEmpty() || bOpt.isEmpty()) return null;

        ToolExecutor a = aOpt.get();
        ToolExecutor b = bOpt.get();

        double descSim = jaccardBigram(a.getDescription(), b.getDescription());
        double paramOverlap = parameterOverlap(a.getParametersSchema(), b.getParametersSchema());

        // Determine which tool to keep (prefer the one with more parameters / broader scope)
        Set<String> paramsA = extractParamNames(a.getParametersSchema());
        Set<String> paramsB = extractParamNames(b.getParametersSchema());
        String keepTool = paramsA.size() >= paramsB.size() ? toolNameA : toolNameB;
        String removeTool = keepTool.equals(toolNameA) ? toolNameB : toolNameA;

        // Merged parameter set
        Set<String> mergedParams = new LinkedHashSet<>(paramsA);
        mergedParams.addAll(paramsB);

        // Parameters only in the removed tool (need to be added to keeper)
        Set<String> keeperParams = keepTool.equals(toolNameA) ? paramsA : paramsB;
        Set<String> newParams = new LinkedHashSet<>(mergedParams);
        newParams.removeAll(keeperParams);

        return MergeProposal.builder()
                .toolA(toolNameA)
                .toolB(toolNameB)
                .descriptionSimilarity(descSim)
                .paramOverlap(paramOverlap)
                .keepTool(keepTool)
                .removeTool(removeTool)
                .mergedParameterNames(new ArrayList<>(mergedParams))
                .newParametersToAdd(new ArrayList<>(newParams))
                .affectedIntentCodes(Collections.emptyList()) // would need DB lookup
                .rationale(buildRationale(a, b, keepTool, descSim, paramOverlap))
                .build();
    }

    // ==================== Jaccard Bigram Similarity ====================

    private double jaccardBigram(String textA, String textB) {
        if (textA == null || textB == null) return 0.0;
        Set<String> bigramsA = toBigrams(textA);
        Set<String> bigramsB = toBigrams(textB);
        if (bigramsA.isEmpty() && bigramsB.isEmpty()) return 1.0;
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(bigramsA);
        intersection.retainAll(bigramsB);
        Set<String> union = new HashSet<>(bigramsA);
        union.addAll(bigramsB);

        return (double) intersection.size() / union.size();
    }

    private Set<String> toBigrams(String text) {
        // Normalize: lowercase, remove punctuation
        String normalized = text.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fff]", " ").trim();
        String[] tokens = normalized.split("\\s+");
        Set<String> bigrams = new HashSet<>();

        // Word-level bigrams
        for (int i = 0; i < tokens.length - 1; i++) {
            if (!tokens[i].isEmpty() && !tokens[i + 1].isEmpty()) {
                bigrams.add(tokens[i] + " " + tokens[i + 1]);
            }
        }
        // Also add individual tokens for better coverage with short descriptions
        for (String token : tokens) {
            if (!token.isEmpty()) bigrams.add(token);
        }
        // Character-level bigrams for Chinese text
        for (String token : tokens) {
            for (int i = 0; i < token.length() - 1; i++) {
                bigrams.add(token.substring(i, i + 2));
            }
        }
        return bigrams;
    }

    // ==================== Parameter Overlap ====================

    @SuppressWarnings("unchecked")
    private double parameterOverlap(Map<String, Object> schemaA, Map<String, Object> schemaB) {
        Set<String> paramsA = extractParamNames(schemaA);
        Set<String> paramsB = extractParamNames(schemaB);
        if (paramsA.isEmpty() && paramsB.isEmpty()) return 1.0;
        if (paramsA.isEmpty() || paramsB.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(paramsA);
        intersection.retainAll(paramsB);
        Set<String> union = new HashSet<>(paramsA);
        union.addAll(paramsB);

        return (double) intersection.size() / union.size();
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractParamNames(Map<String, Object> schema) {
        if (schema == null) return Collections.emptySet();
        Object props = schema.get("properties");
        if (props instanceof Map) {
            return ((Map<String, Object>) props).keySet();
        }
        return Collections.emptySet();
    }

    // ==================== Recommendation Generation ====================

    private String generateQuickRecommendation(ToolExecutor a, ToolExecutor b,
                                                double descSim, double paramOverlap) {
        if (descSim > 0.95 && paramOverlap > 0.90) {
            return "STRONG_MERGE: 描述和参数几乎完全相同，强烈建议合并";
        } else if (descSim > 0.85 && paramOverlap > 0.70) {
            return "MERGE: 高度相似，建议合并为一个 Tool 并用参数区分功能";
        } else if (paramOverlap > 0.85) {
            return "REFACTOR_PARAMS: 参数高度重叠但描述不同，考虑抽取共享参数基类";
        } else {
            return "REVIEW: 有相似之处，建议人工确认是否需要合并";
        }
    }

    private String buildRationale(ToolExecutor a, ToolExecutor b, String keepTool,
                                   double descSim, double paramOverlap) {
        return String.format(
                "保留 %s（参数更全面）。描述相似度=%.0f%%，参数重叠度=%.0f%%。" +
                "合并后需更新绑定到 %s 的 intent_config 记录，将 tool_name 改为 %s。",
                keepTool,
                descSim * 100, paramOverlap * 100,
                keepTool.equals(a.getToolName()) ? b.getToolName() : a.getToolName(),
                keepTool);
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class SimilarToolPair {
        private String toolA;
        private String toolB;
        private double descriptionSimilarity;
        private double paramOverlap;
        private double combinedSimilarity;
        private String mergeRecommendation;
    }

    @Data
    @Builder
    public static class MergeProposal {
        private String toolA;
        private String toolB;
        private double descriptionSimilarity;
        private double paramOverlap;
        private String keepTool;
        private String removeTool;
        private List<String> mergedParameterNames;
        private List<String> newParametersToAdd;
        private List<String> affectedIntentCodes;
        private String rationale;
    }
}
