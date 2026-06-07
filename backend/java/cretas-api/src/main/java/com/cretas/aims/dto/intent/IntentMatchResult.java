package com.cretas.aims.dto.intent;

import com.cretas.aims.config.IntentKnowledgeBase.ActionType;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.dto.ai.PreprocessedQuery;
import com.cretas.aims.entity.config.AIIntentConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 意图匹配结果 DTO
 *
 * 包含意图识别的完整信息:
 * - 最佳匹配意图
 * - Top-N 候选意图（带置信度）
 * - 匹配方法和匹配的关键词
 * - 强/弱信号判断
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class IntentMatchResult {

    /**
     * 最佳匹配的意图配置
     */
    private AIIntentConfig bestMatch;

    /**
     * Top-N 候选意图列表（按置信度降序）
     */
    private List<CandidateIntent> topCandidates;

    /**
     * 最佳匹配的置信度 (0.0 - 1.0)
     */
    private Double confidence;

    /**
     * 匹配方法: REGEX / KEYWORD / LLM / NONE
     */
    private MatchMethod matchMethod;

    /**
     * 匹配到的关键词列表
     */
    private List<String> matchedKeywords;

    /**
     * 是否为强信号（高置信度，无歧义）
     * 强信号条件:
     * 1. 匹配关键词 >= 3
     * 2. top1与top2置信度差距 > 0.3
     * 3. 优先级 >= 80
     */
    private Boolean isStrongSignal;

    /**
     * 是否需要用户确认
     * 基于信号强度和敏感度级别决定
     */
    private Boolean requiresConfirmation;

    /**
     * 澄清问题（当需要确认时生成）
     */
    private String clarificationQuestion;

    /**
     * 原始用户输入
     */
    private String userInput;

    /**
     * 检测到的操作类型
     * 用于区分用户意图是查询、创建、更新还是删除
     */
    private ActionType actionType;

    /**
     * 问题类型（第一层分类）
     * 用于区分操作指令、通用咨询问题、闲聊
     * GENERAL_QUESTION 和 CONVERSATIONAL 类型会跳过关键词匹配，直接路由到LLM
     */
    private QuestionType questionType;

    /**
     * 目标实体标识
     * 例如: MB-F001-001 (物料批次), WO-F001-001 (工单)
     * 从用户输入中提取的实体标识符
     */
    private String targetEntity;

    /**
     * 会话ID (多轮对话)
     * 当置信度低于30%时，启动多轮对话，客户端使用此sessionId继续对话
     */
    private String sessionId;

    /**
     * 多轮对话消息
     * 当启动多轮对话时，包含澄清问题
     */
    private String conversationMessage;

    // ==================== 多意图支持字段 (模块C) ====================

    /**
     * 是否为多意图输入
     * 当用户输入包含多个独立意图时为 true
     */
    private Boolean isMultiIntent;

    /**
     * 附加意图列表
     * 当 isMultiIntent 为 true 时，包含除 bestMatch 外的其他意图
     */
    private List<IntentMatch> additionalIntents;

    /**
     * 多意图执行策略
     * 决定多个意图的执行方式
     */
    private MultiIntentResult.ExecutionStrategy executionStrategy;

    // ==================== 性能计时 ====================

    /**
     * 各阶段耗时 (毫秒)
     * 键: preprocessMs, matchMs, totalMs
     */
    private Map<String, Long> timingMs;

    // ==================== 查询预处理结果 (模块B) ====================

    /**
     * 预处理后的查询结果
     * 包含时间归一化、口语标准化、指代消解等结果
     */
    private PreprocessedQuery preprocessedQuery;

    /**
     * 匹配方法枚举
     */
    public enum MatchMethod {
        EXACT,      // 精确表达匹配 (hash查表)
        PHRASE_MATCH, // v5.0: 短语映射优先匹配 (优先于问题类型检测)
        REGEX,      // 正则表达式匹配
        KEYWORD,    // 关键词匹配
        SEMANTIC,   // 语义向量匹配（高置信度直接采用）
        CLASSIFIER, // v16.0: Python BERT 分类器直接分类
        FUSION,     // 融合匹配（语义+关键词加权）
        SIMILAR,    // 相似表达匹配 (编辑距离)
        LLM,        // LLM fallback 匹配
        DOMAIN_DEFAULT, // 域默认意图匹配 (Layer 3.5)
        CONTINUATION_INHERIT, // T128: 续接继承 — 当前轮是白名单 READ 意图的续接型查询，
                               // 直接继承上一轮意图，跳过语义/LLM 重识别和 W0 ABSTAIN 门。
                               // 仅在 CONTINUATION_CANONICAL_PHRASE 白名单全 READ 意图下触发。
        REJECTED,   // v11.7: 输入被拒绝 (不完整/无效输入)
        NONE        // 未匹配
    }

    /**
     * 候选意图内部类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandidateIntent {
        /**
         * 意图代码
         */
        private String intentCode;

        /**
         * 意图名称
         */
        private String intentName;

        /**
         * 意图分类
         */
        private String intentCategory;

        /**
         * 置信度 (0.0 - 1.0)
         */
        private Double confidence;

        /**
         * 匹配分数（原始分数）
         */
        private Integer matchScore;

        /**
         * 匹配到的关键词
         */
        private List<String> matchedKeywords;

        /**
         * 匹配方法
         */
        private MatchMethod matchMethod;

        /**
         * 意图描述
         */
        private String description;

        /**
         * 从 AIIntentConfig 创建候选意图
         */
        public static CandidateIntent fromConfig(AIIntentConfig config, Double confidence,
                                                  Integer matchScore, List<String> matchedKeywords,
                                                  MatchMethod matchMethod) {
            return CandidateIntent.builder()
                    .intentCode(config.getIntentCode())
                    .intentName(config.getIntentName())
                    .intentCategory(config.getIntentCategory())
                    .confidence(confidence)
                    .matchScore(matchScore)
                    .matchedKeywords(matchedKeywords)
                    .matchMethod(matchMethod)
                    .description(config.getDescription())
                    .build();
        }
    }

    /**
     * 多意图匹配结果内部类 (模块C)
     * 用于存储多意图识别中的单个意图匹配信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntentMatch {
        /**
         * 意图代码
         */
        private String intentCode;

        /**
         * 意图名称
         */
        private String intentName;

        /**
         * 置信度 (0.0 - 1.0)
         */
        private double confidence;

        /**
         * 提取的参数
         */
        private Map<String, Object> extractedParams;

        /**
         * 执行顺序 (多意图时的执行优先级)
         */
        private int executionOrder;

        /**
         * 推理说明
         */
        private String reasoning;
    }

    /**
     * 创建空结果（无匹配）
     */
    public static IntentMatchResult empty(String userInput) {
        return IntentMatchResult.builder()
                .bestMatch(null)
                .topCandidates(List.of())
                .confidence(0.0)
                .matchMethod(MatchMethod.NONE)
                .matchedKeywords(List.of())
                .isStrongSignal(false)
                .requiresConfirmation(false)
                .userInput(userInput)
                .actionType(ActionType.UNKNOWN)
                .targetEntity(null)
                .build();
    }

    /**
     * 创建空结果（无匹配，带操作类型）
     */
    public static IntentMatchResult empty(String userInput, ActionType actionType) {
        return IntentMatchResult.builder()
                .bestMatch(null)
                .topCandidates(List.of())
                .confidence(0.0)
                .matchMethod(MatchMethod.NONE)
                .matchedKeywords(List.of())
                .isStrongSignal(false)
                .requiresConfirmation(false)
                .userInput(userInput)
                .actionType(actionType != null ? actionType : ActionType.UNKNOWN)
                .targetEntity(null)
                .build();
    }

    /**
     * 创建空结果（无匹配，带操作类型和目标实体）
     */
    public static IntentMatchResult empty(String userInput, ActionType actionType, String targetEntity) {
        return IntentMatchResult.builder()
                .bestMatch(null)
                .topCandidates(List.of())
                .confidence(0.0)
                .matchMethod(MatchMethod.NONE)
                .matchedKeywords(List.of())
                .isStrongSignal(false)
                .requiresConfirmation(false)
                .userInput(userInput)
                .actionType(actionType != null ? actionType : ActionType.UNKNOWN)
                .targetEntity(targetEntity)
                .build();
    }

    /**
     * 判断是否有匹配结果
     */
    public boolean hasMatch() {
        return bestMatch != null && confidence > 0;
    }

    /**
     * 判断是否需要 LLM fallback
     * 条件: 无匹配或置信度过低
     */
    public boolean needsLlmFallback() {
        return bestMatch == null || confidence < 0.3;
    }

    /**
     * 判断是否需要用户选择候选意图
     * 条件: 有多个候选且置信度差距小
     */
    public boolean needsCandidateSelection() {
        if (topCandidates == null || topCandidates.size() < 2) {
            return false;
        }
        // 如果 top1 和 top2 置信度差距小于 0.2，需要用户选择
        double gap = topCandidates.get(0).getConfidence() - topCandidates.get(1).getConfidence();
        return gap < 0.2;
    }
}
