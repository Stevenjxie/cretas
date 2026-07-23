package com.cretas.aims.dto.ai;

import com.cretas.aims.dto.intent.ValidationResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI意图执行响应DTO
 *
 * 返回意图执行的完整结果，包括：
 * - 识别到的意图信息
 * - 执行状态和结果
 * - 修改的数据详情
 * - 下一步操作建议
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentExecuteResponse {

    // ==================== 意图识别结果 ====================

    /**
     * 是否成功识别意图
     */
    private Boolean intentRecognized;

    /**
     * 识别到的意图代码
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
     * 敏感度级别
     */
    private String sensitivityLevel;

    /**
     * 匹配置信度 (0.0 - 1.0)
     */
    private Double confidence;

    /**
     * 匹配方法 (KEYWORD / LLM / REGEX)
     */
    private String matchMethod;

    // ==================== 执行状态 ====================

    /**
     * 执行状态
     * PENDING_APPROVAL - 等待审批
     * EXECUTING - 执行中
     * COMPLETED - 执行完成
     * FAILED - 执行失败
     * PREVIEW - 预览模式
     * NO_PERMISSION - 无权限
     * NOT_RECOGNIZED - 未识别到意图
     * NEED_MORE_INFO - 需要更多信息
     */
    private String status;

    /**
     * 状态消息
     */
    private String message;

    /**
     * 格式化的自然语言响应文本
     * 将 resultData 转换为用户友好的文本摘要
     * 例如: "查询到 25 条出货记录，今日发货 3 单，总金额 ¥55,000"
     */
    private String formattedText;

    /**
     * 澄清问题列表 (当 status=NEED_MORE_INFO 时返回)
     * 用于引导用户提供缺失的必需参数
     * LLM生成的自然语言问题，比硬编码消息更友好
     */
    private List<String> clarificationQuestions;

    /**
     * 缺失的参数列表 (技术元数据)
     * 提供结构化的参数信息，供前端生成表单或进一步处理
     */
    private List<MissingParameter> missingParameters;

    /**
     * 配额消耗
     */
    private Integer quotaCost;

    // ==================== Drools 规则验证 ====================

    /**
     * 规则验证违规列表
     * 仅在 status=VALIDATION_FAILED 时返回
     */
    private List<ValidationResult.Violation> validationViolations;

    /**
     * 规则验证建议列表
     * 来自 Drools 规则引擎的操作建议
     */
    private List<String> recommendations;

    // ==================== 执行结果 ====================

    /**
     * 执行结果数据
     * 根据意图类型不同，返回不同结构的数据
     * - FORM: 生成的表单Schema
     * - DATA_OP: 修改后的数据
     * - ANALYSIS: 分析结果
     */
    private Object resultData;

    /**
     * 受影响的实体列表
     */
    private List<AffectedEntity> affectedEntities;

    /**
     * 执行时间
     */
    private LocalDateTime executedAt;

    // ==================== 审批相关 ====================

    /**
     * 是否需要审批
     */
    private Boolean requiresApproval;

    /**
     * 审批请求ID (如果需要审批)
     */
    private String approvalRequestId;

    /**
     * 审批链ID
     */
    private String approvalChainId;

    // ==================== 下一步操作 ====================

    /**
     * 建议的下一步操作
     */
    private List<SuggestedAction> suggestedActions;

    /**
     * 可执行的确认操作 (预览模式下返回)
     */
    private ConfirmableAction confirmableAction;

    // ==================== 读写分块 (P1) ====================

    /**
     * READ|WRITE — 由 WriteGuardService 判定本次意图/工具是否写操作, 前端据此选卡片形态
     */
    private String aiMode;

    /**
     * PERMISSION_DENIED 时告知缺失的权限码 (module:action), 前端渲染「需要 X 权限」提示
     */
    private String requiredPermission;

    // ==================== 额外元数据 ====================

    /**
     * 额外元数据
     * 用于传递验证失败、调试信息等
     */
    private Map<String, Object> metadata;

    // ==================== 缓存相关 ====================

    /**
     * 是否来自缓存
     */
    private Boolean fromCache;

    /**
     * 缓存命中类型 (EXACT / SEMANTIC)
     */
    private String cacheHitType;

    // ==================== 对话续接 ====================

    /**
     * 会话ID
     * 当 status 为 NEED_MORE_INFO 或 NEED_CLARIFICATION 时返回
     * 前端使用此ID续接对话，提供更多信息
     */
    private String sessionId;

    /**
     * 当前对话轮次
     */
    private Integer conversationRound;

    /**
     * 最大对话轮次
     */
    private Integer maxConversationRounds;

    // ==================== 多意图执行结果 ====================

    /**
     * 是否为多意图执行结果
     * 当执行请求包含多个意图时为 true
     */
    private Boolean multiIntentResult;

    /**
     * 子意图执行结果列表
     * 当 multiIntentResult 为 true 时，包含每个子意图的执行结果
     */
    private List<SingleIntentResponse> subResults;

    /**
     * 结果验证结果
     * 包含验证是否通过、验证问题列表、建议等
     */
    private ValidationResultInfo validationResultInfo;

    // ==================== 嵌套类 ====================

    /**
     * 受影响的实体
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AffectedEntity {
        private String entityType;
        private String entityId;
        private String entityName;
        private String action; // CREATED, UPDATED, DELETED
        private Map<String, Object> changes; // 字段变更详情
    }

    /**
     * 建议的操作
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestedAction {
        private String actionCode;
        private String actionName;
        private String description;
        private String endpoint; // API端点
        private Map<String, Object> parameters;
    }

    /**
     * 可确认的操作 (预览模式)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfirmableAction {
        private String confirmToken; // 用于确认执行的token
        private String commandDigest;
        private Instant expiresAt;
        // Future Gateway correlation contract; not persisted or enforced as idempotency yet.
        private String requestId;
        private String idempotencyKey;
        private String description;
        private Integer expiresInSeconds;
        private Map<String, Object> previewData;
        // 写操作影响契约 (2026-07-24): 改前/改后对比 + 影响说明 + 风险档 —
        // 前端确认卡渲染对比表与二次确认弹窗的数据源。
        private Map<String, Object> currentValues;
        private Map<String, Object> newValues;
        private String impactSummary;
        private String actionType;   // READ/WRITE/UPDATE/DELETE/...
        private String riskLevel;    // LOW/MEDIUM/HIGH/CRITICAL
    }

    /**
     * 缺失参数信息
     * 当需要更多信息时，描述具体缺失了哪些参数及其详细信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MissingParameter {
        /**
         * 参数名称（技术名称，如 batchId）
         */
        private String parameterName;

        /**
         * 显示名称（用户友好，如 "批次编号"）
         */
        private String displayName;

        /**
         * 参数类型
         */
        private String parameterType; // STRING, NUMBER, BOOLEAN, DATE, ENUM

        /**
         * 是否必需
         */
        private Boolean required;

        /**
         * 参数说明
         */
        private String description;

        /**
         * 可能的值（用于枚举类型）
         */
        private List<String> possibleValues;

        /**
         * 验证规则提示（如：必须大于0，长度不超过20）
         */
        private String validationHint;
    }

    /**
     * 单意图执行响应
     * 用于多意图执行时记录每个子意图的执行结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SingleIntentResponse {
        /**
         * 意图代码
         */
        private String intentCode;

        /**
         * 意图名称
         */
        private String intentName;

        /**
         * 执行状态
         */
        private String status;

        /**
         * 执行结果数据
         */
        private Object data;

        /**
         * 状态消息
         */
        private String message;

        /**
         * 执行顺序
         */
        private Integer executionOrder;
    }

    /**
     * 验证结果信息
     * 简化版的验证结果，用于响应传递
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResultInfo {
        /**
         * 是否验证通过
         */
        private boolean valid;

        /**
         * 验证状态
         */
        private String status;

        /**
         * 验证问题列表
         */
        private List<ValidationIssueInfo> issues;

        /**
         * 建议信息
         */
        private String suggestion;

        /**
         * 规则验证分数 (0-1)
         */
        private double ruleScore;

        /**
         * 语义验证分数 (0-1)
         */
        private Double semanticScore;

        /**
         * 是否建议重试
         */
        private boolean shouldRetry;
    }

    /**
     * 验证问题信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationIssueInfo {
        /**
         * 问题类型
         */
        private String type;

        /**
         * 相关字段
         */
        private String field;

        /**
         * 期望值
         */
        private String expected;

        /**
         * 实际值
         */
        private String actual;

        /**
         * 问题描述
         */
        private String message;
    }
}
