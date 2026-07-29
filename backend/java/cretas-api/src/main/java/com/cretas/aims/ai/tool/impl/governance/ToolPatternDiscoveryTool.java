package com.cretas.aims.ai.tool.impl.governance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.governance.ToolAutoComposerService;
import com.cretas.aims.service.governance.ToolAutoComposerService.CoOccurrence;
import com.cretas.aims.service.governance.ToolAutoComposerService.ToolSequence;
import com.cretas.aims.service.governance.ToolAutoComposerService.SkillRecommendation;
import com.cretas.aims.service.governance.ToolHealthMonitor;
import com.cretas.aims.service.governance.ToolSimilarityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool 使用模式发现工具
 *
 * 通过 AI 对话让用户查看 tool 共现模式和 skill 组合推荐。
 * 自动挖掘历史调用数据中频繁共用的 tool 组合。
 *
 * 示例对话：
 * - "哪些工具经常一起使用"
 * - "查看工具使用模式"
 * - "有什么推荐的 skill 组合"
 * - "最近30天的工具共现分析"
 */
@Slf4j
@Component
public class ToolPatternDiscoveryTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private ToolAutoComposerService autoComposerService;

    @Autowired
    @Lazy
    private ToolSimilarityService toolSimilarityService;

    @Autowired
    @Lazy
    private ToolHealthMonitor toolHealthMonitor;

    @Override
    public String getToolName() {
        return "governance_pattern_discovery";
    }

    @Override
    public ActionType getActionType() { return ActionType.ANALYZE; }

    @Override
    public String getDescription() {
        return "分析工具使用模式和治理状态。支持：共现分析、序列分析、Skill推荐、" +
                "相似度检测（重复工具）、使用趋势、合并建议。" +
                "适用场景：'哪些工具经常一起用'、'检查重复工具'、'工具使用趋势'、'合并建议'";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "days", Map.of("type", "integer", "description", "回溯天数，默认30"),
                        "analysis_type", Map.of("type", "string", "description",
                                "分析类型: co_occurrence(共现) / sequence(序列) / recommend(推荐) / " +
                                "similarity(相似度检测) / trends(使用趋势) / merge_proposal(合并建议)",
                                "enum", List.of("co_occurrence", "sequence", "recommend",
                                        "similarity", "trends", "merge_proposal")),
                        "tool_a", Map.of("type", "string", "description",
                                "合并建议时的第一个工具名（仅 merge_proposal 模式需要）"),
                        "tool_b", Map.of("type", "string", "description",
                                "合并建议时的第二个工具名（仅 merge_proposal 模式需要）")
                ),
                "required", Collections.emptyList()
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        int days = getInteger(params, "days") != null ? getInteger(params, "days") : 30;
        String analysisType = getString(params, "analysis_type");
        if (analysisType == null) analysisType = "recommend";

        Map<String, Object> result = new HashMap<>();

        switch (analysisType) {
            case "co_occurrence": {
                List<CoOccurrence> coOccurrences = autoComposerService.mineCoOccurrences(factoryId, days, 2);
                result.put("type", "tool_co_occurrence");
                result.put("period_days", days);
                result.put("total_patterns", coOccurrences.size());
                result.put("patterns", coOccurrences.stream()
                        .limit(20)
                        .map(co -> Map.of(
                                "tool_a", co.getToolA(),
                                "tool_b", co.getToolB(),
                                "session_count", co.getSessionCount(),
                                "support_rate", String.format("%.1f%%", co.getSupportRate() * 100)
                        ))
                        .collect(Collectors.toList()));
                result.put("message", formatCoOccurrenceMessage(coOccurrences, days));
                break;
            }
            case "sequence": {
                List<ToolSequence> sequences = autoComposerService.mineSequences(factoryId, days, 2);
                result.put("type", "tool_sequences");
                result.put("period_days", days);
                result.put("total_sequences", sequences.size());
                result.put("sequences", sequences.stream()
                        .limit(20)
                        .map(seq -> Map.of(
                                "tools", seq.getTools(),
                                "occurrences", seq.getOccurrences(),
                                "avg_time_ms", String.format("%.0f", seq.getAvgTotalTimeMs())
                        ))
                        .collect(Collectors.toList()));
                result.put("message", formatSequenceMessage(sequences, days));
                break;
            }
            case "similarity": {
                List<ToolSimilarityService.SimilarToolPair> pairs =
                        toolSimilarityService.detectSimilarTools();
                result.put("type", "tool_similarity");
                result.put("total_pairs", pairs.size());
                result.put("pairs", pairs.stream()
                        .limit(20)
                        .map(p -> Map.of(
                                "tool_a", p.getToolA(),
                                "tool_b", p.getToolB(),
                                "description_similarity", String.format("%.0f%%", p.getDescriptionSimilarity() * 100),
                                "param_overlap", String.format("%.0f%%", p.getParamOverlap() * 100),
                                "combined", String.format("%.0f%%", p.getCombinedSimilarity() * 100),
                                "recommendation", p.getMergeRecommendation()
                        ))
                        .collect(Collectors.toList()));
                result.put("message", formatSimilarityMessage(pairs));
                break;
            }
            case "trends": {
                List<ToolHealthMonitor.ToolUsageTrend> trends =
                        toolHealthMonitor.getUsageTrends(days);
                result.put("type", "tool_usage_trends");
                result.put("period_days", days);
                result.put("total_trends", trends.size());

                List<ToolHealthMonitor.ToolUsageTrend> declining = trends.stream()
                        .filter(t -> "DECLINING".equals(t.getTrend())).collect(Collectors.toList());
                List<ToolHealthMonitor.ToolUsageTrend> surging = trends.stream()
                        .filter(t -> "SURGING".equals(t.getTrend())).collect(Collectors.toList());

                result.put("declining_count", declining.size());
                result.put("declining", declining.stream().limit(10)
                        .map(t -> Map.of("tool", t.getToolName(), "calls", t.getTotalCalls()))
                        .collect(Collectors.toList()));
                result.put("surging_count", surging.size());
                result.put("surging", surging.stream().limit(10)
                        .map(t -> Map.of("tool", t.getToolName(), "calls", t.getTotalCalls()))
                        .collect(Collectors.toList()));
                result.put("message", formatTrendsMessage(declining, surging, days));
                break;
            }
            case "merge_proposal": {
                String toolA = getString(params, "tool_a");
                String toolB = getString(params, "tool_b");
                if (toolA == null || toolB == null) {
                    result.put("message", "请提供 tool_a 和 tool_b 参数来生成合并建议。" +
                            "例如：「为 material_query 和 material_batch_query 生成合并建议」");
                    break;
                }
                ToolSimilarityService.MergeProposal proposal =
                        toolSimilarityService.generateMergeProposal(toolA, toolB);
                if (proposal == null) {
                    result.put("message", String.format("未找到工具 %s 或 %s", toolA, toolB));
                    break;
                }
                result.put("type", "merge_proposal");
                result.put("keep_tool", proposal.getKeepTool());
                result.put("remove_tool", proposal.getRemoveTool());
                result.put("description_similarity",
                        String.format("%.0f%%", proposal.getDescriptionSimilarity() * 100));
                result.put("param_overlap",
                        String.format("%.0f%%", proposal.getParamOverlap() * 100));
                result.put("merged_params", proposal.getMergedParameterNames());
                result.put("new_params_to_add", proposal.getNewParametersToAdd());
                result.put("rationale", proposal.getRationale());
                result.put("message", formatMergeProposalMessage(proposal));
                break;
            }
            case "recommend":
            default: {
                List<SkillRecommendation> recommendations = autoComposerService
                        .generateRecommendations(factoryId, days);
                result.put("type", "skill_recommendations");
                result.put("period_days", days);
                result.put("total_recommendations", recommendations.size());
                result.put("recommendations", recommendations.stream()
                        .limit(10)
                        .map(r -> Map.of(
                                "suggested_name", r.getSuggestedName(),
                                "tools", r.getTools(),
                                "evidence_count", r.getEvidenceCount(),
                                "confidence", String.format("%.0f%%", r.getConfidenceScore() * 100),
                                "reason", r.getReason(),
                                "already_covered", r.isAlreadyCoveredBySkill()
                        ))
                        .collect(Collectors.toList()));
                result.put("message", formatRecommendationMessage(recommendations, days));
                break;
            }
        }

        return result;
    }

    private String formatCoOccurrenceMessage(List<CoOccurrence> coOccurrences, int days) {
        if (coOccurrences.isEmpty()) {
            return String.format("最近 %d 天内未发现显著的工具共现模式。建议积累更多使用数据后再分析。", days);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("最近 %d 天发现 %d 个工具共现模式：\n\n", days, coOccurrences.size()));
        int shown = Math.min(10, coOccurrences.size());
        for (int i = 0; i < shown; i++) {
            CoOccurrence co = coOccurrences.get(i);
            sb.append(String.format("%d. **%s** + **%s** — 共 %d 个会话共同使用\n",
                    i + 1, co.getToolA(), co.getToolB(), co.getSessionCount()));
        }
        if (coOccurrences.size() > 10) {
            sb.append(String.format("\n...及其他 %d 个模式", coOccurrences.size() - 10));
        }
        sb.append("\n\n如需将某个组合创建为 Skill，请说「创建skill：组合名 工具A 工具B」");
        return sb.toString();
    }

    private String formatSequenceMessage(List<ToolSequence> sequences, int days) {
        if (sequences.isEmpty()) {
            return String.format("最近 %d 天内未发现显著的工具调用序列。", days);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("最近 %d 天发现 %d 个有序工具序列：\n\n", days, sequences.size()));
        int shown = Math.min(10, sequences.size());
        for (int i = 0; i < shown; i++) {
            ToolSequence seq = sequences.get(i);
            sb.append(String.format("%d. %s — 出现 %d 次\n",
                    i + 1, String.join(" → ", seq.getTools()), seq.getOccurrences()));
        }
        return sb.toString();
    }

    private String formatSimilarityMessage(List<ToolSimilarityService.SimilarToolPair> pairs) {
        if (pairs.isEmpty()) {
            return "未发现高度相似的工具对。所有工具定义差异足够大。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("🔍 发现 %d 对疑似重复的工具：\n\n", pairs.size()));
        int shown = Math.min(10, pairs.size());
        for (int i = 0; i < shown; i++) {
            ToolSimilarityService.SimilarToolPair p = pairs.get(i);
            sb.append(String.format("%d. **%s** ↔ **%s** (相似度: %.0f%%)\n   %s\n\n",
                    i + 1, p.getToolA(), p.getToolB(),
                    p.getCombinedSimilarity() * 100,
                    p.getMergeRecommendation()));
        }
        sb.append("💡 如需查看某对工具的合并方案，请说「合并建议 工具A 工具B」");
        return sb.toString();
    }

    private String formatTrendsMessage(List<ToolHealthMonitor.ToolUsageTrend> declining,
                                        List<ToolHealthMonitor.ToolUsageTrend> surging, int days) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("最近 %d 天工具使用趋势：\n\n", days));

        if (!surging.isEmpty()) {
            sb.append(String.format("📈 **爆发中** (%d 个):\n", surging.size()));
            for (ToolHealthMonitor.ToolUsageTrend t : surging.subList(0, Math.min(5, surging.size()))) {
                sb.append(String.format("   • %s (%d 次调用)\n", t.getToolName(), t.getTotalCalls()));
            }
            sb.append("\n");
        }

        if (!declining.isEmpty()) {
            sb.append(String.format("📉 **衰退中** (%d 个):\n", declining.size()));
            for (ToolHealthMonitor.ToolUsageTrend t : declining.subList(0, Math.min(5, declining.size()))) {
                sb.append(String.format("   • %s (%d 次调用)\n", t.getToolName(), t.getTotalCalls()));
            }
            sb.append("\n");
        }

        if (surging.isEmpty() && declining.isEmpty()) {
            sb.append("所有工具使用趋势平稳，无显著变化。");
        }
        return sb.toString();
    }

    private String formatMergeProposalMessage(ToolSimilarityService.MergeProposal proposal) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 合并建议：\n\n");
        sb.append(String.format("• **保留**: %s\n", proposal.getKeepTool()));
        sb.append(String.format("• **移除**: %s\n", proposal.getRemoveTool()));
        sb.append(String.format("• 描述相似度: %.0f%%\n", proposal.getDescriptionSimilarity() * 100));
        sb.append(String.format("• 参数重叠度: %.0f%%\n", proposal.getParamOverlap() * 100));
        if (!proposal.getNewParametersToAdd().isEmpty()) {
            sb.append(String.format("• 需要新增参数: %s\n", proposal.getNewParametersToAdd()));
        }
        sb.append(String.format("\n%s\n", proposal.getRationale()));
        sb.append("\n⚠️ 此为建议方案，需人工确认后手动执行合并。");
        return sb.toString();
    }

    private String formatRecommendationMessage(List<SkillRecommendation> recommendations, int days) {
        List<SkillRecommendation> actionable = recommendations.stream()
                .filter(r -> !r.isAlreadyCoveredBySkill())
                .collect(Collectors.toList());

        if (actionable.isEmpty()) {
            return String.format("最近 %d 天的工具使用模式已被现有 Skill 完全覆盖，暂无新推荐。", days);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("最近 %d 天发现 %d 个可组合为 Skill 的模式：\n\n", days, actionable.size()));
        int shown = Math.min(5, actionable.size());
        for (int i = 0; i < shown; i++) {
            SkillRecommendation r = actionable.get(i);
            sb.append(String.format("%d. **%s** (置信度: %s)\n   工具: %s\n   原因: %s\n\n",
                    i + 1,
                    r.getSuggestedDisplayName(),
                    String.format("%.0f%%", r.getConfidenceScore() * 100),
                    String.join(", ", r.getTools()),
                    r.getReason()));
        }
        sb.append("💡 如需创建推荐的 Skill，请说「创建skill 推荐名称」或「把 工具A 和 工具B 组合成skill」");
        return sb.toString();
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
