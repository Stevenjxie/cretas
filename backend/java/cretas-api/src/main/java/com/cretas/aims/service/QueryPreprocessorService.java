package com.cretas.aims.service;

import com.cretas.aims.dto.ai.PreprocessedQuery;
import com.cretas.aims.dto.conversation.ConversationContext;

/**
 * 查询预处理服务接口
 *
 * 模块B: 对用户输入进行预处理，包括：
 * - 规则预处理（空白符规范化、口语标准化、时间归一化）
 * - 上下文注入（实体槽位、指代消解）
 * - 质量评估（评估查询的完整性和清晰度）
 * - 条件触发 LLM 改写（当质量分数低于阈值时）
 *
 * 处理流程：
 * 1. Step 1: 规则预处理 (无LLM)
 *    - 空白符规范化
 *    - 口语标准化
 *    - 时间表达归一化
 *
 * 2. Step 2: 上下文注入
 *    - 从 ConversationContext 获取实体槽位
 *    - 指代消解
 *
 * 3. Step 3: 质量评估
 *    - 长度评分
 *    - 指代完整性
 *    - 时间明确性
 *    - 结构完整性
 *
 * 4. Step 4: 条件触发 LLM 改写
 *    - 当 qualityScore < threshold 时触发
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-15
 */
public interface QueryPreprocessorService {

    /**
     * 预处理用户输入
     *
     * 执行完整的预处理流程：规则预处理 -> 上下文注入 -> 质量评估 -> 条件 LLM 改写
     *
     * @param input   用户原始输入
     * @param context 对话上下文（可以为 null，表示无上下文）
     * @return 预处理结果
     */
    PreprocessedQuery preprocess(String input, ConversationContext context);

    /**
     * 仅执行规则预处理（不使用 LLM）
     *
     * 适用于需要快速处理且不需要 LLM 改写的场景
     *
     * @param input 用户原始输入
     * @return 预处理结果
     */
    PreprocessedQuery preprocessRulesOnly(String input);

    /**
     * 评估查询质量
     *
     * 返回一个 0-1 的分数，表示查询的质量：
     * - 1.0: 完美查询，无需任何改写
     * - 0.6-1.0: 良好查询，可以直接使用
     * - 0.3-0.6: 中等查询，可能需要 LLM 改写
     * - 0.0-0.3: 低质量查询，强烈建议 LLM 改写
     *
     * 评分因素：
     * - 长度因素: 过短 (<5字) 扣分
     * - 未解析指代词: 包含 "这个"、"那个" 但无上下文 → 扣分
     * - 时间模糊: 包含 "最近" 但未指定范围 → 扣分
     * - 完整度: 是否包含动词+名词结构
     *
     * @param input   用户输入
     * @param context 对话上下文（可以为 null）
     * @return 质量分数 (0-1)
     */
    double assessQueryQuality(String input, ConversationContext context);

    /**
     * 使用 LLM 改写查询
     *
     * 将用户的口语化、模糊查询改写为清晰、具体的标准查询。
     *
     * @param input   用户原始输入
     * @param context 对话上下文
     * @return LLM 改写结果
     */
    LlmRewriteResult rewriteWithLLM(String input, ConversationContext context);

    /**
     * 解析指代词
     *
     * 将用户输入中的指代词（如"这个"、"那个"、"它"）解析为具体实体
     *
     * @param input   用户输入
     * @param context 对话上下文
     * @return 解析结果（包含原文本和解析后文本）
     */
    ReferenceResolutionResult resolveReferences(String input, ConversationContext context);

    /**
     * 检查服务是否启用
     *
     * @return true 如果预处理服务启用
     */
    boolean isEnabled();

    /**
     * 检查 LLM 改写是否启用
     *
     * @return true 如果 LLM 改写启用
     */
    boolean isLlmRewriteEnabled();

    // ==================== 复杂语义预处理方法 ====================

    /**
     * 过滤语气词
     *
     * 移除不影响语义的语气词：吧、啊、吗、呢、呀、嘛、哦、哇 等
     *
     * @param input 用户输入
     * @return 过滤后的文本
     */
    String filterModalParticles(String input);

    /**
     * 提取复合句核心
     *
     * 从长句中提取核心动宾结构
     * 例如："我想查一下今天入库的原料批次信息" → "查询原料批次"
     *
     * @param input 用户输入
     * @return 提取结果，包含核心查询和提取的修饰语
     */
    CoreExtractionResult extractCore(String input);

    /**
     * 检测排序/极值查询
     *
     * 检测是否包含排序类关键词：最多、最高、最大、最快、最低等
     *
     * @param input 用户输入
     * @return 排序查询检测结果
     */
    RankingQueryResult detectRankingQuery(String input);

    /**
     * 动词+名词组合消歧
     *
     * 根据动词+名词组合推断意图
     * 例如："处理+原料" → MATERIAL_BATCH_CONSUME
     *
     * @param input 用户输入
     * @return 消歧结果，包含推荐意图代码
     */
    ActionDisambiguationResult disambiguateAction(String input);

    /**
     * 增强预处理（包含所有优化）
     *
     * 执行完整的增强预处理流程：
     * 1. 语气词过滤
     * 2. 口语标准化
     * 3. 核心提取
     * 4. 排序检测
     * 5. 动作消歧
     * 6. 语用学处理（反问句、转折句、双重否定）
     *
     * @param input 用户原始输入
     * @return 增强预处理结果
     */
    EnhancedPreprocessResult enhancedPreprocess(String input);

    /**
     * W1b: 细分否定否决类型。语句级否定("别查/不用看")→ VETO_READ;否定写动词("别开始")→ VETO_WRITE;
     * 双重否定/合法动作动词/普通查询 → NONE。需 IntentKnowledgeBase 判定动词类型。
     */
    NegationKind detectNegationVeto(String input, com.cretas.aims.config.IntentKnowledgeBase knowledgeBase);

    // ==================== 语用学处理方法 (v8.0) ====================

    /**
     * 反问句式转换
     *
     * 将反问句转换为陈述查询意图
     * 例如："难道今天没有原料到货吗" → "查询今天原料到货情况"
     *
     * 支持的模式：
     * - 难道.*(吗|呢)
     * - 怎么.*不
     * - 为什么.*没
     * - 不是.*吗
     *
     * @param input 用户输入
     * @return 语用学处理结果
     */
    PragmaticProcessingResult convertRhetoricalQuestion(String input);

    /**
     * 转折句提取
     *
     * 从转折句中提取真实意图（通常在转折词之后）
     * 例如："虽然产量达标了，但是不合格品率上升了不少" → "查询不合格品率变化"
     *
     * 支持的模式：
     * - (虽然|尽管|虽说).*?(但是|可是|然而|不过)(.*)
     *
     * @param input 用户输入
     * @return 语用学处理结果
     */
    PragmaticProcessingResult extractConcessionIntent(String input);

    /**
     * 双重否定转肯定
     *
     * 将双重否定转换为肯定语义
     * 例如："不是不想用这批原料" → "想用这批原料"
     *
     * 支持的模式：
     * - 不是不、并非不、没有不、不能不、不会不
     *
     * @param input 用户输入
     * @return 语用学处理结果
     */
    PragmaticProcessingResult convertDoubleNegative(String input);

    // ==================== 新增结果类 ====================

    /**
     * 语用学处理结果 (v8.0)
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class PragmaticProcessingResult {
        /** 原始输入 */
        private String originalInput;
        /** 处理后的文本 */
        private String processedText;
        /** 处理类型: RHETORICAL_QUESTION, CONCESSION, DOUBLE_NEGATIVE, NONE */
        private PragmaticType processingType;
        /** 是否进行了处理 */
        private boolean processed;
        /** 检测到的模式 */
        private String detectedPattern;
        /** 提取的核心意图 */
        private String extractedIntent;
        /** 处理说明 */
        private String processingNote;
        /** 置信度 (0-1) */
        private double confidence;
    }

    /**
     * 语用学处理类型枚举 (v8.0)
     */
    enum PragmaticType {
        /** 反问句 */
        RHETORICAL_QUESTION,
        /** 转折句 */
        CONCESSION,
        /** 双重否定 */
        DOUBLE_NEGATIVE,
        /** 无特殊处理 */
        NONE
    }

    /**
     * 核心提取结果
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class CoreExtractionResult {
        /** 提取的核心查询 */
        private String coreQuery;
        /** 原始输入 */
        private String originalInput;
        /** 提取的动词 */
        private String verb;
        /** 提取的名词/对象 */
        private String object;
        /** 提取的修饰语（时间、条件等） */
        private java.util.List<String> modifiers;
        /** 是否成功提取 */
        private boolean extracted;
    }

    /**
     * 排序查询检测结果
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class RankingQueryResult {
        /** 是否是排序查询 */
        private boolean isRankingQuery;
        /** 排序类型：MAX, MIN, TOP, BOTTOM */
        private String rankingType;
        /** 检测到的排序关键词 */
        private String rankingKeyword;
        /** 排序维度（如果能检测到） */
        private String rankingDimension;
    }

    /**
     * 动作消歧结果
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class ActionDisambiguationResult {
        /** 检测到的动词 */
        private String verb;
        /** 检测到的对象 */
        private String object;
        /** 推荐的意图代码 */
        private String recommendedIntent;
        /** 置信度 */
        private double confidence;
        /** 是否成功消歧 */
        private boolean disambiguated;
    }

    /** 否定细分类型 v-W1b */
    enum NegationKind { NONE, EXCLUDE_CONTENT, VETO_WRITE, VETO_READ }

    /**
     * 否定语义信息 v7.4
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class NegationInfo {
        /** 是否包含否定语义 */
        private boolean hasNegation;
        /** 检测到的否定词 */
        private String negationWord;
        /** 被排除的内容 */
        private String excludedContent;
        /** 否定细分类型 v-W1b(默认 NONE) */
        @lombok.Builder.Default
        private NegationKind kind = NegationKind.NONE;

        public boolean hasNegation() {
            return hasNegation;
        }
    }

    /**
     * 增强预处理结果
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class EnhancedPreprocessResult {
        /** 原始输入 */
        private String originalInput;
        /** 处理后的输入 */
        private String processedInput;
        /** 是否过滤了语气词 */
        private boolean modalParticlesFiltered;
        /** 核心提取结果 */
        private CoreExtractionResult coreExtraction;
        /** 排序查询检测 */
        private RankingQueryResult rankingQuery;
        /** 动作消歧结果 */
        private ActionDisambiguationResult actionDisambiguation;
        /** 否定语义信息 v7.5 */
        private NegationInfo negationInfo;
        /** 语用学处理结果 v8.0 */
        private PragmaticProcessingResult pragmaticProcessing;
        /** 检测到的查询特征 */
        private java.util.Set<String> queryFeatures;
        /** 处理耗时(ms) */
        private long processingTimeMs;
    }

    /**
     * 获取 LLM 改写触发阈值
     *
     * @return 阈值 (0-1)
     */
    double getLlmRewriteThreshold();

    // ==================== 结果类 ====================

    /**
     * LLM 改写结果
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class LlmRewriteResult {
        /** 原始输入 */
        private String originalInput;
        /** 改写后的查询 */
        private String rewrittenQuery;
        /** 变更说明列表 */
        private java.util.List<String> changesMade;
        /** 假设说明列表 */
        private java.util.List<String> assumptions;
        /** 改写置信度 */
        private double confidence;
        /** 是否成功 */
        private boolean success;
        /** 错误消息（如果失败） */
        private String errorMessage;
        /** 处理耗时(ms) */
        private long processingTimeMs;
    }

    /**
     * 指代消解结果
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class ReferenceResolutionResult {
        /** 原始输入 */
        private String originalInput;
        /** 解析后的文本 */
        private String resolvedText;
        /** 解析的引用映射 (指代词 -> 实体信息) */
        private java.util.Map<String, ResolvedEntity> resolvedEntities;
        /** 未能解析的指代词列表 */
        private java.util.List<String> unresolvedReferences;
        /** 是否有解析 */
        private boolean hasResolutions;
    }

    /**
     * 解析后的实体
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class ResolvedEntity {
        /** 实体类型 (batch, supplier, customer, etc.) */
        private String entityType;
        /** 实体ID */
        private String entityId;
        /** 实体名称/显示文本 */
        private String entityName;
        /** 解析来源 (context, previous_query, etc.) */
        private String resolvedFrom;
    }
}
