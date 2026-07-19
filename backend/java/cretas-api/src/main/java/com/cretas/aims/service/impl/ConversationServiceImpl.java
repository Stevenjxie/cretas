package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.conversation.ConversationSession.CandidateIntent;
import com.cretas.aims.entity.conversation.ConversationSession.SessionStatus;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.ExpressionLearningService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 多轮对话服务实现
 *
 * 实现 Layer 5 的多轮对话流程:
 * 1. 当 Layer 1-4 置信度 < 30% 时触发
 * 2. 最多进行 5 轮对话
 * 3. 每轮生成澄清问题或确认结果
 * 4. 成功后学习表达和关键词
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
@Slf4j
@Service
public class ConversationServiceImpl implements ConversationService {

    private final ConversationSessionRepository sessionRepository;
    private final AIIntentConfigRepository intentConfigRepository;
    private final ExpressionLearningService learningService;
    private final DashScopeClient dashScopeClient;
    private final ObjectMapper objectMapper;

    @Value("${cretas.ai.conversation.max-rounds:5}")
    private int maxRounds;

    @Value("${cretas.ai.conversation.timeout-minutes:10}")
    private int timeoutMinutes;

    @Value("${cretas.ai.conversation.confidence-threshold:0.7}")
    private double confidenceThreshold;

    @Autowired
    public ConversationServiceImpl(
            ConversationSessionRepository sessionRepository,
            AIIntentConfigRepository intentConfigRepository,
            @Autowired(required = false) ExpressionLearningService learningService,
            @Autowired(required = false) DashScopeClient dashScopeClient) {
        this.sessionRepository = sessionRepository;
        this.intentConfigRepository = intentConfigRepository;
        this.learningService = learningService;
        this.dashScopeClient = dashScopeClient;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    @Transactional
    public ConversationResponse startConversation(String factoryId, Long userId, String userInput) {
        log.info("开始多轮对话: factory={}, user={}, input={}",
                factoryId, userId, truncate(userInput, 50));

        // 取消之前的活跃会话
        sessionRepository.cancelActiveSessionsForUser(factoryId, userId, LocalDateTime.now());

        // 创建新会话
        ConversationSession session = ConversationSession.create(factoryId, userId, userInput);
        session.setMaxRounds(maxRounds);
        session.setTimeoutMinutes(timeoutMinutes);

        // 获取可用意图列表
        List<AIIntentConfig> availableIntents = getAvailableIntents(factoryId);

        // 调用 LLM 生成初始响应
        LlmConversationResult llmResult = callLlmForConversation(session, userInput, availableIntents);

        // 更新会话
        session.addAssistantMessage(llmResult.getMessage());
        session.setCandidates(llmResult.getCandidates());
        session.setLastConfidence(llmResult.getConfidence());

        // 检查是否已经可以确定意图
        if (llmResult.isIntentConfirmed() && llmResult.getConfidence() >= confidenceThreshold) {
            session.complete(llmResult.getIntentCode(), llmResult.getConfidence());
            log.info("首轮对话即确定意图: session={}, intent={}, confidence={}",
                    session.getSessionId(), llmResult.getIntentCode(), llmResult.getConfidence());
        }

        sessionRepository.save(session);

        return buildResponse(session, llmResult);
    }

    @Override
    @Transactional
    public ConversationResponse startParameterCollection(
            String factoryId,
            Long userId,
            String intentCode,
            String intentName,
            List<RequiredParameter> requiredParameters,
            List<String> clarificationQuestions) {

        log.info("开始参数收集对话: factory={}, user={}, intent={}, params={}",
                factoryId, userId, intentCode, requiredParameters.size());

        // 取消之前的活跃会话
        sessionRepository.cancelActiveSessionsForUser(factoryId, userId, LocalDateTime.now());

        // 构建初始收集问题
        String initialQuestion = buildParameterCollectionMessage(
                intentName, requiredParameters, clarificationQuestions);

        // 转换参数类型
        List<ConversationSession.RequiredParameter> sessionParams = requiredParameters.stream()
                .map(p -> ConversationSession.RequiredParameter.builder()
                        .name(p.getName())
                        .label(p.getLabel())
                        .type(p.getType())
                        .validationHint(p.getValidationHint())
                        .collected(false)
                        .build())
                .collect(Collectors.toList());

        // 创建参数收集会话
        ConversationSession session = ConversationSession.createForParameterCollection(
                factoryId, userId, intentCode, sessionParams, initialQuestion);
        session.setMaxRounds(maxRounds);
        session.setTimeoutMinutes(timeoutMinutes);

        sessionRepository.save(session);

        log.info("参数收集会话已创建: sessionId={}, intentCode={}", session.getSessionId(), intentCode);

        return ConversationResponse.builder()
                .sessionId(session.getSessionId())
                .currentRound(session.getCurrentRound())
                .maxRounds(session.getMaxRounds())
                .status(session.getStatus())
                .completed(false)
                .message(initialQuestion)
                .intentCode(intentCode)
                .intentName(intentName)
                .requiresConfirmation(true)
                .build();
    }

    @Override
    @Transactional
    public ConversationResponse continueConversation(
            String factoryId, Long userId, String sessionId, String userReply) {
        log.info("继续多轮对话: session={}, reply={}", sessionId, truncate(userReply, 50));

        Optional<ConversationSession> optSession =
                sessionRepository.findBySessionIdAndFactoryIdAndUserId(sessionId, factoryId, userId);
        if (optSession.isEmpty()) {
            log.warn("会话不存在: {}", sessionId);
            return ConversationResponse.builder()
                    .sessionId(sessionId)
                    .status(SessionStatus.CANCELLED)
                    .completed(false)
                    .message("会话不存在或已过期")
                    .build();
        }

        ConversationSession session = optSession.get();

        // 检查会话状态
        if (session.isExpired()) {
            session.timeout();
            sessionRepository.save(session);
            return ConversationResponse.builder()
                    .sessionId(sessionId)
                    .status(SessionStatus.TIMEOUT)
                    .completed(false)
                    .message("会话已超时，请重新开始")
                    .build();
        }

        if (!session.canContinue()) {
            return ConversationResponse.builder()
                    .sessionId(sessionId)
                    .status(session.getStatus())
                    .completed(session.getStatus() == SessionStatus.COMPLETED)
                    .message(getStatusMessage(session.getStatus()))
                    .build();
        }

        // 根据会话模式分发处理
        if (session.isParameterCollectionMode()) {
            return continueParameterCollection(session, userReply);
        } else {
            return continueIntentRecognition(session, userReply);
        }
    }

    /**
     * 继续参数收集对话
     */
    private ConversationResponse continueParameterCollection(ConversationSession session, String userReply) {
        log.info("继续参数收集: session={}, reply={}", session.getSessionId(), truncate(userReply, 50));

        // 添加用户消息
        session.addUserMessage(userReply);

        // 进入下一轮
        if (!session.nextRound()) {
            session.maxRoundsReached();
            sessionRepository.save(session);
            return ConversationResponse.builder()
                    .sessionId(session.getSessionId())
                    .currentRound(session.getCurrentRound())
                    .maxRounds(session.getMaxRounds())
                    .status(SessionStatus.MAX_ROUNDS_REACHED)
                    .completed(false)
                    .intentCode(session.getKnownIntentCode())
                    .message("对话已达最大轮次。请尝试重新开始操作。")
                    .build();
        }

        // 使用 LLM 提取参数值
        LlmParameterExtractionResult extractionResult = extractParametersWithLlm(session, userReply);

        // 更新收集到的参数
        if (extractionResult.getExtractedParams() != null) {
            for (Map.Entry<String, String> entry : extractionResult.getExtractedParams().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    session.addCollectedParameter(entry.getKey(), entry.getValue());
                    log.info("收集到参数: {}={}", entry.getKey(), entry.getValue());
                }
            }
        }

        // 检查是否所有参数都已收集
        if (session.allParametersCollected()) {
            session.completeParameterCollection();
            sessionRepository.save(session);

            String message = String.format("好的，已收集到所有必需信息。正在为您执行「%s」...",
                    session.getKnownIntentCode());
            session.addAssistantMessage(message);

            log.info("参数收集完成: session={}, intent={}, params={}",
                    session.getSessionId(), session.getKnownIntentCode(), session.getCollectedParameters());

            return ConversationResponse.builder()
                    .sessionId(session.getSessionId())
                    .currentRound(session.getCurrentRound())
                    .maxRounds(session.getMaxRounds())
                    .status(SessionStatus.COMPLETED)
                    .completed(true)
                    .message(message)
                    .intentCode(session.getKnownIntentCode())
                    .confidence(1.0)
                    .requiresConfirmation(false)
                    .build();
        }

        // 生成下一个参数收集问题
        String nextQuestion = buildNextParameterQuestion(session);
        session.addAssistantMessage(nextQuestion);

        sessionRepository.save(session);

        return ConversationResponse.builder()
                .sessionId(session.getSessionId())
                .currentRound(session.getCurrentRound())
                .maxRounds(session.getMaxRounds())
                .status(session.getStatus())
                .completed(false)
                .message(nextQuestion)
                .intentCode(session.getKnownIntentCode())
                .requiresConfirmation(true)
                .build();
    }

    /**
     * 继续意图识别对话 (原有逻辑)
     */
    private ConversationResponse continueIntentRecognition(ConversationSession session, String userReply) {
        // 添加用户消息
        session.addUserMessage(userReply);

        // 检查是否是直接确认 (用户选择了候选意图)
        String confirmedIntent = checkDirectConfirmation(userReply, session.getCandidates());
        if (confirmedIntent != null) {
            session.complete(confirmedIntent, 0.95);
            sessionRepository.save(session);

            // 触发学习
            learnFromSession(session);

            return buildConfirmedResponse(session, confirmedIntent);
        }

        // 进入下一轮
        if (!session.nextRound()) {
            session.maxRoundsReached();
            sessionRepository.save(session);
            return ConversationResponse.builder()
                    .sessionId(session.getSessionId())
                    .currentRound(session.getCurrentRound())
                    .maxRounds(session.getMaxRounds())
                    .status(SessionStatus.MAX_ROUNDS_REACHED)
                    .completed(false)
                    .message("对话已达最大轮次，无法确定您的意图。请尝试更清晰地描述您的需求。")
                    .build();
        }

        // 获取可用意图
        List<AIIntentConfig> availableIntents = getAvailableIntents(session.getFactoryId());

        // 调用 LLM 继续对话
        LlmConversationResult llmResult = callLlmForConversation(session, userReply, availableIntents);

        // 更新会话
        session.addAssistantMessage(llmResult.getMessage());
        session.setCandidates(llmResult.getCandidates());
        session.setLastConfidence(llmResult.getConfidence());

        // 检查是否确定意图
        if (llmResult.isIntentConfirmed() && llmResult.getConfidence() >= confidenceThreshold) {
            session.complete(llmResult.getIntentCode(), llmResult.getConfidence());
            log.info("多轮对话确定意图: session={}, round={}, intent={}, confidence={}",
                    session.getSessionId(), session.getCurrentRound(),
                    llmResult.getIntentCode(), llmResult.getConfidence());

            // 触发学习
            learnFromSession(session);
        }

        sessionRepository.save(session);

        return buildResponse(session, llmResult);
    }

    @Override
    @Transactional
    public boolean endConversation(String factoryId, Long userId, String sessionId, String intentCode) {
        log.info("结束对话: session={}, intent={}", sessionId, intentCode);

        Optional<ConversationSession> optSession =
                sessionRepository.findBySessionIdAndFactoryIdAndUserId(sessionId, factoryId, userId);
        if (optSession.isEmpty()) {
            return false;
        }

        ConversationSession session = optSession.get();
        session.complete(intentCode, 1.0); // 用户确认置信度为1
        sessionRepository.save(session);

        // 触发学习
        learnFromSession(session);

        return true;
    }

    @Override
    @Transactional
    public boolean cancelConversation(String factoryId, Long userId, String sessionId) {
        log.info("取消对话: session={}", sessionId);

        Optional<ConversationSession> optSession =
                sessionRepository.findBySessionIdAndFactoryIdAndUserId(sessionId, factoryId, userId);
        if (optSession.isEmpty()) {
            return false;
        }

        ConversationSession session = optSession.get();
        session.cancel();
        sessionRepository.save(session);

        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConversationSession> getActiveSession(String factoryId, Long userId) {
        return sessionRepository.findLatestActiveSession(factoryId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConversationSession> getSession(String factoryId, Long userId, String sessionId) {
        return sessionRepository.findBySessionIdAndFactoryIdAndUserId(sessionId, factoryId, userId);
    }

    @Override
    @Transactional
    @Scheduled(fixedRate = 60000) // 每分钟执行一次
    public int processExpiredSessions() {
        LocalDateTime expireTime = LocalDateTime.now().minusMinutes(timeoutMinutes);
        int count = sessionRepository.expireInactiveSessions(expireTime, LocalDateTime.now());
        if (count > 0) {
            log.info("处理过期会话: count={}", count);
        }
        return count;
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationStatistics getStatistics(String factoryId, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);

        ConversationStatistics stats = new ConversationStatistics();

        // 获取成功率数据
        List<Object[]> rateData = sessionRepository.getSuccessRate(factoryId, since);
        if (!rateData.isEmpty()) {
            Object[] row = rateData.get(0);
            long completed = ((Number) row[0]).longValue();
            long timeout = ((Number) row[1]).longValue();
            long cancelled = ((Number) row[2]).longValue();
            long maxRounds = ((Number) row[3]).longValue();
            long total = ((Number) row[4]).longValue();

            stats.setCompletedCount(completed);
            stats.setTimeoutCount(timeout);
            stats.setCancelledCount(cancelled);
            stats.setMaxRoundsCount(maxRounds);
            stats.setTotalSessions(total);
            stats.setSuccessRate(total > 0 ? (double) completed / total : 0);
        }

        // 获取平均轮次
        Double avgRounds = sessionRepository.getAverageRoundsForCompleted(factoryId, since);
        stats.setAverageRounds(avgRounds != null ? avgRounds : 0);

        // 活跃会话数
        stats.setActiveSessions(sessionRepository.countActiveByFactoryId(factoryId));

        return stats;
    }

    // ========== 私有方法 ==========

    /**
     * 获取可用意图列表
     */
    private List<AIIntentConfig> getAvailableIntents(String factoryId) {
        return intentConfigRepository.findActiveByFactoryIdWithPriority(factoryId);
    }

    /**
     * 调用 LLM 进行对话
     */
    private LlmConversationResult callLlmForConversation(ConversationSession session,
                                                          String userInput,
                                                          List<AIIntentConfig> availableIntents) {
        if (dashScopeClient == null || !dashScopeClient.isAvailable()) {
            log.warn("DashScope 不可用，使用默认响应");
            return generateDefaultResponse(availableIntents);
        }

        try {
            // R2: 按用户输入相关性过滤意图，减少 prompt 大小
            List<AIIntentConfig> relevantIntents = filterRelevantIntents(availableIntents, userInput, 25);

            // 构建系统提示词
            String systemPrompt = buildConversationSystemPrompt(relevantIntents, session);

            // 构建对话历史
            String conversationHistory = session.buildConversationHistory();
            String userMessage = conversationHistory.isEmpty()
                    ? userInput
                    : conversationHistory + "用户: " + userInput;

            // R2+R4: 使用 fastModel (flash) 并记录耗时
            long t0 = System.currentTimeMillis();
            String response = dashScopeClient.chatFast(systemPrompt, userMessage);
            long elapsed = System.currentTimeMillis() - t0;
            log.info("Conv LLM 调用: model=flash, intents={}/{}, promptLen={}, elapsed={}ms",
                    relevantIntents.size(), availableIntents.size(),
                    systemPrompt.length(), elapsed);

            // 解析响应
            return parseLlmResponse(response, availableIntents);

        } catch (Exception e) {
            log.error("LLM 对话调用失败: {}", e.getMessage(), e);
            return generateDefaultResponse(availableIntents);
        }
    }

    /**
     * R2: 按关键词相关性过滤意图，减少 prompt token 数
     * 从全量意图中筛选与用户输入最相关的 top-N 个
     */
    private List<AIIntentConfig> filterRelevantIntents(List<AIIntentConfig> allIntents,
                                                        String userInput, int topN) {
        if (allIntents.size() <= topN) {
            return allIntents;
        }

        String inputLower = userInput.toLowerCase();

        // 按关键词命中数排序
        List<Map.Entry<AIIntentConfig, Integer>> scored = new ArrayList<>();
        for (AIIntentConfig intent : allIntents) {
            int score = 0;
            String code = intent.getIntentCode() != null ? intent.getIntentCode().toLowerCase() : "";
            String name = intent.getIntentName() != null ? intent.getIntentName() : "";
            String desc = intent.getDescription() != null ? intent.getDescription() : "";
            String keywords = intent.getKeywords() != null ? intent.getKeywords() : "";
            String combined = name + " " + desc + " " + keywords;

            // 意图名称中的每个字在用户输入中出现 → +2
            for (char c : name.toCharArray()) {
                if (c != ' ' && inputLower.indexOf(c) >= 0) {
                    score += 2;
                }
            }
            // 关键词匹配 → +3
            if (!keywords.isEmpty()) {
                for (String kw : keywords.split("[,，;；\\s]+")) {
                    if (!kw.isEmpty() && inputLower.contains(kw.toLowerCase())) {
                        score += 3;
                    }
                }
            }
            // 描述中的关键字匹配 → +1
            for (String word : inputLower.split("\\s+")) {
                if (word.length() >= 2 && combined.toLowerCase().contains(word)) {
                    score++;
                }
            }
            scored.add(new AbstractMap.SimpleEntry<>(intent, score));
        }

        // 按分数降序，取 top-N
        scored.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return scored.stream()
                .limit(topN)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 构建对话系统提示词
     */
    private String buildConversationSystemPrompt(List<AIIntentConfig> availableIntents,
                                                  ConversationSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个意图识别助手，正在进行多轮对话来澄清用户的真实意图。\n\n");

        sb.append("## 当前对话状态\n");
        sb.append("- 轮次: ").append(session.getCurrentRound()).append("/").append(session.getMaxRounds()).append("\n");
        sb.append("- 原始输入: ").append(session.getOriginalInput()).append("\n\n");

        sb.append("## 可用意图列表\n\n");
        for (AIIntentConfig intent : availableIntents) {
            sb.append(String.format("- **%s** (%s): %s\n",
                    intent.getIntentCode(),
                    intent.getIntentName(),
                    intent.getDescription() != null ? intent.getDescription() : ""));
        }

        sb.append("\n## 你的任务\n\n");
        sb.append("1. 分析用户的输入和对话历史\n");
        sb.append("2. 如果能确定用户意图（置信度 >= 0.7），返回确认结果\n");
        sb.append("3. 如果不确定，生成一个澄清问题来帮助确定意图\n");
        sb.append("4. 澄清问题应该简洁明了，并提供选项供用户选择\n\n");

        sb.append("## 输出格式 (JSON)\n\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"is_confirmed\": true/false,\n");
        sb.append("  \"intent_code\": \"意图代码 (如果确定)\",\n");
        sb.append("  \"confidence\": 0.0-1.0,\n");
        sb.append("  \"message\": \"给用户的回复 (澄清问题或确认消息)\",\n");
        sb.append("  \"candidates\": [\n");
        sb.append("    {\"intent_code\": \"...\", \"intent_name\": \"...\", \"confidence\": 0.5}\n");
        sb.append("  ],\n");
        sb.append("  \"reasoning\": \"判断理由\"\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append("仅返回 JSON，不要包含其他文字。");

        return sb.toString();
    }

    /**
     * 解析 LLM 响应
     */
    private LlmConversationResult parseLlmResponse(String response, List<AIIntentConfig> availableIntents) {
        try {
            // 提取 JSON
            Pattern pattern = Pattern.compile("\\{[\\s\\S]*\\}");
            Matcher matcher = pattern.matcher(response);

            if (!matcher.find()) {
                log.warn("无法从 LLM 响应中提取 JSON: {}", truncate(response, 100));
                return generateDefaultResponse(availableIntents);
            }

            JsonNode json = objectMapper.readTree(matcher.group());

            boolean isConfirmed = json.has("is_confirmed") && json.get("is_confirmed").asBoolean();
            String intentCode = json.has("intent_code") ? json.get("intent_code").asText() : null;
            double confidence = json.has("confidence") ? json.get("confidence").asDouble() : 0.5;
            String message = json.has("message") ? json.get("message").asText() : "请问您想要执行什么操作？";
            String reasoning = json.has("reasoning") ? json.get("reasoning").asText() : null;

            // Clamp confidence
            confidence = Math.max(0.0, Math.min(1.0, confidence));

            // 解析候选
            List<CandidateIntent> candidates = new ArrayList<>();
            if (json.has("candidates") && json.get("candidates").isArray()) {
                for (JsonNode candNode : json.get("candidates")) {
                    String code = candNode.has("intent_code") ? candNode.get("intent_code").asText() : null;
                    String name = candNode.has("intent_name") ? candNode.get("intent_name").asText() : code;
                    double candConf = candNode.has("confidence") ? candNode.get("confidence").asDouble() : 0.3;

                    if (code != null && !code.isEmpty()) {
                        candidates.add(CandidateIntent.builder()
                                .intentCode(code)
                                .intentName(name)
                                .confidence(candConf)
                                .build());
                    }
                }
            }

            // 验证意图代码
            if (isConfirmed && intentCode != null) {
                boolean validIntent = availableIntents.stream()
                        .anyMatch(i -> intentCode.equalsIgnoreCase(i.getIntentCode()));
                if (!validIntent) {
                    log.warn("LLM 返回无效意图代码: {}", intentCode);
                    isConfirmed = false;
                }
            }

            return LlmConversationResult.builder()
                    .intentConfirmed(isConfirmed)
                    .intentCode(intentCode)
                    .confidence(confidence)
                    .message(message)
                    .candidates(candidates)
                    .reasoning(reasoning)
                    .build();

        } catch (Exception e) {
            log.error("解析 LLM 响应失败: {}", e.getMessage(), e);
            return generateDefaultResponse(availableIntents);
        }
    }

    /**
     * 生成默认响应
     */
    private LlmConversationResult generateDefaultResponse(List<AIIntentConfig> availableIntents) {
        List<CandidateIntent> candidates = availableIntents.stream()
                .limit(5)
                .map(i -> CandidateIntent.builder()
                        .intentCode(i.getIntentCode())
                        .intentName(i.getIntentName())
                        .confidence(0.3)
                        .build())
                .collect(Collectors.toList());

        StringBuilder message = new StringBuilder("请问您想要执行以下哪个操作？\n");
        for (int i = 0; i < Math.min(candidates.size(), 5); i++) {
            CandidateIntent c = candidates.get(i);
            message.append(String.format("%d. %s\n", i + 1, c.getIntentName()));
        }
        message.append("\n请输入数字或描述您的需求。");

        return LlmConversationResult.builder()
                .intentConfirmed(false)
                .confidence(0.3)
                .message(message.toString())
                .candidates(candidates)
                .build();
    }

    /**
     * 检查用户是否直接确认了某个候选意图
     */
    private String checkDirectConfirmation(String userReply, List<CandidateIntent> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        String reply = userReply.trim().toLowerCase();

        // 检查数字选择 (1, 2, 3...)
        if (reply.matches("\\d+")) {
            int index = Integer.parseInt(reply) - 1;
            if (index >= 0 && index < candidates.size()) {
                return candidates.get(index).getIntentCode();
            }
        }

        // 检查意图名称匹配
        for (CandidateIntent candidate : candidates) {
            if (reply.contains(candidate.getIntentName().toLowerCase()) ||
                reply.contains(candidate.getIntentCode().toLowerCase())) {
                return candidate.getIntentCode();
            }
        }

        // 检查确认词
        if (reply.matches(".*(是的?|对|确认|好的?|没错|正确).*") && candidates.size() == 1) {
            return candidates.get(0).getIntentCode();
        }

        return null;
    }

    /**
     * 从会话中学习
     */
    private void learnFromSession(ConversationSession session) {
        if (learningService == null) {
            log.debug("学习服务不可用，跳过学习");
            return;
        }

        String intentCode = session.getFinalIntentCode();
        if (intentCode == null || intentCode.isEmpty()) {
            return;
        }

        try {
            // 学习原始表达
            String originalInput = session.getOriginalInput();
            learningService.learnExpression(
                    session.getFactoryId(),
                    intentCode,
                    originalInput,
                    session.getLastConfidence() != null ? session.getLastConfidence() : 0.8,
                    LearnedExpression.SourceType.USER_FEEDBACK
            );

            log.info("从多轮对话学习表达: session={}, intent={}, expr={}",
                    session.getSessionId(), intentCode, truncate(originalInput, 50));

            // 提取并学习关键词 (可选)
            // 这里可以添加更复杂的关键词提取逻辑

        } catch (Exception e) {
            log.error("从会话学习失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 构建响应
     */
    private ConversationResponse buildResponse(ConversationSession session, LlmConversationResult llmResult) {
        List<CandidateInfo> candidateInfos = llmResult.getCandidates().stream()
                .map(c -> CandidateInfo.builder()
                        .intentCode(c.getIntentCode())
                        .intentName(c.getIntentName())
                        .confidence(c.getConfidence())
                        .build())
                .collect(Collectors.toList());

        String intentName = null;
        if (llmResult.isIntentConfirmed() && llmResult.getIntentCode() != null) {
            intentName = intentConfigRepository.findByIntentCode(llmResult.getIntentCode())
                    .map(AIIntentConfig::getIntentName)
                    .orElse(llmResult.getIntentCode());
        }

        return ConversationResponse.builder()
                .sessionId(session.getSessionId())
                .currentRound(session.getCurrentRound())
                .maxRounds(session.getMaxRounds())
                .status(session.getStatus())
                .completed(session.getStatus() == SessionStatus.COMPLETED)
                .message(llmResult.getMessage())
                .intentCode(llmResult.isIntentConfirmed() ? llmResult.getIntentCode() : null)
                .intentName(intentName)
                .confidence(llmResult.getConfidence())
                .candidates(candidateInfos)
                .requiresConfirmation(!llmResult.isIntentConfirmed() ||
                        llmResult.getConfidence() < confidenceThreshold)
                .build();
    }

    /**
     * 构建确认响应
     */
    private ConversationResponse buildConfirmedResponse(ConversationSession session, String intentCode) {
        String intentName = intentConfigRepository.findByIntentCode(intentCode)
                .map(AIIntentConfig::getIntentName)
                .orElse(intentCode);

        return ConversationResponse.builder()
                .sessionId(session.getSessionId())
                .currentRound(session.getCurrentRound())
                .maxRounds(session.getMaxRounds())
                .status(SessionStatus.COMPLETED)
                .completed(true)
                .message(String.format("已确认您的意图: %s", intentName))
                .intentCode(intentCode)
                .intentName(intentName)
                .confidence(0.95)
                .requiresConfirmation(false)
                .build();
    }

    /**
     * 获取状态消息
     */
    private String getStatusMessage(SessionStatus status) {
        switch (status) {
            case COMPLETED:
                return "对话已完成";
            case TIMEOUT:
                return "对话已超时";
            case CANCELLED:
                return "对话已取消";
            case MAX_ROUNDS_REACHED:
                return "对话已达最大轮次";
            default:
                return "对话状态异常";
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ========== 内部类 ==========

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class LlmConversationResult {
        private boolean intentConfirmed;
        private String intentCode;
        private double confidence;
        private String message;
        private List<CandidateIntent> candidates;
        private String reasoning;
    }

    /**
     * LLM 参数提取结果
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class LlmParameterExtractionResult {
        /** 提取到的参数 (参数名 -> 参数值) */
        private Map<String, String> extractedParams;
        /** LLM 推理过程 */
        private String reasoning;
        /** 是否成功提取 */
        private boolean successful;
        /** 错误消息 (如果失败) */
        private String errorMessage;
    }

    // ========== 参数收集辅助方法 ==========

    /**
     * 构建参数收集初始消息
     *
     * @param intentName 意图名称
     * @param requiredParameters 需要收集的参数列表
     * @param clarificationQuestions Handler 提供的澄清问题
     * @return 初始收集消息
     */
    private String buildParameterCollectionMessage(
            String intentName,
            List<RequiredParameter> requiredParameters,
            List<String> clarificationQuestions) {

        StringBuilder sb = new StringBuilder();
        sb.append("好的，我来帮您执行「").append(intentName != null ? intentName : "操作").append("」。\n\n");

        // 如果 Handler 提供了澄清问题，直接使用
        if (clarificationQuestions != null && !clarificationQuestions.isEmpty()) {
            sb.append("需要您提供以下信息：\n");
            for (String question : clarificationQuestions) {
                sb.append("• ").append(question).append("\n");
            }
        } else if (requiredParameters != null && !requiredParameters.isEmpty()) {
            // 否则根据参数列表生成问题
            sb.append("请提供以下必需信息：\n");
            for (RequiredParameter param : requiredParameters) {
                String label = param.getLabel() != null ? param.getLabel() : param.getName();
                sb.append("• ").append(label);
                if (param.getValidationHint() != null && !param.getValidationHint().isEmpty()) {
                    sb.append(" (").append(param.getValidationHint()).append(")");
                }
                sb.append("\n");
            }
        }

        sb.append("\n请直接告诉我，我会帮您完成操作。");
        return sb.toString();
    }

    /**
     * 使用 LLM 从用户输入中提取参数值
     *
     * @param session 当前会话
     * @param userReply 用户回复
     * @return 提取结果
     */
    private LlmParameterExtractionResult extractParametersWithLlm(ConversationSession session, String userReply) {
        List<ConversationSession.RequiredParameter> pendingParams = session.getRequiredParameters();
        if (pendingParams == null || pendingParams.isEmpty()) {
            return LlmParameterExtractionResult.builder()
                    .extractedParams(new HashMap<>())
                    .successful(true)
                    .build();
        }

        // 过滤出未收集的参数
        List<ConversationSession.RequiredParameter> uncollectedParams = pendingParams.stream()
                .filter(p -> !p.isCollected())
                .collect(Collectors.toList());

        if (uncollectedParams.isEmpty()) {
            return LlmParameterExtractionResult.builder()
                    .extractedParams(new HashMap<>())
                    .successful(true)
                    .build();
        }

        // 如果 DashScope 不可用，尝试简单提取
        if (dashScopeClient == null || !dashScopeClient.isAvailable()) {
            log.debug("DashScope 不可用，使用简单提取");
            return extractParametersSimple(uncollectedParams, userReply);
        }

        try {
            // 构建 LLM 提示词
            String systemPrompt = buildParameterExtractionPrompt(uncollectedParams, session);
            String response = dashScopeClient.chat(systemPrompt, "用户输入: " + userReply);

            // 解析 LLM 响应
            return parseParameterExtractionResponse(response, uncollectedParams);

        } catch (Exception e) {
            log.error("LLM 参数提取失败: {}", e.getMessage(), e);
            // 降级到简单提取
            return extractParametersSimple(uncollectedParams, userReply);
        }
    }

    /**
     * 构建参数提取提示词
     */
    private String buildParameterExtractionPrompt(
            List<ConversationSession.RequiredParameter> params,
            ConversationSession session) {

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个参数提取助手。请从用户输入中提取以下参数值。\n\n");
        sb.append("## 意图: ").append(session.getKnownIntentCode()).append("\n\n");

        sb.append("## 需要提取的参数:\n");
        for (ConversationSession.RequiredParameter param : params) {
            sb.append(String.format("- **%s** (%s): %s\n",
                    param.getName(),
                    param.getLabel() != null ? param.getLabel() : param.getName(),
                    param.getType() != null ? param.getType() : "string"));
            if (param.getValidationHint() != null) {
                sb.append("  提示: ").append(param.getValidationHint()).append("\n");
            }
        }

        sb.append("\n## 输出格式 (JSON):\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"extracted\": {\n");
        sb.append("    \"参数名\": \"提取的值或null\"\n");
        sb.append("  },\n");
        sb.append("  \"reasoning\": \"提取理由\"\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append("仅返回 JSON，对于无法提取的参数，值设为 null。");

        return sb.toString();
    }

    /**
     * 解析 LLM 参数提取响应
     */
    private LlmParameterExtractionResult parseParameterExtractionResponse(
            String response,
            List<ConversationSession.RequiredParameter> expectedParams) {

        Map<String, String> extractedParams = new HashMap<>();

        try {
            // 提取 JSON
            Pattern pattern = Pattern.compile("\\{[\\s\\S]*\\}");
            Matcher matcher = pattern.matcher(response);

            if (!matcher.find()) {
                log.warn("无法从参数提取响应中提取 JSON");
                return LlmParameterExtractionResult.builder()
                        .extractedParams(extractedParams)
                        .successful(false)
                        .errorMessage("无法解析 LLM 响应")
                        .build();
            }

            JsonNode json = objectMapper.readTree(matcher.group());
            String reasoning = json.has("reasoning") ? json.get("reasoning").asText() : null;

            if (json.has("extracted") && json.get("extracted").isObject()) {
                JsonNode extracted = json.get("extracted");
                for (ConversationSession.RequiredParameter param : expectedParams) {
                    if (extracted.has(param.getName())) {
                        JsonNode valueNode = extracted.get(param.getName());
                        if (!valueNode.isNull()) {
                            String value = valueNode.asText();
                            if (value != null && !value.isEmpty() && !"null".equalsIgnoreCase(value)) {
                                extractedParams.put(param.getName(), value);
                            }
                        }
                    }
                }
            }

            return LlmParameterExtractionResult.builder()
                    .extractedParams(extractedParams)
                    .reasoning(reasoning)
                    .successful(true)
                    .build();

        } catch (Exception e) {
            log.error("解析参数提取响应失败: {}", e.getMessage(), e);
            return LlmParameterExtractionResult.builder()
                    .extractedParams(extractedParams)
                    .successful(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * 简单参数提取 (不使用 LLM)
     *
     * 针对单参数场景，直接使用用户输入作为参数值
     */
    private LlmParameterExtractionResult extractParametersSimple(
            List<ConversationSession.RequiredParameter> params,
            String userReply) {

        Map<String, String> extractedParams = new HashMap<>();
        String trimmedReply = userReply.trim();

        // 如果只有一个未收集的参数，直接使用用户输入
        if (params.size() == 1) {
            ConversationSession.RequiredParameter param = params.get(0);
            if (!trimmedReply.isEmpty()) {
                // 基本验证
                if (isValidParameterValue(param, trimmedReply)) {
                    extractedParams.put(param.getName(), trimmedReply);
                }
            }
        } else {
            // 多参数场景: 尝试按行/逗号分割
            String[] parts = trimmedReply.split("[,，;；\\n]+");
            int partIndex = 0;
            for (ConversationSession.RequiredParameter param : params) {
                if (partIndex < parts.length) {
                    String value = parts[partIndex].trim();
                    // 移除可能的标签前缀 (如 "批次号: XXX")
                    int colonIndex = value.indexOf(':');
                    if (colonIndex == -1) colonIndex = value.indexOf('：');
                    if (colonIndex > 0 && colonIndex < value.length() - 1) {
                        value = value.substring(colonIndex + 1).trim();
                    }
                    if (!value.isEmpty() && isValidParameterValue(param, value)) {
                        extractedParams.put(param.getName(), value);
                    }
                    partIndex++;
                }
            }
        }

        return LlmParameterExtractionResult.builder()
                .extractedParams(extractedParams)
                .successful(true)
                .reasoning("简单提取模式")
                .build();
    }

    /**
     * 验证参数值是否符合类型要求
     */
    private boolean isValidParameterValue(ConversationSession.RequiredParameter param, String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        String type = param.getType();
        if (type == null) {
            return true; // 无类型限制
        }

        switch (type.toLowerCase()) {
            case "number":
            case "integer":
            case "int":
                return value.matches("-?\\d+(\\.\\d+)?");
            case "date":
                return value.matches("\\d{4}-\\d{2}-\\d{2}") ||
                       value.matches("\\d{4}/\\d{2}/\\d{2}") ||
                       value.matches("\\d{8}");
            case "boolean":
            case "bool":
                return value.matches("(?i)true|false|yes|no|是|否|1|0");
            case "uuid":
                return value.matches("[a-fA-F0-9-]{36}");
            default:
                return true; // string 或其他类型
        }
    }

    /**
     * 构建下一个参数收集问题
     *
     * @param session 当前会话
     * @return 下一个问题
     */
    private String buildNextParameterQuestion(ConversationSession session) {
        Optional<ConversationSession.RequiredParameter> nextParamOpt = session.getNextPendingParameter();

        if (nextParamOpt.isEmpty()) {
            return "好的，信息收集完成。正在处理您的请求...";
        }

        ConversationSession.RequiredParameter nextParam = nextParamOpt.get();

        // 获取已收集的参数用于上下文
        Map<String, String> collected = session.getCollectedParameters();
        int collectedCount = collected != null ? collected.size() : 0;
        List<ConversationSession.RequiredParameter> allParams = session.getRequiredParameters();
        int totalCount = allParams != null ? allParams.size() : 0;

        StringBuilder sb = new StringBuilder();

        // 如果已收集部分参数，先确认
        if (collectedCount > 0) {
            sb.append("好的，已记录。");
        }

        // 提示还需要的参数
        int remainingCount = totalCount - collectedCount - 1; // -1 是因为下面会问当前参数
        if (remainingCount > 0) {
            sb.append(String.format("还需要 %d 项信息。\n\n", remainingCount + 1));
        } else {
            sb.append("\n\n");
        }

        // 生成当前参数的问题
        String label = nextParam.getLabel() != null ? nextParam.getLabel() : nextParam.getName();
        sb.append("请提供 **").append(label).append("**");

        if (nextParam.getValidationHint() != null && !nextParam.getValidationHint().isEmpty()) {
            sb.append("\n").append("(").append(nextParam.getValidationHint()).append(")");
        }

        return sb.toString();
    }
}
