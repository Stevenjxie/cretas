package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.config.ColloquialMappings;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.config.TimeNormalizationRules.TimeRange;
import com.cretas.aims.dto.ai.PreprocessedQuery;
import com.cretas.aims.dto.ai.PreprocessedQuery.QualityAssessment;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.EntitySlot;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.DialectNormalizationService;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.SpellCorrectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询预处理服务实现
 *
 * 实现模块B的完整处理流程：
 * 1. 规则预处理（空白符、口语、时间）
 * 2. 上下文注入（实体槽位、指代消解）
 * 3. 质量评估
 * 4. 条件 LLM 改写
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-15
 */
@Slf4j
@Service
public class QueryPreprocessorServiceImpl implements QueryPreprocessorService {

    private final TimeNormalizationRules timeNormalizationRules;
    private final ColloquialMappings colloquialMappings;
    private final ConversationMemoryService conversationMemoryService;
    private final DashScopeClient dashScopeClient;
    private final ObjectMapper objectMapper;
    private final DialectNormalizationService dialectNormalizationService;
    private final SpellCorrectionService spellCorrectionService;

    /**
     * 是否启用预处理服务
     */
    @Value("${cretas.ai.preprocess.enabled:true}")
    private boolean enabled;

    /**
     * 是否启用 LLM 改写
     */
    @Value("${cretas.ai.preprocess.llm-rewrite-enabled:true}")
    private boolean llmRewriteEnabled;

    /**
     * LLM 改写触发阈值（质量分数低于此值触发）
     */
    @Value("${cretas.ai.preprocess.llm-rewrite-threshold:0.6}")
    private double llmRewriteThreshold;

    /**
     * 指代词模式（用于检测未解析的指代）
     */
    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
            "(这个|那个|这批|那批|这家|那家|该|它|他们|它们|前面的|刚才的|之前的|上次的)"
    );

    /**
     * 模糊时间词模式
     */
    private static final Pattern VAGUE_TIME_PATTERN = Pattern.compile(
            "(最近|近期|前段时间|之前|以前|那段时间)"
    );

    /**
     * 动词模式（用于结构完整性评估）
     */
    private static final Pattern VERB_PATTERN = Pattern.compile(
            "(查询|查看|查|看|显示|统计|分析|计算|获取|导出|添加|新增|创建|修改|更新|删除|入库|出库|发货|收货)"
    );

    /**
     * 名词模式（用于结构完整性评估）
     */
    private static final Pattern NOUN_PATTERN = Pattern.compile(
            "(批次|库存|供应商|客户|产品|设备|订单|记录|数据|信息|状态|数量|金额|重量|质检|检测)"
    );

    /**
     * 否定/排除词模式 v7.4
     */
    private static final Pattern NEGATION_PATTERN = Pattern.compile(
            "(除了|排除|不要|不包括|去掉|去除|不含|除开|不是|非|不想要|别给我)"
    );

    /**
     * W1b: 否定否决副词(语句级否定,非内容排除)。锚定句首/近句首以避免误吞合法写。
     * 例: "别查/不用查/先不开始" 命中; "取消订单/作废这张单"(动作动词非否定副词) 不命中。
     */
    private static final Pattern VETO_ADVERB_PATTERN = Pattern.compile(
            "^\\s*(我?|那|这个?|现在|先|那么)?\\s*(别|不用|不要|甭|无需|不必|先不|暂时不|不想|不需要)"
    );

    /** W1b: 不用的/不想的/不要的 用作形容词性定语(unused/unwanted)且位于句首时,非否决副词 */
    private static final java.util.regex.Pattern VETO_FALSE_FRIEND = java.util.regex.Pattern.compile(
            "^\\s*(不用|不想|不要)的");

    /** W1b: 不用谢/不必客气/甭麻烦 等寒暄,非否决 */
    private static final java.util.regex.Pattern VETO_POLITENESS = java.util.regex.Pattern.compile(
            "^\\s*(不用|不必|甭|无需)\\s*(谢|客气|麻烦|担心|介意|送|管我)");

    // ==================== 语用学处理正则模式 (v8.0) ====================

    /**
     * 反问句模式1: 难道...吗/呢
     * 例如: "难道今天没有原料到货吗"
     */
    private static final Pattern RHETORICAL_NANDAO_PATTERN = Pattern.compile(
            "难道(.+?)(吗|呢)[?？]?"
    );

    /**
     * 反问句模式2: 怎么...不
     * 例如: "怎么还不发货", "怎么不检查一下"
     */
    private static final Pattern RHETORICAL_ZENME_PATTERN = Pattern.compile(
            "怎么(.+?)不(.+?)([?？])?$"
    );

    /**
     * 反问句模式3: 为什么...没
     * 例如: "为什么这批货没入库", "为什么还没完成质检"
     */
    private static final Pattern RHETORICAL_WEISHENME_PATTERN = Pattern.compile(
            "为什么(.+?)没(.+?)([?？])?$"
    );

    /**
     * 反问句模式4: 不是...吗
     * 例如: "不是说今天要发货吗", "不是应该入库了吗"
     */
    private static final Pattern RHETORICAL_BUSHI_PATTERN = Pattern.compile(
            "不是(.+?)(吗|么)[?？]?"
    );

    /**
     * 转折句模式: (虽然|尽管|虽说)...(但是|可是|然而|不过)...
     * 例如: "虽然产量达标了，但是不合格品率上升了不少"
     */
    private static final Pattern CONCESSION_PATTERN = Pattern.compile(
            "(虽然|尽管|虽说)(.+?)(但是|可是|然而|不过|但)(.+)"
    );

    /**
     * 双重否定模式
     * 例如: "不是不想用", "并非不知道", "没有不合格", "不能不检查", "不会不通知"
     */
    private static final Pattern DOUBLE_NEGATIVE_PATTERN = Pattern.compile(
            "(不是不|并非不|没有不|不能不|不会不|不得不|无法不|难以不)(.+)"
    );

    @Autowired
    public QueryPreprocessorServiceImpl(
            TimeNormalizationRules timeNormalizationRules,
            ColloquialMappings colloquialMappings,
            @Autowired(required = false) ConversationMemoryService conversationMemoryService,
            @Autowired(required = false) DashScopeClient dashScopeClient,
            ObjectMapper objectMapper,
            @Autowired(required = false) DialectNormalizationService dialectNormalizationService,
            @Autowired(required = false) SpellCorrectionService spellCorrectionService) {
        this.timeNormalizationRules = timeNormalizationRules;
        this.colloquialMappings = colloquialMappings;
        this.conversationMemoryService = conversationMemoryService;
        this.dashScopeClient = dashScopeClient;
        this.objectMapper = objectMapper;
        this.dialectNormalizationService = dialectNormalizationService;
        this.spellCorrectionService = spellCorrectionService;
    }

    @Override
    public PreprocessedQuery preprocess(String input, ConversationContext context) {
        if (!enabled || input == null || input.trim().isEmpty()) {
            return PreprocessedQuery.passThrough(input);
        }

        long startTime = System.currentTimeMillis();
        List<PreprocessedQuery.ProcessingStep> steps = new ArrayList<>();

        // 收集处理结果的变量
        List<String> foundColloquials = new ArrayList<>();
        List<String> standardizedExpressions = new ArrayList<>();
        List<TimeRange> extractedTimeRanges = new ArrayList<>();
        TimeRange primaryTimeRange = null;
        Map<String, PreprocessedQuery.ResolvedReference> resolvedReferences = new HashMap<>();
        List<String> unresolvedReferences = new ArrayList<>();
        String rewrittenText = null;
        List<String> rewriteChanges = new ArrayList<>();
        List<String> rewriteAssumptions = new ArrayList<>();
        Double rewriteConfidence = null;
        boolean llmRewriteTriggered = false;

        try {
            String processedText = input;
            LocalDateTime now = LocalDateTime.now();

            // ==================== Step 1: 规则预处理 ====================
            long stepStart = System.currentTimeMillis();

            // 1.1 空白符规范化
            processedText = normalizeWhitespace(processedText);

            // 1.1.5 v11.3: 特殊字符清理
            processedText = cleanSpecialCharacters(processedText);

            // 1.1.6 v11.4: 拼写纠正（同音错别字）
            if (spellCorrectionService != null) {
                processedText = spellCorrectionService.correct(processedText);
            }

            // 1.2 口语标准化
            ColloquialMappings.StandardizationResult colloquialResult =
                    colloquialMappings.findAndReplace(processedText);
            if (colloquialResult.hasReplacements()) {
                processedText = colloquialResult.getProcessedText();
                foundColloquials = colloquialResult.getFoundColloquials();
                standardizedExpressions = colloquialResult.getStandardizedTerms();
            }

            // 1.3 时间表达归一化
            TimeNormalizationRules.NormalizationResult timeResult =
                    timeNormalizationRules.extractAndReplace(processedText, now);
            if (timeResult.hasTimeRange()) {
                processedText = timeResult.getProcessedText();
                extractedTimeRanges = timeResult.getExtractedRanges();
                primaryTimeRange = timeResult.getFirstRange().orElse(null);
            }

            steps.add(PreprocessedQuery.ProcessingStep.of(
                    "rule_preprocess",
                    "规则预处理完成",
                    System.currentTimeMillis() - stepStart
            ));

            String normalizedText = processedText;

            // ==================== Step 2: 上下文注入 ====================
            stepStart = System.currentTimeMillis();

            // 2.1 指代消解
            ReferenceResolutionResult refResult = resolveReferences(processedText, context);
            if (refResult.isHasResolutions()) {
                processedText = refResult.getResolvedText();
                for (Map.Entry<String, ResolvedEntity> entry : refResult.getResolvedEntities().entrySet()) {
                    resolvedReferences.put(entry.getKey(), PreprocessedQuery.ResolvedReference.of(
                            entry.getValue().getEntityType(),
                            entry.getValue().getEntityId(),
                            entry.getValue().getEntityName(),
                            entry.getKey()
                    ));
                }
            }
            if (refResult.getUnresolvedReferences() != null && !refResult.getUnresolvedReferences().isEmpty()) {
                unresolvedReferences = refResult.getUnresolvedReferences();
            }

            steps.add(PreprocessedQuery.ProcessingStep.of(
                    "context_injection",
                    "上下文注入完成",
                    System.currentTimeMillis() - stepStart
            ));

            // ==================== Step 3: 质量评估 ====================
            stepStart = System.currentTimeMillis();

            QualityAssessment assessment = assessQuality(processedText, context, refResult);

            steps.add(PreprocessedQuery.ProcessingStep.of(
                    "quality_assessment",
                    String.format("质量评估完成 (score=%.2f)", assessment.getTotalScore()),
                    System.currentTimeMillis() - stepStart
            ));

            // ==================== Step 4: 条件 LLM 改写 ====================
            String finalQuery = processedText;

            if (llmRewriteEnabled && assessment.getTotalScore() < llmRewriteThreshold) {
                stepStart = System.currentTimeMillis();

                LlmRewriteResult llmResult = rewriteWithLLM(input, context);
                if (llmResult.isSuccess()) {
                    llmRewriteTriggered = true;
                    rewrittenText = llmResult.getRewrittenQuery();
                    rewriteChanges = llmResult.getChangesMade();
                    rewriteAssumptions = llmResult.getAssumptions();
                    rewriteConfidence = llmResult.getConfidence();
                    finalQuery = llmResult.getRewrittenQuery();

                    steps.add(PreprocessedQuery.ProcessingStep.of(
                            "llm_rewrite",
                            "LLM 改写完成",
                            llmResult.getProcessingTimeMs()
                    ));
                } else {
                    log.warn("LLM 改写失败: {}", llmResult.getErrorMessage());
                    steps.add(PreprocessedQuery.ProcessingStep.of(
                            "llm_rewrite",
                            "LLM 改写失败: " + llmResult.getErrorMessage(),
                            System.currentTimeMillis() - stepStart
                    ));
                }
            }

            PreprocessedQuery result = PreprocessedQuery.builder()
                    .originalInput(input)
                    .normalizedText(normalizedText)
                    .rewrittenText(rewrittenText)
                    .finalQuery(finalQuery)
                    .extractedTimeRanges(extractedTimeRanges)
                    .primaryTimeRange(primaryTimeRange)
                    .foundColloquials(foundColloquials)
                    .standardizedExpressions(standardizedExpressions)
                    .resolvedReferences(resolvedReferences)
                    .unresolvedReferences(unresolvedReferences)
                    .qualityScore(assessment.getTotalScore())
                    .qualityAssessment(assessment)
                    .llmRewriteTriggered(llmRewriteTriggered)
                    .rewriteChanges(rewriteChanges)
                    .rewriteAssumptions(rewriteAssumptions)
                    .rewriteConfidence(rewriteConfidence)
                    .processedAt(now)
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .processingSteps(steps)
                    .build();

            log.debug("查询预处理完成: '{}' -> '{}' (score={}, time={}ms)",
                    truncate(input, 30), truncate(result.getFinalQuery(), 30),
                    result.getQualityScore(), result.getProcessingTimeMs());

            return result;

        } catch (Exception e) {
            log.error("查询预处理异常: input={}, error={}", truncate(input, 50), e.getMessage(), e);
            return PreprocessedQuery.passThrough(input);
        }
    }

    @Override
    public PreprocessedQuery preprocessRulesOnly(String input) {
        if (input == null || input.trim().isEmpty()) {
            return PreprocessedQuery.passThrough(input);
        }

        long startTime = System.currentTimeMillis();
        String processedText = input;
        LocalDateTime now = LocalDateTime.now();

        // 空白符规范化
        processedText = normalizeWhitespace(processedText);

        // v11.3: 特殊字符清理
        processedText = cleanSpecialCharacters(processedText);

        // v11.4: 拼写纠正（同音错别字）
        if (spellCorrectionService != null) {
            processedText = spellCorrectionService.correct(processedText);
        }

        // 口语标准化
        ColloquialMappings.StandardizationResult colloquialResult =
                colloquialMappings.findAndReplace(processedText);
        if (colloquialResult.hasReplacements()) {
            processedText = colloquialResult.getProcessedText();
        }

        // 时间归一化
        TimeNormalizationRules.NormalizationResult timeResult =
                timeNormalizationRules.extractAndReplace(processedText, now);
        if (timeResult.hasTimeRange()) {
            processedText = timeResult.getProcessedText();
        }

        return PreprocessedQuery.builder()
                .originalInput(input)
                .normalizedText(processedText)
                .finalQuery(processedText)
                .foundColloquials(colloquialResult.getFoundColloquials())
                .standardizedExpressions(colloquialResult.getStandardizedTerms())
                .extractedTimeRanges(timeResult.getExtractedRanges())
                .primaryTimeRange(timeResult.getFirstRange().orElse(null))
                .qualityScore(1.0) // 不评估质量
                .processedAt(now)
                .processingTimeMs(System.currentTimeMillis() - startTime)
                .build();
    }

    @Override
    public double assessQueryQuality(String input, ConversationContext context) {
        if (input == null || input.trim().isEmpty()) {
            return 0.0;
        }

        ReferenceResolutionResult refResult = resolveReferences(input, context);
        QualityAssessment assessment = assessQuality(input, context, refResult);
        return assessment.getTotalScore();
    }

    @Override
    public LlmRewriteResult rewriteWithLLM(String input, ConversationContext context) {
        long startTime = System.currentTimeMillis();

        if (dashScopeClient == null || !dashScopeClient.isAvailable()) {
            return LlmRewriteResult.builder()
                    .originalInput(input)
                    .success(false)
                    .errorMessage("DashScope 服务不可用")
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        try {
            // 构建系统提示词
            String systemPrompt = buildRewriteSystemPrompt(context);

            // 调用 LLM
            String response = dashScopeClient.chatLowTemp(systemPrompt, input);

            // 解析响应
            return parseRewriteResponse(input, response, startTime);

        } catch (Exception e) {
            log.error("LLM 改写失败: input={}, error={}", truncate(input, 50), e.getMessage(), e);
            return LlmRewriteResult.builder()
                    .originalInput(input)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public ReferenceResolutionResult resolveReferences(String input, ConversationContext context) {
        if (input == null || input.trim().isEmpty()) {
            return ReferenceResolutionResult.builder()
                    .originalInput(input)
                    .resolvedText(input)
                    .resolvedEntities(Collections.emptyMap())
                    .unresolvedReferences(Collections.emptyList())
                    .hasResolutions(false)
                    .build();
        }

        String resolvedText = input;
        Map<String, ResolvedEntity> resolvedEntities = new HashMap<>();
        List<String> unresolvedReferences = new ArrayList<>();

        // 尝试使用 ConversationMemoryService 进行指代消解
        if (conversationMemoryService != null && context != null && context.getSessionId() != null) {
            resolvedText = conversationMemoryService.resolveReference(context.getSessionId(), input);
            // 如果文本有变化，说明有解析
            if (!resolvedText.equals(input)) {
                // 解析成功，但我们需要确定解析了什么
                // 通过比较原文和解析后文本来推断
                detectResolvedReferences(input, resolvedText, context, resolvedEntities);
            }
        }

        // 检测未解析的指代词
        Matcher matcher = REFERENCE_PATTERN.matcher(resolvedText);
        while (matcher.find()) {
            String ref = matcher.group();
            if (!resolvedEntities.containsKey(ref)) {
                unresolvedReferences.add(ref);
            }
        }

        return ReferenceResolutionResult.builder()
                .originalInput(input)
                .resolvedText(resolvedText)
                .resolvedEntities(resolvedEntities)
                .unresolvedReferences(unresolvedReferences)
                .hasResolutions(!resolvedEntities.isEmpty())
                .build();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isLlmRewriteEnabled() {
        return llmRewriteEnabled;
    }

    @Override
    public double getLlmRewriteThreshold() {
        return llmRewriteThreshold;
    }

    // ==================== 私有方法 ====================

    /**
     * 空白符规范化
     */
    private String normalizeWhitespace(String text) {
        if (text == null) return null;
        // Trim 前后空白
        String result = text.trim();
        // 合并多个空格为一个
        result = result.replaceAll("\\s+", " ");
        // 移除标点符号后的多余空格
        result = result.replaceAll("([,，.。!！?？;；:：])\\s+", "$1");
        return result;
    }

    /**
     * v11.3: 清理特殊字符
     * 移除干扰意图识别的特殊符号，保留有意义的字符
     */
    private String cleanSpecialCharacters(String text) {
        if (text == null || text.isEmpty()) return text;

        // 移除装饰性特殊字符
        String result = text;
        result = result.replaceAll("[【】\\[\\]「」『』《》<>〈〉]", "");  // 各种括号
        result = result.replaceAll("[>>><<<——…·•※★☆◆◇▲△▼▽]", "");  // 装饰符号
        result = result.replaceAll("[!@#$%^&*()+={}|\\\\:\"<>?`~]", "");  // 英文特殊符号
        result = result.replaceAll("\\s+", " ");  // 清理后再规范空格
        return result.trim();
    }

    /**
     * 评估查询质量
     */
    private QualityAssessment assessQuality(String input, ConversationContext context,
                                            ReferenceResolutionResult refResult) {
        QualityAssessment.QualityAssessmentBuilder builder = QualityAssessment.builder();
        List<String> deductions = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        double lengthScore = 1.0;
        double referenceScore = 1.0;
        double timeScore = 1.0;
        double structureScore = 1.0;

        // 1. 长度评分
        int length = input.trim().length();
        if (length < 3) {
            lengthScore = 0.2;
            deductions.add("输入过短 (<3字)");
            suggestions.add("请提供更详细的查询描述");
        } else if (length < 5) {
            lengthScore = 0.5;
            deductions.add("输入较短 (<5字)");
            suggestions.add("建议补充更多信息");
        } else if (length < 8) {
            lengthScore = 0.8;
        }

        // 2. 指代完整性评分
        if (refResult != null && refResult.getUnresolvedReferences() != null) {
            int unresolvedCount = refResult.getUnresolvedReferences().size();
            if (unresolvedCount > 0) {
                // 检查上下文是否有对应槽位
                boolean hasContext = context != null && !context.getEntitySlots().isEmpty();
                if (!hasContext) {
                    referenceScore = Math.max(0.3, 1.0 - unresolvedCount * 0.3);
                    deductions.add("包含未解析指代词: " + String.join(", ", refResult.getUnresolvedReferences()));
                    suggestions.add("请指定具体的批次/供应商/客户名称");
                } else {
                    // 有上下文但仍有未解析引用，扣分较少
                    referenceScore = Math.max(0.5, 1.0 - unresolvedCount * 0.15);
                }
            }
        }

        // 3. 时间明确性评分
        if (VAGUE_TIME_PATTERN.matcher(input).find()) {
            // 检查是否已经被时间归一化处理过
            boolean hasTimeRange = timeNormalizationRules.containsTimeExpression(input);
            if (!hasTimeRange) {
                timeScore = 0.7;
                deductions.add("包含模糊时间表述");
                suggestions.add("建议指定具体时间范围，如\"最近7天\"或\"本月\"");
            }
        }

        // 4. 结构完整性评分 (动词+名词)
        boolean hasVerb = VERB_PATTERN.matcher(input).find();
        boolean hasNoun = NOUN_PATTERN.matcher(input).find();

        if (!hasVerb && !hasNoun) {
            structureScore = 0.4;
            deductions.add("缺少操作动词和对象名词");
            suggestions.add("请描述要执行的操作和对象");
        } else if (!hasVerb) {
            structureScore = 0.6;
            deductions.add("缺少操作动词");
            suggestions.add("建议添加操作说明（如查询、添加等）");
        } else if (!hasNoun) {
            structureScore = 0.7;
            deductions.add("缺少明确对象");
            suggestions.add("建议明确操作对象");
        }

        // 计算总分（加权平均）
        double totalScore = (lengthScore * 0.2 + referenceScore * 0.3 +
                            timeScore * 0.2 + structureScore * 0.3);
        totalScore = Math.max(0.0, Math.min(1.0, totalScore));

        return builder
                .totalScore(totalScore)
                .lengthScore(lengthScore)
                .referenceScore(referenceScore)
                .timeScore(timeScore)
                .structureScore(structureScore)
                .deductionReasons(deductions)
                .improvementSuggestions(suggestions)
                .build();
    }

    /**
     * 构建 LLM 改写系统提示词
     */
    private String buildRewriteSystemPrompt(ConversationContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 角色\n");
        sb.append("你是查询改写助手。将用户的口语化、模糊查询改写为清晰、具体的标准查询。\n\n");

        sb.append("## 改写原则\n");
        sb.append("1. 保持用户的原始意图\n");
        sb.append("2. 补充缺失的上下文信息\n");
        sb.append("3. 将口语表达转换为正式表达\n");
        sb.append("4. 明确模糊的时间/数量表述\n");
        sb.append("5. 不要添加用户未提及的限制条件\n\n");

        sb.append("## 上下文信息\n");
        sb.append("当前时间: ").append(LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");

        if (context != null) {
            // 添加实体槽位信息
            if (context.getEntitySlots() != null && !context.getEntitySlots().isEmpty()) {
                sb.append("用户最近查询的实体:\n");
                for (Map.Entry<EntitySlot.SlotType, EntitySlot> entry : context.getEntitySlots().entrySet()) {
                    EntitySlot slot = entry.getValue();
                    sb.append("- ").append(entry.getKey().name().toLowerCase())
                      .append(": ").append(slot.getName());
                    if (slot.getId() != null) {
                        sb.append(" (ID: ").append(slot.getId()).append(")");
                    }
                    sb.append("\n");
                }
            }

            // 添加对话摘要
            if (context.getConversationSummary() != null && !context.getConversationSummary().isEmpty()) {
                sb.append("\n对话摘要: ").append(context.getConversationSummary()).append("\n");
            }

            // 最后意图
            if (context.getLastIntentCode() != null) {
                sb.append("上次操作: ").append(context.getLastIntentCode()).append("\n");
            }
        }

        sb.append("\n## 输出格式 (JSON)\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"rewritten_query\": \"改写后的查询\",\n");
        sb.append("  \"changes_made\": [\"变更说明1\", \"变更说明2\"],\n");
        sb.append("  \"assumptions\": [\"假设说明1\"],\n");
        sb.append("  \"confidence\": 0.85\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append("仅返回 JSON，不要包含其他文字。\n");

        return sb.toString();
    }

    /**
     * 解析 LLM 改写响应
     */
    private LlmRewriteResult parseRewriteResponse(String originalInput, String response, long startTime) {
        try {
            // 提取 JSON
            Pattern pattern = Pattern.compile("\\{[\\s\\S]*\\}");
            Matcher matcher = pattern.matcher(response);

            if (!matcher.find()) {
                log.warn("无法从 LLM 响应中提取 JSON: {}", truncate(response, 100));
                return LlmRewriteResult.builder()
                        .originalInput(originalInput)
                        .rewrittenQuery(originalInput) // 返回原文
                        .success(false)
                        .errorMessage("无法解析 LLM 响应")
                        .processingTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            JsonNode json = objectMapper.readTree(matcher.group());

            String rewrittenQuery = json.has("rewritten_query")
                    ? json.get("rewritten_query").asText()
                    : originalInput;

            List<String> changesMade = new ArrayList<>();
            if (json.has("changes_made") && json.get("changes_made").isArray()) {
                for (JsonNode node : json.get("changes_made")) {
                    changesMade.add(node.asText());
                }
            }

            List<String> assumptions = new ArrayList<>();
            if (json.has("assumptions") && json.get("assumptions").isArray()) {
                for (JsonNode node : json.get("assumptions")) {
                    assumptions.add(node.asText());
                }
            }

            double confidence = json.has("confidence")
                    ? json.get("confidence").asDouble()
                    : 0.8;

            return LlmRewriteResult.builder()
                    .originalInput(originalInput)
                    .rewrittenQuery(rewrittenQuery)
                    .changesMade(changesMade)
                    .assumptions(assumptions)
                    .confidence(confidence)
                    .success(true)
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (Exception e) {
            log.error("解析 LLM 改写响应失败: {}", e.getMessage(), e);
            return LlmRewriteResult.builder()
                    .originalInput(originalInput)
                    .success(false)
                    .errorMessage("解析响应失败: " + e.getMessage())
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 检测已解析的引用（通过比较原文和解析后文本）
     */
    private void detectResolvedReferences(String original, String resolved,
                                          ConversationContext context,
                                          Map<String, ResolvedEntity> resolvedEntities) {
        if (context == null || context.getEntitySlots() == null) {
            return;
        }

        // 遍历上下文中的槽位，检查是否被使用
        for (Map.Entry<EntitySlot.SlotType, EntitySlot> entry : context.getEntitySlots().entrySet()) {
            EntitySlot slot = entry.getValue();
            if (slot.getName() != null && resolved.contains(slot.getName())) {
                // 检查原文中是否有对应的指代词
                String[] referencePatterns = getReferencePatterns(entry.getKey());
                for (String pattern : referencePatterns) {
                    if (original.contains(pattern) && !resolved.contains(pattern)) {
                        resolvedEntities.put(pattern, ResolvedEntity.builder()
                                .entityType(entry.getKey().name().toLowerCase())
                                .entityId(slot.getId())
                                .entityName(slot.getName())
                                .resolvedFrom("context")
                                .build());
                    }
                }
            }
        }
    }

    /**
     * 获取槽位类型对应的指代词模式
     */
    private String[] getReferencePatterns(EntitySlot.SlotType type) {
        switch (type) {
            case BATCH:
                return new String[]{"这批", "那批", "该批次", "这个批次"};
            case DISH:
                return new String[]{"那道菜", "这道菜", "该菜品", "这个菜", "那个菜", "这款菜", "那款菜", "它"};
            case STORE:
                return new String[]{"那家店", "这家店", "该店", "那家", "这家", "那个店", "这个店", "该门店"};
            case SUPPLIER:
                return new String[]{"这家", "那家", "那个供应商", "他们"};
            case CUSTOMER:
                return new String[]{"这个客户", "那个客户", "对方"};
            case PRODUCT:
                return new String[]{"这个产品", "那个产品", "这种货"};
            case EQUIPMENT:
                return new String[]{"这台设备", "那台设备", "这台机器"};
            default:
                return new String[]{};
        }
    }

    /**
     * 截断字符串
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 检测否定/排除语义 v7.4
     * 当检测到否定词时，返回标记信息以便后续处理。
     *
     * <p>无 {@code knowledgeBase} 的调用路径(如 enhancedPreprocess @1473):仅做
     * exclusion 检测,{@code kind} 取 {@code EXCLUDE_CONTENT} 或 {@code NONE},
     * <b>不</b>产生 veto kind —— exclusion 行为与 W1b 之前完全一致(finding 4)。</p>
     */
    public NegationInfo detectNegationSemantics(String input) {
        return detectNegationSemantics(input, null);
    }

    /**
     * W1b: 带 knowledge base 的否定语义检测。在 exclusion 之外叠加 veto 细分(VETO_READ/VETO_WRITE)。
     *
     * <p>语句级否决("别查/不用看")优先于内容排除:veto 命中时 {@code kind=VETO_*};
     * 否则 exclusion 命中 {@code kind=EXCLUDE_CONTENT};都不命中 {@code kind=NONE}。
     * {@code kb==null} 时退化为纯 exclusion 检测(向后兼容)。</p>
     *
     * @param input 用户原始输入
     * @param kb    意图知识库,用于判定否定副词后动词的 ActionType(可为 null)
     */
    public NegationInfo detectNegationSemantics(String input, IntentKnowledgeBase kb) {
        if (input == null || input.trim().isEmpty()) {
            return new NegationInfo(false, null, null, QueryPreprocessorService.NegationKind.NONE);
        }
        // veto 细分(kb==null 时恒返 NONE,纯 exclusion 路径不变)
        QueryPreprocessorService.NegationKind vetoKind =
                (kb == null) ? QueryPreprocessorService.NegationKind.NONE : detectNegationVeto(input, kb);
        Matcher matcher = NEGATION_PATTERN.matcher(input);
        if (matcher.find()) {
            String negationWord = matcher.group(1);
            // 提取被排除的内容
            String excludedContent = extractExcludedContent(input, matcher.end());
            // veto 优先于 exclusion(用户语句级否决比内容过滤更强);否则 EXCLUDE_CONTENT
            QueryPreprocessorService.NegationKind kind =
                    (vetoKind != QueryPreprocessorService.NegationKind.NONE)
                            ? vetoKind : QueryPreprocessorService.NegationKind.EXCLUDE_CONTENT;
            return new NegationInfo(true, negationWord, excludedContent, kind);
        }
        // 无 exclusion 但有 veto(如 "不用查库存了" 不匹配 NEGATION_PATTERN)
        if (vetoKind != QueryPreprocessorService.NegationKind.NONE) {
            return new NegationInfo(true, null, null, vetoKind);
        }
        return new NegationInfo(false, null, null, QueryPreprocessorService.NegationKind.NONE);
    }

    /**
     * W1b: 细分否定否决类型。语句级否定("别查/不用看")→ VETO_READ;
     * 否定写动词("别开始/不用创建")→ VETO_WRITE。双重否定优先返 NONE(finding 6)。
     *
     * <p>采用<b>参数形式</b> 传入 {@code kb}:{@code QueryPreprocessorServiceImpl} 未注入
     * {@code IntentKnowledgeBase}(其 ctor 7 参不含它),由调用方(IRP, T3/T4)持有并传入。</p>
     *
     * @param input 用户原始输入
     * @param kb    意图知识库,提供 {@code detectActionType} 判定否定副词后的动词性质
     * @return VETO_READ / VETO_WRITE / NONE
     */
    @Override
    public QueryPreprocessorService.NegationKind detectNegationVeto(String input, IntentKnowledgeBase kb) {
        if (input == null) return QueryPreprocessorService.NegationKind.NONE;
        String s = input.trim();
        if (s.isEmpty()) return QueryPreprocessorService.NegationKind.NONE;
        // 1) 双重否定守卫(finding 6,必须第一步):不是不想查/不能不查 → 实为肯定查询
        if (DOUBLE_NEGATIVE_PATTERN.matcher(s).find()) return QueryPreprocessorService.NegationKind.NONE;
        // 2) "不用的"(unused)等 false friend
        if (VETO_FALSE_FRIEND.matcher(s).find()) return QueryPreprocessorService.NegationKind.NONE;
        // 2b) 寒暄 false friend: "不用谢/不必客气/甭麻烦" 后接合法查询时,否定副词非否决(W1b)
        if (VETO_POLITENESS.matcher(s).find()) return QueryPreprocessorService.NegationKind.NONE;
        // 3) 否定副词(句首/近句首)
        Matcher m = VETO_ADVERB_PATTERN.matcher(s);
        if (!m.find()) return QueryPreprocessorService.NegationKind.NONE;
        String remainder = s.substring(m.end()).trim();
        if (remainder.startsWith("给我")) remainder = remainder.substring(2).trim();
        // 4) 否定副词后的动词决定 veto 细分。仅<b>明确动词</b>触发 veto:
        //    CREATE/UPDATE/DELETE → VETO_WRITE; QUERY → VETO_READ;
        //    AMBIGUOUS/UNKNOWN/null(否定副词后无明确动词)→ NONE,不做明确读否决,
        //    交给常规路由/排除逻辑处理 —— 这样既保留句首 "不要[名词]" 的 EXCLUDE_CONTENT,
        //    也避免对模糊/无动词输入产生误否决。写操作无论如何仍受 W0 守护,
        //    故对模糊/无动词否定保守默认 NONE(更少误否决)是安全选择。
        IntentKnowledgeBase.ActionType at = (kb == null)
                ? IntentKnowledgeBase.ActionType.UNKNOWN
                : kb.detectActionType(remainder.isEmpty() ? s : remainder);
        if (at == IntentKnowledgeBase.ActionType.CREATE
                || at == IntentKnowledgeBase.ActionType.UPDATE
                || at == IntentKnowledgeBase.ActionType.DELETE) {
            return QueryPreprocessorService.NegationKind.VETO_WRITE;
        }
        if (at == IntentKnowledgeBase.ActionType.QUERY) {
            return QueryPreprocessorService.NegationKind.VETO_READ;
        }
        // AMBIGUOUS / UNKNOWN(无明确动词)→ NONE
        return QueryPreprocessorService.NegationKind.NONE;
    }

    /**
     * 提取否定词后面被排除的内容 v7.4
     */
    private String extractExcludedContent(String input, int startPos) {
        // 提取否定词后面的名词短语
        String afterNegation = input.substring(startPos).trim();
        // 简单提取：取到下一个连接词或句末
        Pattern contentPattern = Pattern.compile("^([^，,。的之外还]+)");
        Matcher m = contentPattern.matcher(afterNegation);
        if (m.find()) {
            return m.group(1).trim();
        }
        return afterNegation;
    }

    // ==================== 复杂语义预处理方法实现 ====================

    /**
     * 语气词正则模式
     */
    private static final Pattern MODAL_PARTICLE_PATTERN = Pattern.compile(
            "[吧啊吗呢呀嘛哦哇啦嘞咯呗哈嗯]$"
    );

    /**
     * 语气词组合模式（句尾多个语气词）
     */
    private static final Pattern MODAL_PARTICLE_COMBO_PATTERN = Pattern.compile(
            "[吧啊吗呢呀嘛哦哇啦嘞咯呗哈嗯]{1,3}$"
    );

    /**
     * 排序关键词映射
     */
    private static final Map<String, String> RANKING_KEYWORDS = new LinkedHashMap<>();
    static {
        // MAX 类型
        RANKING_KEYWORDS.put("最多", "MAX");
        RANKING_KEYWORDS.put("最高", "MAX");
        RANKING_KEYWORDS.put("最大", "MAX");
        RANKING_KEYWORDS.put("最快", "MAX");
        RANKING_KEYWORDS.put("最好", "MAX");
        RANKING_KEYWORDS.put("最贵", "MAX");
        RANKING_KEYWORDS.put("最重", "MAX");
        RANKING_KEYWORDS.put("最长", "MAX");
        // MIN 类型
        RANKING_KEYWORDS.put("最少", "MIN");
        RANKING_KEYWORDS.put("最低", "MIN");
        RANKING_KEYWORDS.put("最小", "MIN");
        RANKING_KEYWORDS.put("最慢", "MIN");
        RANKING_KEYWORDS.put("最差", "MIN");
        RANKING_KEYWORDS.put("最便宜", "MIN");
        RANKING_KEYWORDS.put("最轻", "MIN");
        RANKING_KEYWORDS.put("最短", "MIN");
        // TOP 类型
        RANKING_KEYWORDS.put("前几", "TOP");
        RANKING_KEYWORDS.put("top", "TOP");
        RANKING_KEYWORDS.put("排名前", "TOP");
        RANKING_KEYWORDS.put("排行前", "TOP");
        // BOTTOM 类型
        RANKING_KEYWORDS.put("后几", "BOTTOM");
        RANKING_KEYWORDS.put("排名后", "BOTTOM");
        RANKING_KEYWORDS.put("倒数", "BOTTOM");
    }

    /**
     * 动词+名词组合到意图的映射
     */
    private static final Map<String, String> ACTION_INTENT_MAPPINGS = new LinkedHashMap<>();
    static {
        // 处理类动作
        ACTION_INTENT_MAPPINGS.put("处理+原料", "MATERIAL_BATCH_CONSUME");
        ACTION_INTENT_MAPPINGS.put("处理+物料", "MATERIAL_BATCH_CONSUME");
        ACTION_INTENT_MAPPINGS.put("处理+告警", "ALERT_ACKNOWLEDGE");
        ACTION_INTENT_MAPPINGS.put("处理+预警", "ALERT_ACKNOWLEDGE");
        ACTION_INTENT_MAPPINGS.put("处理+异常", "ALERT_ACKNOWLEDGE");
        ACTION_INTENT_MAPPINGS.put("处理+不合格", "QUALITY_DISPOSITION_EXECUTE");

        // 提交/执行类动作
        ACTION_INTENT_MAPPINGS.put("提交+质检", "QUALITY_CHECK_EXECUTE");
        ACTION_INTENT_MAPPINGS.put("执行+质检", "QUALITY_CHECK_EXECUTE");
        ACTION_INTENT_MAPPINGS.put("做+质检", "QUALITY_CHECK_EXECUTE");

        // 确认类动作
        ACTION_INTENT_MAPPINGS.put("确认+收货", "SHIPMENT_STATUS_UPDATE");
        ACTION_INTENT_MAPPINGS.put("确认+发货", "SHIPMENT_STATUS_UPDATE");
        ACTION_INTENT_MAPPINGS.put("确认+入库", "MATERIAL_BATCH_CREATE");

        // 更新/修改类动作
        ACTION_INTENT_MAPPINGS.put("更新+发货", "SHIPMENT_STATUS_UPDATE");
        ACTION_INTENT_MAPPINGS.put("修改+发货", "SHIPMENT_UPDATE");
        ACTION_INTENT_MAPPINGS.put("更新+状态", "SHIPMENT_STATUS_UPDATE");
        ACTION_INTENT_MAPPINGS.put("修改+库存", "MATERIAL_ADJUST_QUANTITY");
        ACTION_INTENT_MAPPINGS.put("调整+库存", "MATERIAL_ADJUST_QUANTITY");

        // 开始/启动类动作
        ACTION_INTENT_MAPPINGS.put("开始+生产", "PROCESSING_BATCH_START");
        ACTION_INTENT_MAPPINGS.put("启动+生产", "PROCESSING_BATCH_START");
        ACTION_INTENT_MAPPINGS.put("启动+批次", "PROCESSING_BATCH_START");
        ACTION_INTENT_MAPPINGS.put("开始+加工", "PROCESSING_BATCH_START");

        // 停止/暂停类动作
        ACTION_INTENT_MAPPINGS.put("停止+生产", "PROCESSING_BATCH_CANCEL");
        ACTION_INTENT_MAPPINGS.put("暂停+生产", "PROCESSING_BATCH_PAUSE");
        ACTION_INTENT_MAPPINGS.put("暂停+批次", "PROCESSING_BATCH_PAUSE");
        ACTION_INTENT_MAPPINGS.put("恢复+生产", "PROCESSING_BATCH_RESUME");
        ACTION_INTENT_MAPPINGS.put("恢复+批次", "PROCESSING_BATCH_RESUME");

        // 完成类动作
        ACTION_INTENT_MAPPINGS.put("完成+生产", "PROCESSING_BATCH_COMPLETE");
        ACTION_INTENT_MAPPINGS.put("完成+批次", "PROCESSING_BATCH_COMPLETE");
        ACTION_INTENT_MAPPINGS.put("结束+生产", "PROCESSING_BATCH_COMPLETE");

        // 创建/新建类动作
        ACTION_INTENT_MAPPINGS.put("创建+批次", "PROCESSING_BATCH_CREATE");
        ACTION_INTENT_MAPPINGS.put("新建+批次", "PROCESSING_BATCH_CREATE");
        ACTION_INTENT_MAPPINGS.put("创建+原料", "MATERIAL_BATCH_CREATE");
        ACTION_INTENT_MAPPINGS.put("新建+原料", "MATERIAL_BATCH_CREATE");
        ACTION_INTENT_MAPPINGS.put("添加+原料", "MATERIAL_BATCH_CREATE");
        ACTION_INTENT_MAPPINGS.put("录入+原料", "MATERIAL_BATCH_CREATE");
        ACTION_INTENT_MAPPINGS.put("入库+原料", "MATERIAL_BATCH_CREATE");
        ACTION_INTENT_MAPPINGS.put("创建+发货", "SHIPMENT_CREATE");
        ACTION_INTENT_MAPPINGS.put("新建+发货", "SHIPMENT_CREATE");
        ACTION_INTENT_MAPPINGS.put("安排+发货", "SHIPMENT_CREATE");

        // 查询类动作（用于消歧）
        ACTION_INTENT_MAPPINGS.put("查询+原料", "MATERIAL_BATCH_QUERY");
        ACTION_INTENT_MAPPINGS.put("查看+原料", "MATERIAL_BATCH_QUERY");
        ACTION_INTENT_MAPPINGS.put("查询+批次", "PROCESSING_BATCH_LIST");
        ACTION_INTENT_MAPPINGS.put("查看+批次", "PROCESSING_BATCH_LIST");
        ACTION_INTENT_MAPPINGS.put("查询+发货", "SHIPMENT_QUERY");
        ACTION_INTENT_MAPPINGS.put("查看+发货", "SHIPMENT_QUERY");
        ACTION_INTENT_MAPPINGS.put("查询+告警", "ALERT_LIST");
        ACTION_INTENT_MAPPINGS.put("查看+告警", "ALERT_LIST");
        ACTION_INTENT_MAPPINGS.put("查询+设备", "EQUIPMENT_LIST");
        ACTION_INTENT_MAPPINGS.put("查看+设备", "EQUIPMENT_LIST");
        ACTION_INTENT_MAPPINGS.put("查询+客户", "CUSTOMER_SEARCH");
        ACTION_INTENT_MAPPINGS.put("查看+客户", "CUSTOMER_LIST");
        ACTION_INTENT_MAPPINGS.put("统计+质量", "QUALITY_STATS");
        ACTION_INTENT_MAPPINGS.put("统计+客户", "CUSTOMER_STATS");
        ACTION_INTENT_MAPPINGS.put("统计+生产", "REPORT_PRODUCTION");
        ACTION_INTENT_MAPPINGS.put("统计+库存", "REPORT_INVENTORY");

        // 追溯相关 - 专门用于溯源查询
        ACTION_INTENT_MAPPINGS.put("追溯+批次", "TRACE_BATCH");
        ACTION_INTENT_MAPPINGS.put("追溯+发货", "TRACE_BATCH");
        ACTION_INTENT_MAPPINGS.put("追溯+原料", "TRACE_MATERIAL");
        ACTION_INTENT_MAPPINGS.put("溯源+批次", "TRACE_BATCH");
        ACTION_INTENT_MAPPINGS.put("溯源+发货", "TRACE_BATCH");
        ACTION_INTENT_MAPPINGS.put("查询+溯源", "TRACE_BATCH");
        ACTION_INTENT_MAPPINGS.put("查看+溯源", "TRACE_BATCH");

        // ========== v7.2新增：更多动作消歧映射 ==========

        // 考勤相关
        ACTION_INTENT_MAPPINGS.put("查询+考勤", "ATTENDANCE_QUERY");
        ACTION_INTENT_MAPPINGS.put("查看+考勤", "ATTENDANCE_QUERY");
        ACTION_INTENT_MAPPINGS.put("统计+考勤", "ATTENDANCE_STATS");
        ACTION_INTENT_MAPPINGS.put("打卡+考勤", "ATTENDANCE_RECORD");
        ACTION_INTENT_MAPPINGS.put("记录+考勤", "ATTENDANCE_RECORD");

        // v7.5: 修复 "记录" 动词的映射
        ACTION_INTENT_MAPPINGS.put("记录+打卡", "ATTENDANCE_RECORD");
        ACTION_INTENT_MAPPINGS.put("记录+出勤", "ATTENDANCE_RECORD");

        // 供应商相关
        ACTION_INTENT_MAPPINGS.put("查询+供应商", "SUPPLIER_QUERY");
        ACTION_INTENT_MAPPINGS.put("查看+供应商", "SUPPLIER_QUERY");
        ACTION_INTENT_MAPPINGS.put("搜索+供应商", "SUPPLIER_SEARCH");
        ACTION_INTENT_MAPPINGS.put("找+供应商", "SUPPLIER_SEARCH");
        ACTION_INTENT_MAPPINGS.put("评价+供应商", "SUPPLIER_EVALUATE");
        ACTION_INTENT_MAPPINGS.put("评估+供应商", "SUPPLIER_EVALUATE");
        ACTION_INTENT_MAPPINGS.put("添加+供应商", "SUPPLIER_CREATE");
        ACTION_INTENT_MAPPINGS.put("新增+供应商", "SUPPLIER_CREATE");

        // 设备控制相关
        ACTION_INTENT_MAPPINGS.put("启动+设备", "EQUIPMENT_START");
        ACTION_INTENT_MAPPINGS.put("开启+设备", "EQUIPMENT_START");
        ACTION_INTENT_MAPPINGS.put("打开+设备", "EQUIPMENT_START");
        ACTION_INTENT_MAPPINGS.put("停止+设备", "EQUIPMENT_STOP");
        ACTION_INTENT_MAPPINGS.put("关闭+设备", "EQUIPMENT_STOP");
        ACTION_INTENT_MAPPINGS.put("关掉+设备", "EQUIPMENT_STOP");
        ACTION_INTENT_MAPPINGS.put("暂停+设备", "EQUIPMENT_STOP");
        ACTION_INTENT_MAPPINGS.put("维护+设备", "EQUIPMENT_MAINTENANCE");
        ACTION_INTENT_MAPPINGS.put("保养+设备", "EQUIPMENT_MAINTENANCE");
        ACTION_INTENT_MAPPINGS.put("维修+设备", "EQUIPMENT_MAINTENANCE");
        ACTION_INTENT_MAPPINGS.put("查询+机器", "EQUIPMENT_LIST");
        ACTION_INTENT_MAPPINGS.put("查看+机器", "EQUIPMENT_LIST");
        ACTION_INTENT_MAPPINGS.put("启动+机器", "EQUIPMENT_START");
        ACTION_INTENT_MAPPINGS.put("停止+机器", "EQUIPMENT_STOP");

        // 报表相关
        ACTION_INTENT_MAPPINGS.put("生成+报表", "REPORT_DASHBOARD_OVERVIEW");
        ACTION_INTENT_MAPPINGS.put("导出+报表", "REPORT_EXPORT");
        ACTION_INTENT_MAPPINGS.put("查看+报表", "REPORT_DASHBOARD_OVERVIEW");
        ACTION_INTENT_MAPPINGS.put("统计+报表", "REPORT_DASHBOARD_OVERVIEW");
        ACTION_INTENT_MAPPINGS.put("查询+报表", "REPORT_DASHBOARD_OVERVIEW");
        ACTION_INTENT_MAPPINGS.put("生成+数据", "REPORT_DASHBOARD_OVERVIEW");
        ACTION_INTENT_MAPPINGS.put("导出+数据", "REPORT_EXPORT");

        // 效率/产量相关
        ACTION_INTENT_MAPPINGS.put("统计+效率", "REPORT_EFFICIENCY");
        ACTION_INTENT_MAPPINGS.put("查看+效率", "REPORT_EFFICIENCY");
        ACTION_INTENT_MAPPINGS.put("统计+产量", "REPORT_PRODUCTION");
        ACTION_INTENT_MAPPINGS.put("查看+产量", "REPORT_PRODUCTION");
        ACTION_INTENT_MAPPINGS.put("查询+产量", "REPORT_PRODUCTION");

        // 告警/预警相关
        ACTION_INTENT_MAPPINGS.put("处理+预警", "ALERT_ACKNOWLEDGE");
        ACTION_INTENT_MAPPINGS.put("确认+告警", "ALERT_ACKNOWLEDGE");
        ACTION_INTENT_MAPPINGS.put("确认+预警", "ALERT_ACKNOWLEDGE");
        ACTION_INTENT_MAPPINGS.put("忽略+告警", "ALERT_DISMISS");
        ACTION_INTENT_MAPPINGS.put("忽略+预警", "ALERT_DISMISS");

        // 订单相关
        ACTION_INTENT_MAPPINGS.put("创建+订单", "ORDER_CREATE");
        ACTION_INTENT_MAPPINGS.put("新建+订单", "ORDER_CREATE");
        ACTION_INTENT_MAPPINGS.put("查询+订单", "ORDER_QUERY");
        ACTION_INTENT_MAPPINGS.put("查看+订单", "ORDER_QUERY");
        ACTION_INTENT_MAPPINGS.put("取消+订单", "ORDER_CANCEL");
        ACTION_INTENT_MAPPINGS.put("修改+订单", "ORDER_UPDATE");
        ACTION_INTENT_MAPPINGS.put("更新+订单", "ORDER_UPDATE");

        // 库存调整相关
        ACTION_INTENT_MAPPINGS.put("盘点+库存", "INVENTORY_CHECK");
        ACTION_INTENT_MAPPINGS.put("盘+库存", "INVENTORY_CHECK");
        ACTION_INTENT_MAPPINGS.put("查看+库存", "INVENTORY_QUERY");
        ACTION_INTENT_MAPPINGS.put("查询+库存", "INVENTORY_QUERY");
        ACTION_INTENT_MAPPINGS.put("补充+库存", "MATERIAL_BATCH_CREATE");
        ACTION_INTENT_MAPPINGS.put("调拨+库存", "MATERIAL_TRANSFER");
    }

    /**
     * 核心动词集合 (v7.4扩展)
     */
    private static final Set<String> CORE_VERBS = Set.of(
            // 查询类
            "查询", "查看", "查", "看", "显示", "统计", "分析", "计算", "获取", "导出",
            "搜索", "搜", "找", "查找", "检索", "筛选", "过滤", "核实", "核查",
            // 创建/添加类
            "添加", "新增", "创建", "新建", "录入", "登记", "注册", "记录",
            // 修改/更新类
            "修改", "更新", "编辑", "调整", "变更", "改", "设置", "配置",
            // 删除类
            "删除", "移除", "取消", "作废", "撤销",
            // 物流类
            "入库", "出库", "发货", "收货", "调拨", "转移", "配送",
            // 处理/执行类
            "处理", "提交", "执行", "确认", "审核", "审批", "批准", "消耗", "用掉",
            // 状态控制类 (v7.4: 增加单字动词)
            "开始", "启动", "停止", "暂停", "恢复", "完成", "结束", "终止",
            "关", "关掉", "关闭", "开", "开启", "停",
            // 其他
            "安排", "追溯", "溯源", "盘点", "盘", "打卡", "签到",
            "维护", "保养", "维修", "评价", "评估", "生成", "汇总", "检查"
    );

    /**
     * 核心名词集合 (v7.2扩展)
     */
    private static final Set<String> CORE_NOUNS = Set.of(
            // 物料相关
            "原料", "物料", "材料", "批次", "库存", "存货", "货物",
            // 物流相关
            "发货", "收货", "出货", "入库", "出库", "配送",
            // 告警相关
            "告警", "预警", "警报", "异常", "故障",
            // 设备相关
            "设备", "机器", "机台", "产线", "生产线",
            // 质量相关
            "质检", "检测", "检验", "质量", "品质",
            // 人员相关
            "客户", "供应商", "员工", "工人", "用户",
            // 业务对象
            "订单", "记录", "数据", "报表", "报告",
            // 生产相关
            "生产", "加工", "产量", "效率", "产能",
            // 通用
            "状态", "信息", "列表", "详情", "进度", "结果",
            // 考勤相关
            "考勤", "打卡", "签到", "出勤"
    );

    /**
     * 冗余前缀模式
     */
    private static final Pattern REDUNDANT_PREFIX_PATTERN = Pattern.compile(
            "^(我想|我要|请|请帮我|帮我|麻烦|能不能|可以|可不可以|能否|请问)+"
    );

    /**
     * 冗余后缀模式
     */
    private static final Pattern REDUNDANT_SUFFIX_PATTERN = Pattern.compile(
            "(一下|一下子|看看|看一看|试试|来着)?$"
    );

    @Override
    public String filterModalParticles(String input) {
        if (input == null || input.trim().isEmpty()) {
            return input;
        }

        String result = input.trim();

        // 移除句尾语气词组合（先处理多个连续的）
        result = MODAL_PARTICLE_COMBO_PATTERN.matcher(result).replaceAll("");

        // 移除单个句尾语气词
        result = MODAL_PARTICLE_PATTERN.matcher(result).replaceAll("");

        return result.trim();
    }

    @Override
    public CoreExtractionResult extractCore(String input) {
        if (input == null || input.trim().isEmpty()) {
            return CoreExtractionResult.builder()
                    .originalInput(input)
                    .coreQuery(input)
                    .extracted(false)
                    .modifiers(Collections.emptyList())
                    .build();
        }

        String processed = input.trim();
        List<String> modifiers = new ArrayList<>();

        // Step 1: 移除冗余前缀
        Matcher prefixMatcher = REDUNDANT_PREFIX_PATTERN.matcher(processed);
        if (prefixMatcher.find()) {
            modifiers.add("prefix:" + prefixMatcher.group());
            processed = prefixMatcher.replaceFirst("");
        }

        // Step 2: 移除冗余后缀
        processed = REDUNDANT_SUFFIX_PATTERN.matcher(processed).replaceAll("");

        // Step 3: 过滤语气词
        processed = filterModalParticles(processed);

        // Step 4: 提取核心动词和名词
        String verb = null;
        String object = null;

        // 查找动词
        for (String v : CORE_VERBS) {
            if (processed.contains(v)) {
                verb = v;
                break;
            }
        }

        // 查找名词（从后向前找，通常核心对象在句尾）
        for (String n : CORE_NOUNS) {
            if (processed.contains(n)) {
                object = n;
                // 不 break，继续找更靠后的名词
            }
        }

        // Step 5: 提取时间/条件修饰语
        Pattern timeModifierPattern = Pattern.compile("(今天|昨天|本周|本月|最近|上周|上月|这几天|那几天)");
        Matcher timeMatcher = timeModifierPattern.matcher(input);
        while (timeMatcher.find()) {
            modifiers.add("time:" + timeMatcher.group());
        }

        // Step 6: 构建核心查询
        String coreQuery = processed.trim();
        if (verb != null && object != null) {
            // v19: 检查动词前是否有否定词，如有则保留否定语义
            String negPrefix = "";
            int verbIdx = processed.indexOf(verb);
            if (verbIdx > 0) {
                String[] negations = {"未", "没有", "没", "不", "非", "无", "尚未", "还没", "还未"};
                for (String neg : negations) {
                    int negStart = verbIdx - neg.length();
                    if (negStart >= 0 && processed.substring(negStart, verbIdx).equals(neg)) {
                        negPrefix = neg;
                        break;
                    }
                }
            }
            coreQuery = negPrefix + verb + object;
        }

        boolean extracted = !coreQuery.equals(input.trim()) || verb != null || object != null;

        return CoreExtractionResult.builder()
                .originalInput(input)
                .coreQuery(coreQuery)
                .verb(verb)
                .object(object)
                .modifiers(modifiers)
                .extracted(extracted)
                .build();
    }

    @Override
    public RankingQueryResult detectRankingQuery(String input) {
        if (input == null || input.trim().isEmpty()) {
            return RankingQueryResult.builder()
                    .isRankingQuery(false)
                    .build();
        }

        String normalized = input.toLowerCase().trim();

        for (Map.Entry<String, String> entry : RANKING_KEYWORDS.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                // 尝试提取排序维度
                String dimension = extractRankingDimension(normalized, entry.getKey());

                return RankingQueryResult.builder()
                        .isRankingQuery(true)
                        .rankingType(entry.getValue())
                        .rankingKeyword(entry.getKey())
                        .rankingDimension(dimension)
                        .build();
            }
        }

        return RankingQueryResult.builder()
                .isRankingQuery(false)
                .build();
    }

    /**
     * 提取排序维度
     */
    private String extractRankingDimension(String input, String rankingKeyword) {
        int idx = input.indexOf(rankingKeyword);
        if (idx < 0) return null;

        // 取排序关键词后的文本作为维度
        String afterKeyword = input.substring(idx + rankingKeyword.length()).trim();

        // 常见维度关键词
        Map<String, String> dimensionKeywords = Map.of(
                "数量", "quantity",
                "金额", "amount",
                "重量", "weight",
                "产量", "output",
                "效率", "efficiency",
                "合格率", "pass_rate",
                "库存", "stock"
        );

        for (Map.Entry<String, String> dim : dimensionKeywords.entrySet()) {
            if (afterKeyword.contains(dim.getKey()) || input.contains(dim.getKey())) {
                return dim.getValue();
            }
        }

        return null;
    }

    // 时间表达词（用于检测查询上下文）
    private static final Set<String> TIME_QUERY_INDICATORS = Set.of(
            "今天", "昨天", "前天", "明天", "后天",
            "本周", "上周", "下周", "这周",
            "本月", "上月", "下月", "这个月", "上个月",
            "今年", "去年", "明年",
            "最近", "近期", "刚才", "刚刚",
            "上午", "下午", "早上", "晚上", "今早", "今晚"
    );

    // 过去时态/查询态词（带"了"、"过"、"的"等表示查询而非动作）
    private static final Pattern QUERY_CONTEXT_PATTERN = Pattern.compile(
            ".*(了多少|了几|过的|的记录|的情况|的状态|的批次|的原料|的发货).*"
    );

    @Override
    public ActionDisambiguationResult disambiguateAction(String input) {
        if (input == null || input.trim().isEmpty()) {
            return ActionDisambiguationResult.builder()
                    .disambiguated(false)
                    .confidence(0.0)
                    .build();
        }

        String normalized = input.toLowerCase().trim();

        // ========== 查询上下文检测 ==========
        // 如果包含时间词或过去时态，这很可能是查询而非执行动作
        boolean hasTimeContext = TIME_QUERY_INDICATORS.stream().anyMatch(normalized::contains);
        boolean hasQueryContext = QUERY_CONTEXT_PATTERN.matcher(normalized).matches();
        boolean isLikelyQuery = hasTimeContext || hasQueryContext;

        // 对于明确的查询上下文，跳过动作消歧，交给其他匹配层处理
        if (isLikelyQuery) {
            log.debug("检测到查询上下文，跳过动作消歧: input={}, hasTimeContext={}, hasQueryContext={}",
                    input, hasTimeContext, hasQueryContext);
            return ActionDisambiguationResult.builder()
                    .disambiguated(false)
                    .confidence(0.3)
                    .build();
        }

        // 提取动词
        String detectedVerb = null;
        for (String verb : CORE_VERBS) {
            if (normalized.contains(verb)) {
                detectedVerb = verb;
                break;
            }
        }

        // 提取名词
        String detectedObject = null;
        for (String noun : CORE_NOUNS) {
            if (normalized.contains(noun)) {
                detectedObject = noun;
            }
        }

        if (detectedVerb == null || detectedObject == null) {
            return ActionDisambiguationResult.builder()
                    .verb(detectedVerb)
                    .object(detectedObject)
                    .disambiguated(false)
                    .confidence(0.3)
                    .build();
        }

        // 查找动词+名词组合映射
        String actionKey = detectedVerb + "+" + detectedObject;
        String recommendedIntent = ACTION_INTENT_MAPPINGS.get(actionKey);

        if (recommendedIntent != null) {
            return ActionDisambiguationResult.builder()
                    .verb(detectedVerb)
                    .object(detectedObject)
                    .recommendedIntent(recommendedIntent)
                    .confidence(0.85)
                    .disambiguated(true)
                    .build();
        }

        // 没有精确匹配，尝试模糊匹配（只匹配名词部分）
        for (Map.Entry<String, String> entry : ACTION_INTENT_MAPPINGS.entrySet()) {
            String[] parts = entry.getKey().split("\\+");
            if (parts.length == 2 && parts[1].equals(detectedObject)) {
                // 检查动词是否相似
                if (isSimilarVerb(detectedVerb, parts[0])) {
                    return ActionDisambiguationResult.builder()
                            .verb(detectedVerb)
                            .object(detectedObject)
                            .recommendedIntent(entry.getValue())
                            .confidence(0.65)
                            .disambiguated(true)
                            .build();
                }
            }
        }

        return ActionDisambiguationResult.builder()
                .verb(detectedVerb)
                .object(detectedObject)
                .disambiguated(false)
                .confidence(0.4)
                .build();
    }

    /**
     * 检查两个动词是否相似
     */
    private boolean isSimilarVerb(String verb1, String verb2) {
        if (verb1 == null || verb2 == null) return false;
        if (verb1.equals(verb2)) return true;

        // 同义动词组
        Set<Set<String>> synonymGroups = Set.of(
                Set.of("查询", "查看", "查", "看", "显示", "获取"),
                Set.of("添加", "新增", "创建", "新建", "录入"),
                Set.of("修改", "更新", "编辑", "调整"),
                Set.of("删除", "移除", "取消", "作废"),
                Set.of("开始", "启动", "启用"),
                Set.of("停止", "暂停", "中止"),
                Set.of("完成", "结束", "完结")
        );

        for (Set<String> group : synonymGroups) {
            if (group.contains(verb1) && group.contains(verb2)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public EnhancedPreprocessResult enhancedPreprocess(String input) {
        long startTime = System.currentTimeMillis();

        if (input == null || input.trim().isEmpty()) {
            return EnhancedPreprocessResult.builder()
                    .originalInput(input)
                    .processedInput(input)
                    .modalParticlesFiltered(false)
                    .queryFeatures(Collections.emptySet())
                    .processingTimeMs(0)
                    .build();
        }

        Set<String> features = new HashSet<>();
        String processed = input;

        // Step 1: 过滤语气词
        String afterModalFilter = filterModalParticles(processed);
        boolean modalFiltered = !afterModalFilter.equals(processed);
        if (modalFiltered) {
            features.add("MODAL_PARTICLES_FILTERED");
            processed = afterModalFilter;
        }

        // Step 1.5: 方言/口语标准化（优先于 ColloquialMappings）
        if (dialectNormalizationService != null) {
            DialectNormalizationService.NormalizationResult dialectResult =
                    dialectNormalizationService.normalize(processed);
            if (dialectResult.isHasReplacements()) {
                features.add("DIALECT_NORMALIZED");
                processed = dialectResult.getNormalizedText();
                log.debug("方言标准化: {} 处替换", dialectResult.getReplacements().size());
            }
        }

        // Step 2: 口语标准化
        ColloquialMappings.StandardizationResult colloquialResult =
                colloquialMappings.findAndReplace(processed);
        if (colloquialResult.hasReplacements()) {
            features.add("COLLOQUIAL_STANDARDIZED");
            processed = colloquialResult.getProcessedText();
        }

        // Step 3: 核心提取
        CoreExtractionResult coreResult = extractCore(processed);
        if (coreResult.isExtracted()) {
            features.add("CORE_EXTRACTED");
            // 使用核心查询继续处理（但保留原处理文本用于后续步骤）
        }

        // Step 4: 排序检测
        RankingQueryResult rankingResult = detectRankingQuery(input);
        if (rankingResult.isRankingQuery()) {
            features.add("RANKING_QUERY_" + rankingResult.getRankingType());
        }

        // Step 5: 动作消歧
        ActionDisambiguationResult disambiguationResult = disambiguateAction(processed);
        if (disambiguationResult.isDisambiguated()) {
            features.add("ACTION_DISAMBIGUATED");
        }

        // Step 6 (v7.4): 否定语义检测
        NegationInfo negationInfo = detectNegationSemantics(input);
        if (negationInfo.hasNegation()) {
            features.add("HAS_NEGATION");
            features.add("NEGATION_WORD:" + negationInfo.getNegationWord());
            log.debug("检测到否定语义: {}", negationInfo);
        }

        // Step 7 (v8.0): 语用学处理 - 反问句、转折句、双重否定
        PragmaticProcessingResult pragmaticResult = performPragmaticProcessing(processed);
        if (pragmaticResult.isProcessed()) {
            features.add("PRAGMATIC_PROCESSED");
            features.add("PRAGMATIC_TYPE:" + pragmaticResult.getProcessingType().name());
            processed = pragmaticResult.getProcessedText();
            log.info("语用学处理完成: type={}, pattern={}, confidence={}",
                    pragmaticResult.getProcessingType(),
                    pragmaticResult.getDetectedPattern(),
                    pragmaticResult.getConfidence());
        }

        // 决定最终处理结果
        String finalProcessed = processed;
        if (pragmaticResult.isProcessed()) {
            // 如果进行了语用学处理，优先使用处理后的结果
            finalProcessed = pragmaticResult.getProcessedText();
        } else if (coreResult.isExtracted() && coreResult.getCoreQuery() != null
                && coreResult.getCoreQuery().length() >= 2) {
            // 如果核心提取有效且不太短，使用核心查询
            finalProcessed = coreResult.getCoreQuery();
        }

        // v7.5 / W1b: 将内部 NegationInfo 转换为接口 NegationInfo(带 kind)
        QueryPreprocessorService.NegationInfo interfaceNegationInfo = null;
        if (negationInfo.hasNegation()
                || negationInfo.getKind() != QueryPreprocessorService.NegationKind.NONE) {
            interfaceNegationInfo = QueryPreprocessorService.NegationInfo.builder()
                    .hasNegation(negationInfo.hasNegation())
                    .negationWord(negationInfo.getNegationWord())
                    .excludedContent(negationInfo.getExcludedContent())
                    .kind(negationInfo.getKind())
                    .build();
        }

        return EnhancedPreprocessResult.builder()
                .originalInput(input)
                .processedInput(finalProcessed)
                .modalParticlesFiltered(modalFiltered)
                .coreExtraction(coreResult)
                .rankingQuery(rankingResult)
                .actionDisambiguation(disambiguationResult)
                .negationInfo(interfaceNegationInfo)
                .pragmaticProcessing(pragmaticResult)
                .queryFeatures(features)
                .processingTimeMs(System.currentTimeMillis() - startTime)
                .build();
    }

    /**
     * 执行语用学处理 (v8.0)
     *
     * 按优先级依次检测：
     * 1. 反问句
     * 2. 转折句
     * 3. 双重否定
     *
     * 返回第一个匹配的处理结果
     */
    private PragmaticProcessingResult performPragmaticProcessing(String input) {
        if (input == null || input.trim().isEmpty()) {
            return PragmaticProcessingResult.builder()
                    .originalInput(input)
                    .processedText(input)
                    .processingType(PragmaticType.NONE)
                    .processed(false)
                    .confidence(0.0)
                    .build();
        }

        log.debug("开始语用学处理: input={}", truncate(input, 50));

        // 1. 检测反问句
        PragmaticProcessingResult rhetoricalResult = convertRhetoricalQuestion(input);
        if (rhetoricalResult.isProcessed()) {
            return rhetoricalResult;
        }

        // 2. 检测转折句
        PragmaticProcessingResult concessionResult = extractConcessionIntent(input);
        if (concessionResult.isProcessed()) {
            return concessionResult;
        }

        // 3. 检测双重否定
        PragmaticProcessingResult doubleNegResult = convertDoubleNegative(input);
        if (doubleNegResult.isProcessed()) {
            return doubleNegResult;
        }

        // 无匹配
        log.debug("未检测到需要语用学处理的模式");
        return PragmaticProcessingResult.builder()
                .originalInput(input)
                .processedText(input)
                .processingType(PragmaticType.NONE)
                .processed(false)
                .confidence(0.0)
                .build();
    }

    // ==================== 语用学处理方法实现 (v8.0) ====================

    @Override
    public PragmaticProcessingResult convertRhetoricalQuestion(String input) {
        if (input == null || input.trim().isEmpty()) {
            return PragmaticProcessingResult.builder()
                    .originalInput(input)
                    .processedText(input)
                    .processingType(PragmaticType.NONE)
                    .processed(false)
                    .confidence(0.0)
                    .build();
        }

        String text = input.trim();
        log.debug("反问句检测开始: input={}", truncate(text, 50));

        // 检测模式1: 难道...吗/呢
        Matcher nandaoMatcher = RHETORICAL_NANDAO_PATTERN.matcher(text);
        if (nandaoMatcher.find()) {
            String content = nandaoMatcher.group(1).trim();
            String converted = convertRhetoricalContent(content, "难道");
            log.info("反问句转换(难道): '{}' -> '{}'", text, converted);
            return PragmaticProcessingResult.builder()
                    .originalInput(input)
                    .processedText(converted)
                    .processingType(PragmaticType.RHETORICAL_QUESTION)
                    .processed(true)
                    .detectedPattern("难道...吗/呢")
                    .extractedIntent(converted)
                    .processingNote("反问句'难道'转换为查询意图")
                    .confidence(0.85)
                    .build();
        }

        // 检测模式2: 怎么...不
        Matcher zenmeMatcher = RHETORICAL_ZENME_PATTERN.matcher(text);
        if (zenmeMatcher.find()) {
            String subject = zenmeMatcher.group(1).trim();
            String action = zenmeMatcher.group(2).trim();
            String converted = "查询" + subject + action + "情况";
            log.info("反问句转换(怎么不): '{}' -> '{}'", text, converted);
            return PragmaticProcessingResult.builder()
                    .originalInput(input)
                    .processedText(converted)
                    .processingType(PragmaticType.RHETORICAL_QUESTION)
                    .processed(true)
                    .detectedPattern("怎么...不")
                    .extractedIntent(converted)
                    .processingNote("反问句'怎么不'表达期望行为，转换为查询")
                    .confidence(0.80)
                    .build();
        }

        // 检测模式3: 为什么...没
        Matcher weishenmeMatcher = RHETORICAL_WEISHENME_PATTERN.matcher(text);
        if (weishenmeMatcher.find()) {
            String subject = weishenmeMatcher.group(1).trim();
            String action = weishenmeMatcher.group(2).trim();
            String converted = "查询" + subject + action + "状态";
            log.info("反问句转换(为什么没): '{}' -> '{}'", text, converted);
            return PragmaticProcessingResult.builder()
                    .originalInput(input)
                    .processedText(converted)
                    .processingType(PragmaticType.RHETORICAL_QUESTION)
                    .processed(true)
                    .detectedPattern("为什么...没")
                    .extractedIntent(converted)
                    .processingNote("反问句'为什么没'表达疑问，转换为状态查询")
                    .confidence(0.80)
                    .build();
        }

        // 检测模式4: 不是...吗
        Matcher bushiMatcher = RHETORICAL_BUSHI_PATTERN.matcher(text);
        if (bushiMatcher.find()) {
            String content = bushiMatcher.group(1).trim();
            String converted = "确认" + content + "情况";
            log.info("反问句转换(不是吗): '{}' -> '{}'", text, converted);
            return PragmaticProcessingResult.builder()
                    .originalInput(input)
                    .processedText(converted)
                    .processingType(PragmaticType.RHETORICAL_QUESTION)
                    .processed(true)
                    .detectedPattern("不是...吗")
                    .extractedIntent(converted)
                    .processingNote("反问句'不是吗'表达确认需求")
                    .confidence(0.75)
                    .build();
        }

        log.debug("未检测到反问句模式: input={}", truncate(text, 50));
        return PragmaticProcessingResult.builder()
                .originalInput(input)
                .processedText(input)
                .processingType(PragmaticType.NONE)
                .processed(false)
                .confidence(0.0)
                .build();
    }

    /**
     * 将反问句内容转换为查询语句
     */
    private String convertRhetoricalContent(String content, String rhetoricalWord) {
        // 处理否定词转肯定
        content = content.replace("没有", "")
                        .replace("没", "")
                        .replace("不", "")
                        .trim();

        // 检测时间词
        boolean hasTimeWord = content.contains("今天") || content.contains("昨天") ||
                             content.contains("本周") || content.contains("本月");

        // 检测常见业务对象
        if (content.contains("原料") || content.contains("物料")) {
            return "查询" + (hasTimeWord ? content : content + "原料") + "到货情况";
        } else if (content.contains("发货") || content.contains("出货")) {
            return "查询" + content + "情况";
        } else if (content.contains("入库") || content.contains("库存")) {
            return "查询" + content + "情况";
        } else if (content.contains("质检") || content.contains("检测")) {
            return "查询" + content + "结果";
        } else if (content.contains("生产") || content.contains("批次")) {
            return "查询" + content + "进度";
        }

        return "查询" + content + "情况";
    }

    @Override
    public PragmaticProcessingResult extractConcessionIntent(String input) {
        if (input == null || input.trim().isEmpty()) {
            return PragmaticProcessingResult.builder()
                    .originalInput(input)
                    .processedText(input)
                    .processingType(PragmaticType.NONE)
                    .processed(false)
                    .confidence(0.0)
                    .build();
        }

        String text = input.trim();
        log.debug("转折句检测开始: input={}", truncate(text, 50));

        Matcher concessionMatcher = CONCESSION_PATTERN.matcher(text);
        if (concessionMatcher.find()) {
            String concessionWord = concessionMatcher.group(1);  // 虽然/尽管/虽说
            String concessionPart = concessionMatcher.group(2).trim();  // 让步部分
            String contrastWord = concessionMatcher.group(3);    // 但是/可是/然而/不过
            String mainIntent = concessionMatcher.group(4).trim();  // 真实意图部分

            // 清理主意图部分的标点符号
            mainIntent = mainIntent.replaceAll("[，。！？,.!?]+$", "").trim();

            // 生成查询意图
            String converted = generateConcessionQuery(mainIntent);

            log.info("转折句提取: '{}' -> '{}' (让步部分: '{}')", text, converted, concessionPart);
            return PragmaticProcessingResult.builder()
                    .originalInput(input)
                    .processedText(converted)
                    .processingType(PragmaticType.CONCESSION)
                    .processed(true)
                    .detectedPattern(concessionWord + "..." + contrastWord)
                    .extractedIntent(converted)
                    .processingNote(String.format("从转折句中提取真实意图，忽略让步部分'%s'", concessionPart))
                    .confidence(0.85)
                    .build();
        }

        log.debug("未检测到转折句模式: input={}", truncate(text, 50));
        return PragmaticProcessingResult.builder()
                .originalInput(input)
                .processedText(input)
                .processingType(PragmaticType.NONE)
                .processed(false)
                .confidence(0.0)
                .build();
    }

    /**
     * 根据转折后的主意图生成查询语句
     */
    private String generateConcessionQuery(String mainIntent) {
        // 检测关键业务词汇
        if (mainIntent.contains("不合格") || mainIntent.contains("合格率")) {
            if (mainIntent.contains("上升") || mainIntent.contains("升高") || mainIntent.contains("增加")) {
                return "查询不合格品率变化趋势";
            } else if (mainIntent.contains("下降") || mainIntent.contains("降低")) {
                return "查询合格率改善情况";
            }
            return "查询不合格品率数据";
        } else if (mainIntent.contains("效率") || mainIntent.contains("产能")) {
            return "查询生产效率变化";
        } else if (mainIntent.contains("库存") || mainIntent.contains("存货")) {
            return "查询库存变化情况";
        } else if (mainIntent.contains("延迟") || mainIntent.contains("延误") || mainIntent.contains("滞后")) {
            return "查询延迟原因";
        } else if (mainIntent.contains("设备") || mainIntent.contains("机器")) {
            return "查询设备运行状态";
        } else if (mainIntent.contains("成本") || mainIntent.contains("费用")) {
            return "查询成本变化";
        } else if (mainIntent.contains("质量") || mainIntent.contains("品质")) {
            return "查询质量问题";
        }

        // 默认处理：添加"查询"前缀
        if (!mainIntent.startsWith("查询") && !mainIntent.startsWith("查看")) {
            return "查询" + mainIntent;
        }
        return mainIntent;
    }

    @Override
    public PragmaticProcessingResult convertDoubleNegative(String input) {
        if (input == null || input.trim().isEmpty()) {
            return PragmaticProcessingResult.builder()
                    .originalInput(input)
                    .processedText(input)
                    .processingType(PragmaticType.NONE)
                    .processed(false)
                    .confidence(0.0)
                    .build();
        }

        String text = input.trim();
        log.debug("双重否定检测开始: input={}", truncate(text, 50));

        Matcher doubleNegMatcher = DOUBLE_NEGATIVE_PATTERN.matcher(text);
        if (doubleNegMatcher.find()) {
            String negPattern = doubleNegMatcher.group(1);  // 双重否定词
            String content = doubleNegMatcher.group(2).trim();  // 后续内容

            // 转换双重否定为肯定
            String positiveForm = convertToPositive(negPattern, content);

            log.info("双重否定转换: '{}' -> '{}' (模式: {})", text, positiveForm, negPattern);
            return PragmaticProcessingResult.builder()
                    .originalInput(input)
                    .processedText(positiveForm)
                    .processingType(PragmaticType.DOUBLE_NEGATIVE)
                    .processed(true)
                    .detectedPattern(negPattern)
                    .extractedIntent(positiveForm)
                    .processingNote(String.format("双重否定'%s'转换为肯定语义", negPattern))
                    .confidence(0.90)
                    .build();
        }

        log.debug("未检测到双重否定模式: input={}", truncate(text, 50));
        return PragmaticProcessingResult.builder()
                .originalInput(input)
                .processedText(input)
                .processingType(PragmaticType.NONE)
                .processed(false)
                .confidence(0.0)
                .build();
    }

    /**
     * 将双重否定转换为肯定形式
     */
    private String convertToPositive(String negPattern, String content) {
        switch (negPattern) {
            case "不是不":
                return "想" + content;
            case "并非不":
                return "确实" + content;
            case "没有不":
                return "都" + content;
            case "不能不":
                return "必须" + content;
            case "不会不":
                return "一定会" + content;
            case "不得不":
                return "必须" + content;
            case "无法不":
                return "必然" + content;
            case "难以不":
                return "很难不" + content;  // 保留部分否定语义
            default:
                return content;
        }
    }

    /**
     * 否定语义信息 v7.4 (内部使用)
     */
    public static class NegationInfo {
        private final boolean hasNegation;
        private final String negationWord;
        private final String excludedContent;
        private final QueryPreprocessorService.NegationKind kind;   // W1b

        /** 旧 3-arg ctor 保留,kind 默认 NONE(向后兼容 805/812/814 三处 call site) */
        public NegationInfo(boolean hasNegation, String negationWord, String excludedContent) {
            this(hasNegation, negationWord, excludedContent, QueryPreprocessorService.NegationKind.NONE);
        }

        /** W1b 4-arg ctor */
        public NegationInfo(boolean hasNegation, String negationWord, String excludedContent,
                            QueryPreprocessorService.NegationKind kind) {
            this.hasNegation = hasNegation;
            this.negationWord = negationWord;
            this.excludedContent = excludedContent;
            this.kind = kind == null ? QueryPreprocessorService.NegationKind.NONE : kind;
        }

        public boolean hasNegation() { return hasNegation; }
        public String getNegationWord() { return negationWord; }
        public String getExcludedContent() { return excludedContent; }
        public QueryPreprocessorService.NegationKind getKind() { return kind; }  // W1b

        @Override
        public String toString() {
            return hasNegation ?
                String.format("NegationInfo[word=%s, excluded=%s, kind=%s]", negationWord, excludedContent, kind) :
                "NegationInfo[none, kind=" + kind + "]";
        }
    }
}
