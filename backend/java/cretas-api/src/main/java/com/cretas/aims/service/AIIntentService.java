package com.cretas.aims.service;

import com.cretas.aims.dto.intent.IntentFeedbackRequest;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.MultiIntentResult;
import com.cretas.aims.entity.config.AIIntentConfig;

import java.util.List;
import java.util.Optional;

/**
 * AI意图服务接口
 *
 * 提供AI请求的意图识别和配置管理:
 * - 意图识别：根据用户输入匹配意图
 * - 权限校验：检查用户角色是否允许执行
 * - 敏感度控制：根据敏感级别决定审批流程
 * - 配额管理：计算配额消耗
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
public interface AIIntentService {

    // ==================== 意图识别 ====================

    /**
     * 识别用户输入的意图（租户隔离）
     *
     * @param factoryId 工厂ID（必须）
     * @param userInput 用户输入文本
     * @return 匹配的意图配置 (按优先级排序，返回最高优先级)
     */
    Optional<AIIntentConfig> recognizeIntent(String factoryId, String userInput);

    /**
     * Recognize intent with conversation context support (P4 Task 4.3).
     *
     * <p>Loads recent 3 turns from {@link com.cretas.aims.service.conversation.ConversationStateService}
     * for the given (factoryId, userId) before matching, and appends the recognized
     * turn after successful recognition. When userId is null, this behaves
     * identically to {@link #recognizeIntent(String, String)}.
     *
     * <p>Default implementation ignores userId — concrete impl overrides.
     *
     * @param factoryId factory identifier
     * @param userInput user's natural language input
     * @param userId    user identifier, or null to skip context tracking
     * @return recognized intent config, empty if no match
     */
    default Optional<AIIntentConfig> recognizeIntent(
            String factoryId, String userInput, String userId) {
        // Default implementation ignores userId — concrete impl overrides.
        return recognizeIntent(factoryId, userInput);
    }

    /**
     * 识别用户输入的意图（无租户隔离，向后兼容）
     * @deprecated 请使用 {@link #recognizeIntent(String, String)} 代替
     * @param userInput 用户输入文本
     * @return 匹配的意图配置
     */
    @Deprecated
    Optional<AIIntentConfig> recognizeIntent(String userInput);

    /**
     * 识别用户输入的意图（增强版，返回置信度和候选列表）
     *
     * @param userInput 用户输入文本
     * @param topN 返回的候选意图数量（默认3）
     * @return 完整的匹配结果，包含置信度、候选意图、匹配方法等
     */
    IntentMatchResult recognizeIntentWithConfidence(String userInput, int topN);

    /**
     * 识别用户输入的意图（增强版，默认返回Top-3）
     *
     * @param userInput 用户输入文本
     * @return 完整的匹配结果
     */
    default IntentMatchResult recognizeIntentWithConfidence(String userInput) {
        return recognizeIntentWithConfidence(userInput, 3);
    }

    /**
     * 识别用户输入的意图（增强版，带工厂ID用于LLM Fallback上下文）
     *
     * @param userInput 用户输入文本
     * @param factoryId 工厂ID（用于LLM上下文）
     * @param topN 返回的候选意图数量
     * @param userId 用户ID（用于Tool Calling权限验证）
     * @param userRole 用户角色（用于Tool Calling权限验证）
     * @return 完整的匹配结果
     */
    IntentMatchResult recognizeIntentWithConfidence(String userInput, String factoryId, int topN, Long userId, String userRole);

    /**
     * 识别用户输入的意图（增强版，支持会话上下文）
     *
     * 在原有功能基础上增加：
     * - 查询预处理（规则预处理、口语标准化、时间归一化）
     * - 上下文注入（实体槽位、指代消解）
     * - 多轮对话支持
     *
     * @param userInput 用户输入文本
     * @param factoryId 工厂ID（用于LLM上下文）
     * @param topN 返回的候选意图数量
     * @param userId 用户ID（用于Tool Calling权限验证）
     * @param userRole 用户角色（用于Tool Calling权限验证）
     * @param sessionId 会话ID（用于对话上下文管理，可选）
     * @return 完整的匹配结果
     */
    IntentMatchResult recognizeIntentWithConfidence(String userInput, String factoryId, int topN,
                                                    Long userId, String userRole, String sessionId);

    /**
     * 识别用户输入的意图（读写分块版, spec §8.2 识别层候选过滤）。
     *
     * <p>在 6 参版基础上把咨询/操作 tab 的模式带进<b>识别层</b>: 既下发给 Python matcher
     * (填充 {@code PythonIntentMatchRequest.mode} / {@code userPermissions} 两个此前
     * 从未被赋值的目录过滤字段), 也在 Java 本地对候选集做同样的剔除, 使得无论走 Python 分支
     * 还是 legacy pipeline, {@code mode=READ} 下写意图都不会留在候选集里。
     *
     * <p>注意本方法<b>不做鉴权</b> —— {@code userPermissions} 只用于收窄候选,
     * 真正的授权仍在 IntentPermissionGate / ToolRbacEnforcer。
     *
     * @param mode            {@code "READ"} = 咨询 tab (剔除全部写意图候选);
     *                        {@code "OPERATE"} = 操作 tab (按 userPermissions 剔除无权限写意图);
     *                        null / 其它值 = 不过滤 (与 6 参版行为一致)
     * @param userPermissions 调用者权限码集合 (module:action); null = 不按权限过滤
     */
    default IntentMatchResult recognizeIntentWithConfidence(String userInput, String factoryId, int topN,
                                                            Long userId, String userRole, String sessionId,
                                                            String mode, java.util.Set<String> userPermissions) {
        return recognizeIntentWithConfidence(userInput, factoryId, topN, userId, userRole, sessionId);
    }

    // ==================== 多意图识别 ====================

    /**
     * 多标签意图识别（支持复合请求）
     *
     * 使用 Sigmoid-based Multi-Label Classification 方法：
     * 1. 将用户输入转为向量
     * 2. 与所有意图向量计算余弦相似度
     * 3. Sigmoid 归一化为 0-1 概率
     * 4. 阈值筛选返回多个意图
     *
     * @param userInput 用户输入文本
     * @param factoryId 工厂ID
     * @return 多意图识别结果
     */
    MultiIntentResult recognizeMultiIntent(String userInput, String factoryId);

    /**
     * 多标签意图识别（自定义阈值）
     *
     * @param userInput 用户输入文本
     * @param factoryId 工厂ID
     * @param threshold 置信度阈值 (0.0 - 1.0)
     * @return 多意图识别结果
     */
    MultiIntentResult recognizeMultiIntent(String userInput, String factoryId, double threshold);

    /**
     * 识别所有可能的意图（租户隔离）
     *
     * @param factoryId 工厂ID（必须）
     * @param userInput 用户输入文本
     * @return 所有匹配的意图配置列表
     */
    List<AIIntentConfig> recognizeAllIntents(String factoryId, String userInput);

    /**
     * 识别所有可能的意图（无租户隔离，向后兼容）
     * @deprecated 请使用 {@link #recognizeAllIntents(String, String)} 代替
     * @param userInput 用户输入文本
     * @return 所有匹配的意图配置列表
     */
    @Deprecated
    List<AIIntentConfig> recognizeAllIntents(String userInput);

    /**
     * 根据意图代码获取配置（租户隔离）
     *
     * @param factoryId 工厂ID（必须）
     * @param intentCode 意图代码
     * @return 意图配置（工厂级优先，平台级兜底）
     */
    Optional<AIIntentConfig> getIntentByCode(String factoryId, String intentCode);

    /**
     * 根据意图代码获取配置（无租户隔离，向后兼容）
     * @deprecated 请使用 {@link #getIntentByCode(String, String)} 代替
     * @param intentCode 意图代码
     * @return 意图配置
     */
    @Deprecated
    Optional<AIIntentConfig> getIntentByCode(String intentCode);

    // ==================== 权限校验 ====================

    /**
     * 检查用户角色是否有权限执行意图 (工厂感知)
     *
     * @param factoryId 工厂ID
     * @param intentCode 意图代码
     * @param userRole 用户角色
     * @return 是否有权限
     */
    boolean hasPermission(String factoryId, String intentCode, String userRole);

    /**
     * 检查用户角色是否有权限执行意图
     *
     * @deprecated 使用 {@link #hasPermission(String, String, String)} 代替，需要提供factoryId
     * @param intentCode 意图代码
     * @param userRole 用户角色
     * @return 是否有权限
     */
    @Deprecated
    boolean hasPermission(String intentCode, String userRole);

    /**
     * 检查意图是否需要审批 (工厂感知)
     *
     * @param factoryId 工厂ID
     * @param intentCode 意图代码
     * @return 是否需要审批
     */
    boolean requiresApproval(String factoryId, String intentCode);

    /**
     * 检查意图是否需要审批
     *
     * @deprecated 使用 {@link #requiresApproval(String, String)} 代替，需要提供factoryId
     * @param intentCode 意图代码
     * @return 是否需要审批
     */
    @Deprecated
    boolean requiresApproval(String intentCode);

    /**
     * 获取意图的审批链ID (工厂感知)
     *
     * @param factoryId 工厂ID
     * @param intentCode 意图代码
     * @return 审批链ID (如果需要审批)
     */
    Optional<String> getApprovalChainId(String factoryId, String intentCode);

    /**
     * 获取意图的审批链ID
     *
     * @deprecated 使用 {@link #getApprovalChainId(String, String)} 代替，需要提供factoryId
     * @param intentCode 意图代码
     * @return 审批链ID (如果需要审批)
     */
    @Deprecated
    Optional<String> getApprovalChainId(String intentCode);

    // ==================== 配额管理 ====================

    /**
     * 获取意图的配额消耗 (工厂感知)
     *
     * @param factoryId 工厂ID
     * @param intentCode 意图代码
     * @return 配额消耗值
     */
    int getQuotaCost(String factoryId, String intentCode);

    /**
     * 获取意图的配额消耗
     *
     * @deprecated 使用 {@link #getQuotaCost(String, String)} 代替，需要提供factoryId
     * @param intentCode 意图代码
     * @return 配额消耗值
     */
    @Deprecated
    int getQuotaCost(String intentCode);

    /**
     * 获取意图的缓存TTL (工厂感知)
     *
     * @param factoryId 工厂ID
     * @param intentCode 意图代码
     * @return 缓存有效期 (分钟)，0表示不缓存
     */
    int getCacheTtl(String factoryId, String intentCode);

    /**
     * 获取意图的缓存TTL
     *
     * @deprecated 使用 {@link #getCacheTtl(String, String)} 代替，需要提供factoryId
     * @param intentCode 意图代码
     * @return 缓存有效期 (分钟)，0表示不缓存
     */
    @Deprecated
    int getCacheTtl(String intentCode);

    // ==================== 意图查询 ====================

    /**
     * 获取所有启用的意图配置（租户隔离）
     *
     * @param factoryId 工厂ID（必须）
     * @return 意图配置列表（工厂级+平台级）
     */
    List<AIIntentConfig> getAllIntents(String factoryId);

    /**
     * 获取所有启用的意图配置（无租户隔离，向后兼容）
     * @deprecated 请使用 {@link #getAllIntents(String)} 代替
     * @return 意图配置列表
     */
    @Deprecated
    List<AIIntentConfig> getAllIntents();

    /**
     * 根据分类获取意图配置（租户隔离）
     *
     * @param factoryId 工厂ID（必须）
     * @param category 意图分类
     * @return 意图配置列表
     */
    List<AIIntentConfig> getIntentsByCategory(String factoryId, String category);

    /**
     * 根据分类获取意图配置（无租户隔离，向后兼容）
     * @deprecated 请使用 {@link #getIntentsByCategory(String, String)} 代替
     */
    @Deprecated
    List<AIIntentConfig> getIntentsByCategory(String category);

    /**
     * 根据敏感度级别获取意图配置（租户隔离）
     *
     * @param factoryId 工厂ID（必须）
     * @param sensitivityLevel 敏感度级别 (LOW, MEDIUM, HIGH, CRITICAL)
     * @return 意图配置列表
     */
    List<AIIntentConfig> getIntentsBySensitivity(String factoryId, String sensitivityLevel);

    /**
     * 根据敏感度级别获取意图配置（无租户隔离，向后兼容）
     * @deprecated 请使用 {@link #getIntentsBySensitivity(String, String)} 代替
     */
    @Deprecated
    List<AIIntentConfig> getIntentsBySensitivity(String sensitivityLevel);

    /**
     * 获取所有意图分类（租户隔离）
     *
     * @param factoryId 工厂ID（必须）
     * @return 分类列表
     */
    List<String> getAllCategories(String factoryId);

    /**
     * 获取所有意图分类（无租户隔离，向后兼容）
     * @deprecated 请使用 {@link #getAllCategories(String)} 代替
     */
    @Deprecated
    List<String> getAllCategories();

    // ==================== 意图管理 ====================

    /**
     * 创建意图配置
     *
     * @param intentConfig 意图配置
     * @return 创建的意图配置
     */
    AIIntentConfig createIntent(AIIntentConfig intentConfig);

    /**
     * 更新意图配置
     *
     * @param intentConfig 意图配置
     * @return 更新后的意图配置
     */
    AIIntentConfig updateIntent(AIIntentConfig intentConfig);

    /**
     * 删除意图配置 (软删除)
     *
     * @param intentCode 意图代码
     */
    void deleteIntent(String intentCode);

    /**
     * 启用/禁用意图
     *
     * @param intentCode 意图代码
     * @param active 是否启用
     */
    void setIntentActive(String intentCode, boolean active);

    // ==================== 缓存管理 ====================

    /**
     * 清除意图配置缓存
     */
    void clearCache();

    /**
     * 刷新意图配置缓存
     */
    void refreshCache();

    // ==================== 反馈记录 ====================

    /**
     * 记录意图匹配的正向反馈
     * 用于追踪关键词效果，当用户确认匹配正确时调用
     *
     * @param factoryId 工厂ID
     * @param intentCode 意图代码
     * @param matchedKeywords 匹配到的关键词列表
     */
    void recordPositiveFeedback(String factoryId, String intentCode, List<String> matchedKeywords);

    /**
     * 记录意图匹配的负向反馈
     * 当用户拒绝匹配结果并选择其他意图时调用
     *
     * @param factoryId 工厂ID
     * @param rejectedIntentCode 被拒绝的意图代码
     * @param selectedIntentCode 用户选择的正确意图代码
     * @param matchedKeywords 原匹配到的关键词列表
     */
    void recordNegativeFeedback(String factoryId, String rejectedIntentCode,
                                String selectedIntentCode, List<String> matchedKeywords);

    /**
     * 处理意图识别反馈
     * 用户可以纠正错误的意图识别结果，系统自动学习
     *
     * @param factoryId 工厂ID
     * @param userId 用户ID
     * @param request 反馈请求
     */
    void processIntentFeedback(String factoryId, Long userId, IntentFeedbackRequest request);
}
