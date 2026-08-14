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
     * 🔴 2026-08-14: 本服务与 {@link ToolRegistry} 互相依赖, 而 registry 在**自己的
     * {@code @PostConstruct} 里**调 {@code runSimilarityGateCheck()}。
     *
     * 修了两轮才对, 两轮的读数都写在这里, 因为第一轮「看起来对」:
     *
     * <ul>
     *   <li><b>原始</b>: 构造注入 {@code ToolRegistry} → 连本服务都建不出来。
     *       报错 {@code Error creating bean 'toolSimilarityService' ... 构造参数 0 ...
     *       'toolRegistry': Requested bean is currently in creation}。</li>
     *   <li><b>第一轮修(#2613, 无效)</b>: 换成 {@code ObjectProvider}, 本服务能建了 ——
     *       但 {@code getObject()} 仍在 registry 的 {@code @PostConstruct} 里执行,
     *       此刻它照样 in-creation。生产实测报错只是**变短**了:
     *       {@code Error creating bean 'toolRegistry': Requested bean is currently in creation},
     *       闸依然一次没跑成。</li>
     *   <li><b>第二轮修(本次)</b>: 启动期**根本不去取 registry** ——
     *       registry 手里就有 {@code toolMap}, 直接把 executors 传进来
     *       ({@link #detectSimilarTools(Collection)})。依赖消失, 时序问题随之消失。</li>
     * </ul>
     *
     * ⚠️ registry 那侧本来就写了 {@code @Autowired @Lazy} —— {@code @Lazy} 只延后**代理的创建**,
     * 挡不住「代理方法被调用时对端仍在构造」。**只在一侧加 @Lazy 是无效的。**
     *
     * ⚠️ 第一轮之所以没被拦住: 我的断言只验了「本服务能否构造」, 而失效发生在**执行**那一步。
     * 守卫必须跑在真实调用点上 —— 见 {@code ToolRegistryStartupGateTest}(驱动真实的 {@code init()},
     * 断言直接打在生产症状那行 WARN 上)。实测: 把本类的调用改回无参重载, 那个文件 4/4 全红,
     * 而只验构造的 {@code ToolSimilarityGateRunsTest} 纹丝不动。
     *
     * 范围澄清: 从未执行的是**启动闸**这一条路。{@code ToolHealthMonitor} 的定时扫描
     * (启动完成之后跑)一直是通的, 日志里有 {@code Tool similarity scan complete} 为证。
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
        return detectSimilarTools(toolRegistryProvider.getObject().getAllExecutors());
    }

    /**
     * 同上, 但由调用方直接把 executors 交进来。
     *
     * 🔴 启动闸**必须**走这一条: 它在 {@link ToolRegistry} 自己的 {@code @PostConstruct} 里执行,
     * 那一刻 registry 还是 in-creation, 任何形式的「回头找 registry 这个 bean」
     * (构造注入 / {@code @Lazy} 代理 / {@code ObjectProvider#getObject})都会失败。
     * 而 registry 手里本来就有全部 executors —— 传进来即可, 不必再问容器要。
     */
    public List<SimilarToolPair> detectSimilarTools(Collection<ToolExecutor> executors) {
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
