package com.cretas.aims.service.impl;

import com.cretas.aims.ai.tool.BusinessTypeScope;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.entity.learning.TrainingSample;
import com.cretas.aims.repository.learning.LearnedExpressionRepository;
import com.cretas.aims.repository.learning.TrainingSampleRepository;
import com.cretas.aims.service.EmbeddingClient;
import com.cretas.aims.service.ExpressionLearningService;
import com.cretas.aims.service.IntentEmbeddingCacheService;
import com.cretas.aims.service.RequestScopedEmbeddingCache;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 表达学习服务实现
 *
 * 实现完整表达的学习、匹配和管理功能。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpressionLearningServiceImpl implements ExpressionLearningService {

    private final LearnedExpressionRepository expressionRepository;
    private final TrainingSampleRepository sampleRepository;
    private final EmbeddingClient embeddingClient;
    private final RequestScopedEmbeddingCache requestScopedCache;
    private final IntentEmbeddingCacheService embeddingCacheService;

    // 自引用代理 - 用于解决 Spring @Transactional 自调用不走代理的问题
    @Autowired
    @Lazy
    private ExpressionLearningService self;

    /**
     * #553 守卫: 意图配置管理服务, 用于 promote 阶段的业态/tool_name 校验。
     * @Lazy 避免循环依赖 (IntentConfigManagementService → 无 ExpressionLearning 依赖, 但 @Lazy 作保险)。
     * 可为 null (测试中 mock 可选注入, 或未配 Spring 上下文时); null 时 promote 守卫保守**不 promote**。
     */
    @Autowired(required = false)
    @Lazy
    private IntentConfigManagementService intentConfigService;

    // ========== 飞轮 v2 常量 ==========

    /**
     * 分层阈值: confidence >= HIGH_CONF_THRESHOLD → 新行直接 active=true。
     * 低于此值 (mid-conf zone 0.70-0.89) → staged (is_active=false)，等重提议激活。
     */
    static final double HIGH_CONF_THRESHOLD = 0.90;

    /**
     * 重提议 promote 阈值。
     * dedup 命中同一 staged/dormant 行 {@value} 次后（含首次写入），自动激活。
     */
    static final int PROMOTE_THRESHOLD = 3;

    // ========== 表达学习 ==========

    @Override
    @Transactional
    public LearnedExpression learnExpression(String factoryId, String intentCode,
                                              String expression, double confidence,
                                              LearnedExpression.SourceType sourceType) {
        if (expression == null || expression.trim().isEmpty()) {
            log.warn("尝试学习空表达，已忽略");
            return null;
        }

        String normalized = expression.toLowerCase().trim();
        String hash = LearnedExpression.computeHash(normalized);

        // 飞轮 v2: dedup 命中时，不只 skip — 追踪重提议并尝试 promote
        Optional<LearnedExpression> existing = expressionRepository.findOneByHashAndFactory(hash, factoryId);
        if (existing.isPresent()) {
            LearnedExpression row = existing.get();
            // 已经 active 的行无需处理
            if (Boolean.TRUE.equals(row.getIsActive())) {
                log.debug("表达已存在且活跃，跳过: factory={}, intent={}, expr={}",
                        factoryId, intentCode, truncate(normalized, 50));
                return null;
            }
            // staged (false) 或 dormant (null): 追踪重提议
            return incrementProposalAndMaybePromote(row, intentCode, confidence, factoryId);
        }

        // 飞轮 v2 分层写入: confidence >= 0.90 → active; 0.70-0.89 → staged
        boolean shouldBeActive = confidence >= HIGH_CONF_THRESHOLD;

        LearnedExpression entity = LearnedExpression.builder()
                .factoryId(factoryId)
                .intentCode(intentCode)
                .expression(normalized)
                .expressionHash(hash)
                .sourceType(sourceType)
                .confidence(java.math.BigDecimal.valueOf(confidence))
                .hitCount(0)
                .isVerified(false)
                .isActive(shouldBeActive)
                .proposalCount(1)
                .lastProposedAt(LocalDateTime.now())
                .build();

        // 生成 embedding (用于 Layer 4 统一语义搜索, 使用请求级缓存)
        if (embeddingClient != null && embeddingClient.isAvailable()) {
            try {
                float[] embedding = requestScopedCache.getOrCompute(normalized);
                entity.setEmbeddingFromFloatArray(embedding);
                entity.setEmbeddingModel(embeddingClient.getModelName());
                entity.setEmbeddingCreatedAt(LocalDateTime.now());
                log.debug("表达 embedding 生成成功: expr={}", truncate(normalized, 30));
            } catch (Exception e) {
                log.warn("生成表达 embedding 失败: {}", e.getMessage());
            }
        }

        LearnedExpression saved = expressionRepository.save(entity);
        log.info("学习新表达: factory={}, intent={}, tier={}, source={}, hasEmbedding={}, expr={}",
                factoryId, intentCode, shouldBeActive ? "active" : "staged",
                sourceType, saved.hasEmbedding(), truncate(normalized, 50));

        // 更新内存缓存 (异步添加到 Layer 4 搜索 — 仅 active 行)
        if (shouldBeActive && saved.hasEmbedding() && embeddingCacheService != null) {
            try {
                embeddingCacheService.cacheExpression(saved);
            } catch (Exception e) {
                log.warn("缓存表达 embedding 失败: {}", e.getMessage());
            }
        }

        return saved;
    }

    /**
     * 飞轮 v2 重提议 promote 逻辑。
     *
     * <p>当 dedup 命中一个 staged (is_active=false) 或 dormant (is_active=null) 行时：
     * <ol>
     *   <li>递增 proposal_count，更新 last_proposed_at。</li>
     *   <li>若 proposal_count >= {@link #PROMOTE_THRESHOLD} AND confidence >= 0.70 → promote (is_active=true)。</li>
     *   <li>返回更新后的行（activate 时返回含 is_active=true；否则返回仍 staged 的行）。</li>
     * </ol>
     *
     * <p>含义：LLM 跨 session 反复提议同一映射 → 一致同意 → 激活；仅提议一次的映射保持 dormant 安全。
     *
     * @param existing   已存在的 staged/dormant 行
     * @param intentCode 本次提议的意图（用于日志；行的 intentCode 不变以保持幂等）
     * @param confidence 本次提议的置信度
     * @param factoryId  工厂 ID（用于日志）
     * @return 更新后的实体（已 save）
     */
    @Transactional
    LearnedExpression incrementProposalAndMaybePromote(
            LearnedExpression existing,
            String intentCode,
            double confidence,
            String factoryId) {
        int newCount = (existing.getProposalCount() == null ? 1 : existing.getProposalCount()) + 1;
        existing.setProposalCount(newCount);
        existing.setLastProposedAt(LocalDateTime.now());

        boolean canPromote = newCount >= PROMOTE_THRESHOLD && confidence >= 0.70
                && isPromoteAllowedByGuard(existing.getIntentCode(), factoryId);
        if (canPromote) {
            existing.setIsActive(true);
            log.info("飞轮 v2 promote: factory={}, intent={}, expr={}, proposalCount={}, confidence={}",
                    factoryId, existing.getIntentCode(),
                    truncate(existing.getExpression(), 50), newCount, confidence);
            // 更新内存 embedding 缓存
            if (existing.hasEmbedding() && embeddingCacheService != null) {
                try {
                    embeddingCacheService.cacheExpression(existing);
                } catch (Exception e) {
                    log.warn("promote 后缓存 embedding 失败: {}", e.getMessage());
                }
            }
        } else {
            log.debug("飞轮 v2 重提议 (未达阈值): factory={}, intent={}, proposalCount={}/{}, expr={}",
                    factoryId, intentCode, newCount, PROMOTE_THRESHOLD,
                    truncate(existing.getExpression(), 50));
        }

        return expressionRepository.save(existing);
    }

    /**
     * #553 promote 守卫 (防御纵深) — 镜像 IntentRecognitionPipelineServiceImpl.shouldLearnExpression。
     *
     * <p>两道守卫:
     * <ul>
     *   <li><b>Guard B</b>: tool_name 非空 — 无 executor 的意图 promote = 激活死路由毒。</li>
     *   <li><b>Guard C</b>: 业态兼容 (BusinessTypeScope.isCompatible) — 跨域毒(如餐饮意图
     *       被工厂租户重提议)不得 promote。</li>
     * </ul>
     *
     * <p>Fail-closed 策略:
     * <ul>
     *   <li>intentConfigService 未注入(null) → 无法判断守卫 → 保守 <b>拒绝</b> promote。</li>
     *   <li>cfg 未找到 → 无法判断 → 保守 <b>拒绝</b> promote。</li>
     *   <li>业态解析异常 → 无法判断兼容性 → 保守 <b>拒绝</b> promote。</li>
     * </ul>
     *
     * <p>正常路径(cfg 已找到 + tool_name 非空 + 业态兼容) → 放行 true。
     *
     * @param intentCode 被 promote 行的意图 code
     * @param factoryId  工厂 ID
     * @return true = 允许 promote; false = 守卫拒绝, 保持 staged/dormant
     */
    boolean isPromoteAllowedByGuard(String intentCode, String factoryId) {
        if (intentConfigService == null) {
            // 未注入 (纯单元测试 without Spring / 意外未配置): fail-closed
            log.warn("promote 守卫: intentConfigService 未注入, 保守拒绝 promote (intent={}, factory={})",
                    intentCode, factoryId);
            return false;
        }
        try {
            AIIntentConfig cfg = intentConfigService.getIntentConfigByCode(factoryId, intentCode);
            if (cfg == null) {
                log.warn("promote 守卫(Guard B): 意图配置未找到, 保守拒绝 promote (intent={}, factory={})",
                        intentCode, factoryId);
                return false;
            }
            // Guard B: tool_name 必须非空
            String toolName = cfg.getToolName();
            if (toolName == null || toolName.isBlank()) {
                log.warn("promote 守卫(Guard B — tool_name=NULL): 拒绝 promote 遗留中毒行 (intent={}, factory={})",
                        intentCode, factoryId);
                return false;
            }
            // Guard C: 业态兼容
            String biz = intentConfigService.resolveBusinessDomain(factoryId);
            if (!BusinessTypeScope.isCompatible(cfg.getBusinessType(), biz)) {
                log.warn("promote 守卫(Guard C — 业态不兼容): 拒绝 promote 跨域毒行 " +
                                "(intent={}, business_type={}, factory_biz={}, factory={})",
                        intentCode, cfg.getBusinessType(), biz, factoryId);
                return false;
            }
            return true;
        } catch (Exception e) {
            // 任何异常: fail-closed, 不激活毒
            log.warn("promote 守卫查询异常, 保守拒绝 promote (intent={}, factory={}, error={})",
                    intentCode, factoryId, e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional
    public int learnExpressions(String factoryId, String intentCode,
                                 List<String> expressions, double confidence,
                                 LearnedExpression.SourceType sourceType) {
        int count = 0;
        for (String expr : expressions) {
            if (learnExpression(factoryId, intentCode, expr, confidence, sourceType) != null) {
                count++;
            }
        }
        return count;
    }

    // ========== 表达匹配 ==========

    /**
     * 95% 相似度阈值 - 用于模糊精确匹配
     */
    private static final double FUZZY_EXACT_THRESHOLD = 0.95;

    @Override
    @Transactional(readOnly = true)
    public Optional<ExpressionMatchResult> matchExactExpression(String factoryId, String input) {
        if (input == null || input.trim().isEmpty()) {
            return Optional.empty();
        }

        String normalized = input.toLowerCase().trim();
        String hash = LearnedExpression.computeHash(normalized);

        // Step 1: 尝试 SHA256 哈希精确匹配 (O(1))
        List<LearnedExpression> matches = expressionRepository.findByExpressionHash(hash, factoryId);
        if (!matches.isEmpty()) {
            // 优先返回工厂特定的匹配，其次是全局匹配
            LearnedExpression best = matches.get(0);

            // 记录命中（异步更新）
            recordHitAsync(best.getId());

            ExpressionMatchResult result = new ExpressionMatchResult(
                    best.getId(),
                    best.getIntentCode(),
                    best.getExpression(),
                    1.0, // 精确匹配置信度为1
                    "EXACT"
            );

            log.debug("精确表达匹配成功: input={}, intent={}", truncate(normalized, 50), best.getIntentCode());
            return Optional.of(result);
        }

        // Step 2: 哈希不匹配时，尝试 95% 相似度模糊匹配
        return matchFuzzyExact(factoryId, normalized);
    }

    /**
     * 95% 相似度模糊匹配
     * 当哈希精确匹配失败时，使用 Levenshtein 编辑距离计算相似度
     * 相似度公式: 1 - (编辑距离 / max(len1, len2))
     * 阈值: 95%
     *
     * @param factoryId 工厂ID
     * @param normalizedInput 已标准化的输入
     * @return 匹配结果
     */
    private Optional<ExpressionMatchResult> matchFuzzyExact(String factoryId, String normalizedInput) {
        // 获取工厂的所有活跃表达
        List<LearnedExpression> allExpressions = expressionRepository.findActiveByFactory(factoryId);

        if (allExpressions.isEmpty()) {
            return Optional.empty();
        }

        LearnedExpression bestMatch = null;
        double bestSimilarity = 0.0;

        for (LearnedExpression expr : allExpressions) {
            String candidateExpr = expr.getExpression();
            if (candidateExpr == null || candidateExpr.isEmpty()) {
                continue;
            }

            double similarity = calculateSimilarity(normalizedInput, candidateExpr.toLowerCase().trim());

            if (similarity >= FUZZY_EXACT_THRESHOLD && similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestMatch = expr;
            }
        }

        if (bestMatch != null) {
            // 记录命中（异步更新）
            recordHitAsync(bestMatch.getId());

            ExpressionMatchResult result = new ExpressionMatchResult(
                    bestMatch.getId(),
                    bestMatch.getIntentCode(),
                    bestMatch.getExpression(),
                    bestSimilarity, // 相似度作为置信度
                    "FUZZY_EXACT"   // 标记为模糊精确匹配
            );

            log.info("模糊精确匹配成功: input={}, matched={}, similarity={}%, intent={}",
                    truncate(normalizedInput, 50),
                    truncate(bestMatch.getExpression(), 50),
                    String.format("%.2f", bestSimilarity * 100),
                    bestMatch.getIntentCode());

            return Optional.of(result);
        }

        return Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpressionMatchResult> matchSimilarExpressions(String factoryId, String input,
                                                                int maxResults, double minScore) {
        if (input == null || input.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String normalized = input.toLowerCase().trim();

        // 获取候选表达 (至少被命中过一次的表达)
        List<LearnedExpression> candidates = expressionRepository
                .findCandidatesForSimilarMatch(factoryId, 0);

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 计算编辑距离相似度
        List<ExpressionMatchResult> results = candidates.stream()
                .map(expr -> {
                    double similarity = calculateSimilarity(normalized, expr.getExpression());
                    return new ExpressionMatchResult(
                            expr.getId(),
                            expr.getIntentCode(),
                            expr.getExpression(),
                            similarity,
                            "SIMILAR"
                    );
                })
                .filter(r -> r.getScore() >= minScore)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(maxResults)
                .collect(Collectors.toList());

        if (!results.isEmpty()) {
            log.debug("相似表达匹配: input={}, matches={}",
                    truncate(normalized, 50), results.size());
        }

        return results;
    }

    /**
     * 计算两个字符串的相似度 (基于编辑距离)
     */
    private double calculateSimilarity(String s1, String s2) {
        int distance = levenshteinDistance(s1, s2);
        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - (double) distance / maxLen;
    }

    /**
     * 计算 Levenshtein 编辑距离
     */
    private int levenshteinDistance(String s1, String s2) {
        int m = s1.length();
        int n = s2.length();

        int[][] dp = new int[m + 1][n + 1];

        for (int i = 0; i <= m; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= n; j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j - 1],
                            Math.min(dp[i - 1][j], dp[i][j - 1]));
                }
            }
        }

        return dp[m][n];
    }

    // ========== 反馈与验证 ==========

    @Override
    @Transactional
    public boolean verifyExpression(String expressionId, boolean isCorrect) {
        if (isCorrect) {
            int updated = expressionRepository.markAsVerified(expressionId, LocalDateTime.now());
            log.info("表达已验证: id={}", expressionId);
            return updated > 0;
        } else {
            return deactivateExpression(expressionId);
        }
    }

    @Override
    @Transactional
    public boolean deactivateExpression(String expressionId) {
        int updated = expressionRepository.deactivate(expressionId, LocalDateTime.now());
        if (updated > 0) {
            log.info("表达已禁用: id={}", expressionId);
        }
        return updated > 0;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordHit(String expressionId) {
        expressionRepository.incrementHitCount(expressionId, LocalDateTime.now());
    }

    /**
     * 异步记录命中（避免阻塞匹配流程）
     * 注意：使用 self 代理调用以确保 @Transactional(REQUIRES_NEW) 生效
     */
    private void recordHitAsync(String expressionId) {
        // 使用自引用代理调用，确保事务注解生效（解决 Spring 自调用问题）
        try {
            self.recordHit(expressionId);
        } catch (Exception e) {
            log.warn("记录表达命中失败: id={}, error={}", expressionId, e.getMessage());
        }
    }

    // ========== 训练样本收集 ==========

    @Override
    @Transactional
    public TrainingSample recordSample(String factoryId, String userInput,
                                        String intentCode, TrainingSample.MatchMethod method,
                                        double confidence, String sessionId) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return null;
        }

        TrainingSample sample = TrainingSample.create(
                factoryId,
                userInput.trim(),
                intentCode,
                method,
                confidence,
                sessionId
        );

        TrainingSample saved = sampleRepository.save(sample);
        log.debug("记录训练样本: factory={}, intent={}, method={}",
                factoryId, intentCode, method);

        return saved;
    }

    @Override
    @Transactional
    public boolean recordFeedback(Long sampleId, boolean isCorrect, String correctIntentCode) {
        int updated = sampleRepository.updateFeedback(
                sampleId,
                isCorrect,
                isCorrect ? null : correctIntentCode,
                LocalDateTime.now()
        );

        if (updated > 0) {
            log.info("记录样本反馈: sampleId={}, correct={}, correctIntent={}",
                    sampleId, isCorrect, correctIntentCode);

            // 获取样本数据用于学习
            Optional<TrainingSample> sampleOpt = sampleRepository.findById(sampleId);
            if (sampleOpt.isPresent()) {
                TrainingSample sample = sampleOpt.get();
                String factoryId = sample.getFactoryId();
                String userInput = sample.getUserInput();

                if (isCorrect) {
                    // 正确反馈: 强化学习该表达 -> 意图映射
                    String intentCode = sample.getMatchedIntentCode();
                    if (intentCode != null && userInput != null && !userInput.isEmpty()) {
                        try {
                            LearnedExpression learned = self.learnExpression(
                                    factoryId,
                                    intentCode,
                                    userInput,
                                    1.0, // 用户确认，置信度为1
                                    LearnedExpression.SourceType.USER_FEEDBACK
                            );
                            if (learned != null) {
                                log.info("正向反馈触发表达学习: sampleId={}, intent={}, expr={}",
                                        sampleId, intentCode, truncate(userInput, 50));
                            }
                        } catch (Exception e) {
                            log.warn("正向反馈学习表达失败: sampleId={}, error={}", sampleId, e.getMessage());
                        }
                    }
                } else {
                    // 错误反馈: 学习新关键词模式到正确意图
                    if (correctIntentCode != null && userInput != null && !userInput.isEmpty()) {
                        try {
                            // 将用户输入作为新表达学习到正确意图
                            LearnedExpression learned = self.learnExpression(
                                    factoryId,
                                    correctIntentCode,
                                    userInput,
                                    0.9, // 错误纠正，置信度略低
                                    LearnedExpression.SourceType.USER_FEEDBACK
                            );
                            if (learned != null) {
                                log.info("负向反馈触发表达学习: sampleId={}, wrongIntent={}, correctIntent={}, expr={}",
                                        sampleId, sample.getMatchedIntentCode(), correctIntentCode,
                                        truncate(userInput, 50));
                            }
                        } catch (Exception e) {
                            log.warn("负向反馈学习表达失败: sampleId={}, error={}", sampleId, e.getMessage());
                        }
                    }
                }
            }
        }

        return updated > 0;
    }

    @Override
    @Transactional
    public void recordFeedback(String factoryId, String userInput, String matchedIntentCode,
                               String correctIntentCode, Boolean isCorrect, String sessionId) {
        if (userInput == null || userInput.trim().isEmpty()) {
            log.warn("recordFeedback: userInput为空，已忽略");
            return;
        }

        try {
            // 先记录为训练样本
            TrainingSample.MatchMethod matchMethod = matchedIntentCode != null ?
                    TrainingSample.MatchMethod.USER_FEEDBACK : TrainingSample.MatchMethod.UNKNOWN;

            TrainingSample sample = recordSample(
                    factoryId,
                    userInput,
                    matchedIntentCode != null ? matchedIntentCode : "",
                    matchMethod,
                    isCorrect != null && isCorrect ? 1.0 : 0.0,
                    sessionId
            );

            // 如果有样本ID且用户提供了反馈，更新反馈信息
            if (sample != null && sample.getId() != null) {
                recordFeedback(sample.getId(), Boolean.TRUE.equals(isCorrect), correctIntentCode);
            }

            log.info("记录意图反馈样本: factory={}, matched={}, correct={}, isCorrect={}, session={}",
                    factoryId, matchedIntentCode, correctIntentCode, isCorrect, sessionId);

        } catch (Exception e) {
            log.error("记录意图反馈失败: factory={}, error={}", factoryId, e.getMessage(), e);
        }
    }

    // ========== 清理与维护 ==========

    @Override
    @Transactional
    public int cleanupIneffectiveExpressions(String factoryId, int minHits, int daysThreshold) {
        LocalDateTime beforeDate = LocalDateTime.now().minusDays(daysThreshold);

        int cleaned = expressionRepository.deactivateIneffective(
                factoryId,
                minHits,
                beforeDate,
                LocalDateTime.now()
        );

        if (cleaned > 0) {
            log.info("清理低效表达: factory={}, minHits={}, days={}, cleaned={}",
                    factoryId, minHits, daysThreshold, cleaned);
        }

        return cleaned;
    }

    @Override
    @Transactional
    public int cleanupExpiredSamples(int daysThreshold) {
        LocalDateTime before = LocalDateTime.now().minusDays(daysThreshold);
        int deleted = sampleRepository.deleteExpiredUnfeedback(before);

        if (deleted > 0) {
            log.info("清理过期样本: days={}, deleted={}", daysThreshold, deleted);
        }

        return deleted;
    }

    // ========== 统计 ==========

    @Override
    @Transactional(readOnly = true)
    public LearningStatistics getStatistics(String factoryId, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);

        LearningStatistics stats = new LearningStatistics();

        // 表达统计
        List<LearnedExpression> expressions = expressionRepository.findActiveByFactory(factoryId);
        stats.setTotalExpressions(expressions.size());
        stats.setActiveExpressions(expressions.stream().filter(LearnedExpression::getIsActive).count());
        stats.setVerifiedExpressions(expressions.stream().filter(LearnedExpression::getIsVerified).count());

        // 按来源统计
        Map<String, Long> bySource = expressionRepository.countBySourceType(factoryId).stream()
                .collect(Collectors.toMap(
                        arr -> String.valueOf(arr[0]),
                        arr -> ((Number) arr[1]).longValue()
                ));
        stats.setExpressionsBySource(bySource);

        // 样本统计
        stats.setTotalSamples(sampleRepository.countByFactory(factoryId));

        // 按匹配方法统计
        Map<String, Long> byMethod = sampleRepository.countByMatchMethod(factoryId, since).stream()
                .collect(Collectors.toMap(
                        arr -> String.valueOf(arr[0]),
                        arr -> ((Number) arr[1]).longValue()
                ));
        stats.setSamplesByMethod(byMethod);

        // 反馈统计
        Map<Boolean, Long> feedbackMap = sampleRepository.countByFeedback(factoryId, since).stream()
                .collect(Collectors.toMap(
                        arr -> (Boolean) arr[0],
                        arr -> ((Number) arr[1]).longValue()
                ));
        long positive = feedbackMap.getOrDefault(true, 0L);
        long negative = feedbackMap.getOrDefault(false, 0L);
        stats.setPositiveCount(positive);
        stats.setNegativeCount(negative);
        stats.setFeedbackCount(positive + negative);

        // 准确率
        if (positive + negative > 0) {
            stats.setAccuracyRate((double) positive / (positive + negative));
        }

        // LLM Fallback 率
        List<Object[]> fallbackData = sampleRepository.getLlmFallbackRate(factoryId, since);
        if (!fallbackData.isEmpty() && fallbackData.get(0) != null) {
            Object[] row = fallbackData.get(0);
            long llmCount = ((Number) row[0]).longValue();
            long totalCount = ((Number) row[1]).longValue();
            if (totalCount > 0) {
                stats.setLlmFallbackRate((double) llmCount / totalCount);
            }
        }

        return stats;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LearnedExpression> getExpressionsByIntent(String factoryId, String intentCode) {
        return expressionRepository.findByIntentCode(intentCode, factoryId);
    }

    // ========== 工具方法 ==========

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
