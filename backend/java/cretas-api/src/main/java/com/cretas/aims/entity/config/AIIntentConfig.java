package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * AI意图配置实体
 *
 * 用于配置化管理AI服务的意图识别:
 * - 定义不同类型的AI问题意图
 * - 配置敏感度级别和审批要求
 * - 管理角色权限和配额消耗
 * - 支持关键词匹配规则
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Entity
@Table(name = "ai_intent_configs",
       uniqueConstraints = @UniqueConstraint(columnNames = {"intent_code"}),
       indexes = {
           @Index(name = "idx_intent_category", columnList = "intent_category"),
           @Index(name = "idx_intent_sensitivity", columnList = "sensitivity_level"),
           @Index(name = "idx_intent_is_active", columnList = "is_active"),
           @Index(name = "idx_intent_factory_id", columnList = "factory_id")
       })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class AIIntentConfig extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    /**
     * 工厂ID（用于工厂级意图隔离）
     * - null: 平台级意图（所有工厂共享）
     * - 具体工厂ID: 工厂级意图（仅该工厂可见）
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 业态类型
     * COMMON - 通用意图（所有业态共享）
     * FACTORY - 工厂专属意图
     * RESTAURANT - 餐饮专属意图
     */
    @Column(name = "business_type", length = 30)
    @Builder.Default
    private String businessType = "COMMON";

    /**
     * 意图代码 (唯一标识)
     * 如: COST_ANALYSIS, BATCH_UPDATE, FORM_GENERATION, SCHEDULE_OPTIMIZATION
     */
    @Column(name = "intent_code", nullable = false, length = 50)
    private String intentCode;

    /**
     * 意图名称 (显示用)
     * 如: 成本分析, 批次更新, 表单生成, 排程优化
     */
    @Column(name = "intent_name", nullable = false, length = 100)
    private String intentName;

    /**
     * 意图分类
     * ANALYSIS - 数据分析类 (只读)
     * DATA_OP - 数据操作类 (可能修改数据)
     * FORM - 表单生成类
     * SCHEDULE - 排程相关
     * SYSTEM - 系统管理类
     */
    @Column(name = "intent_category", length = 50)
    private String intentCategory;

    /**
     * 敏感度级别
     * LOW - 低敏感 (普通查询)
     * MEDIUM - 中敏感 (业务分析)
     * HIGH - 高敏感 (数据修改)
     * CRITICAL - 关键操作 (需要审批)
     */
    @Column(name = "sensitivity_level", length = 20)
    @Builder.Default
    private String sensitivityLevel = "LOW";

    /**
     * 允许的角色列表 (JSON数组)
     * 如: ["factory_super_admin", "dispatcher", "manager"]
     * 空数组或null表示所有角色都可以
     */
    @Column(name = "required_roles", columnDefinition = "JSON")
    private String requiredRoles;

    /**
     * 权限码 (module:action, 如 inventory:write) — 与 ToolRbacEnforcer/HTTP @RequirePermission
     * 同一权限矩阵。非空时优先于 requiredRoles 判定; 空则回落 requiredRoles 旧逻辑 (兼容期)。
     */
    @Column(name = "required_permission", length = 64)
    private String requiredPermission;

    /**
     * 配额消耗值
     * 每次调用消耗的配额单位
     */
    @Column(name = "quota_cost")
    @Builder.Default
    private Integer quotaCost = 1;

    /**
     * 缓存有效期 (分钟)
     * 0 = 不缓存
     */
    @Column(name = "cache_ttl_minutes")
    @Builder.Default
    private Integer cacheTtlMinutes = 0;

    /**
     * 是否需要审批
     * 仅CRITICAL级别生效
     */
    @Column(name = "requires_approval")
    @Builder.Default
    private Boolean requiresApproval = false;

    /**
     * 审批链ID (关联ApprovalChainConfig)
     */
    @Column(name = "approval_chain_id", length = 36)
    private String approvalChainId;

    /**
     * 触发关键词列表 (JSON数组)
     * 用于意图识别的关键词匹配
     * 如: ["成本", "分析", "预算", "费用"]
     */
    @Column(name = "keywords", columnDefinition = "JSON")
    private String keywords;

    /**
     * 负向关键词列表 (JSON数组)
     * 匹配时会扣分，防止意图误匹配
     * 如: SCALE_DELETE 不应匹配 ["列表", "查看"]
     */
    @Column(name = "negative_keywords", columnDefinition = "JSON")
    private String negativeKeywords;

    /**
     * 负向关键词惩罚百分比 (0-100)
     * 每匹配一个负向词扣分 penalty/100
     */
    @Column(name = "negative_keyword_penalty")
    @Builder.Default
    private Integer negativeKeywordPenalty = 15;

    /**
     * 正则匹配规则 (可选)
     * 用于更精确的意图匹配
     */
    @Column(name = "regex_pattern", length = 500)
    private String regexPattern;

    /**
     * 意图描述
     * 用于 LLM Reranking 时提供语义理解上下文
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * 示例查询列表 (JSON数组)
     * 用于 LLM Reranking 时提供 Few-shot 示例
     * 如: ["查看今天的成本", "这周成本多少", "分析一下成本情况"]
     */
    @Column(name = "example_queries", columnDefinition = "JSON")
    private String exampleQueries;

    /**
     * 反例列表 (JSON数组)
     * 明确不应该匹配到此意图的表达
     * 如: ["删除成本记录", "修改成本数据"]
     */
    @Column(name = "negative_examples", columnDefinition = "JSON")
    private String negativeExamples;

    /**
     * 处理器类名 (可选 - 旧架构)
     * 用于路由到特定的处理方法
     * 如: CostAnalysisHandler, FormGenerationHandler
     * @deprecated 推荐使用 toolName 字段替代
     */
    @Deprecated
    @Column(name = "handler_class", length = 200)
    private String handlerClass;

    /**
     * 关联的 Tool 名称 (新 Tool Calling 架构)
     * 对应 ToolExecutor.getToolName() 返回的值
     * 如: material_batch_query, quality_check_create
     * 优先级高于 handlerClass
     */
    @Column(name = "tool_name", length = 100)
    private String toolName;

    /**
     * 最大响应token数
     */
    @Column(name = "max_tokens")
    @Builder.Default
    private Integer maxTokens = 2000;

    /**
     * 响应模板 (可选的Prompt模板)
     */
    @Column(name = "response_template", columnDefinition = "TEXT")
    private String responseTemplate;

    /**
     * 是否启用
     */
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 优先级 (用于多个意图匹配时的优先级)
     * 数值越高优先级越高
     */
    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 0;

    /**
     * 扩展元数据 (JSON)
     */
    @Column(name = "metadata", columnDefinition = "JSON")
    private String metadata;

    // ==================== SmartBI 专用字段 ====================

    /**
     * 推荐图表类型 (SmartBI)
     * 用于智能BI查询时推荐的可视化图表类型
     * 如: bar_chart, line_chart, pie_chart, table, card
     */
    @Column(name = "chart_type", length = 32)
    private String chartType;

    /**
     * 必需实体类型 (SmartBI)
     * JSON数组，定义该意图必须识别的实体类型
     * 如: ["time", "region", "metric"]
     */
    @Column(name = "required_entities", columnDefinition = "TEXT")
    private String requiredEntities;

    /**
     * 置信度提升值 (SmartBI)
     * 当识别到特定实体组合时，提升该意图的置信度
     * 范围: 0.00 - 1.00
     */
    @Column(name = "confidence_boost", columnDefinition = "DECIMAL(3,2)")
    @Builder.Default
    private java.math.BigDecimal confidenceBoost = java.math.BigDecimal.ZERO;

    /**
     * Legacy SmartBI regex-pattern list. Kept on the canonical entity so the
     * read-only compatibility API does not lose populated historical columns.
     */
    @Column(name = "patterns", columnDefinition = "JSON")
    private String patterns;

    /** Legacy SmartBI example list (distinct from canonical example_queries). */
    @Column(name = "examples", columnDefinition = "JSON")
    private String examples;

    /** Legacy SmartBI follow-up suggestions. */
    @Column(name = "follow_up_questions", columnDefinition = "JSON")
    private String followUpQuestions;

    /** Legacy SmartBI analysis service binding. */
    @Column(name = "analysis_service", length = 64)
    private String analysisService;

    /** Legacy SmartBI analysis method binding. */
    @Column(name = "method_name", length = 64)
    private String methodName;

    /** Legacy SmartBI matching threshold. */
    @Column(name = "confidence_threshold")
    @Builder.Default
    private Double confidenceThreshold = 0.6;

    // ==================== 版本控制字段 ====================

    /**
     * 配置版本号
     * 每次修改自动递增
     */
    @Column(name = "config_version")
    @Builder.Default
    private Integer configVersion = 1;

    /**
     * 上个版本的完整配置快照 (JSON)
     * 用于一键回滚
     */
    @Column(name = "previous_snapshot", columnDefinition = "JSON")
    private String previousSnapshot;

    // ==================== 语义层字段 ====================

    /**
     * L1 语义域
     * 业务领域分类: DATA, QUALITY, SCHEDULE, SCALE, SHIPMENT, FORM, META, SYSTEM
     */
    @Column(name = "semantic_domain", length = 30)
    private String semanticDomain;

    /**
     * L2 语义动作
     * 操作类型: QUERY, UPDATE, CREATE, DELETE, ANALYZE, EXECUTE, CONFIGURE, DETECT
     */
    @Column(name = "semantic_action", length = 30)
    private String semanticAction;

    /**
     * L3 语义对象
     * 操作目标: BATCH, PRODUCT, PLAN, MATERIAL, EQUIPMENT, USER, INTENT, etc.
     */
    @Column(name = "semantic_object", length = 50)
    private String semanticObject;

    /**
     * 语义路径 (计算字段)
     * 格式: {domain}.{action}.{object}
     * 例如: DATA.UPDATE.BATCH, QUALITY.EXECUTE.CHECK
     */
    @Column(name = "semantic_path", insertable = false, updatable = false, length = 100)
    private String semanticPath;

    /**
     * 判断是否需要管理员权限
     */
    public boolean requiresAdminRole() {
        return "HIGH".equals(sensitivityLevel) || "CRITICAL".equals(sensitivityLevel);
    }

    /**
     * 判断是否为数据修改类操作
     */
    public boolean isDataOperation() {
        return "DATA_OP".equals(intentCategory);
    }

    /**
     * 判断是否需要审批流程
     */
    public boolean needsApproval() {
        return Boolean.TRUE.equals(requiresApproval) && "CRITICAL".equals(sensitivityLevel);
    }

    /**
     * 获取关键词列表
     * 从 JSON 数组字符串解析为 List
     */
    public java.util.List<String> getKeywordsList() {
        if (keywords == null || keywords.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            // 解析 JSON 数组 ["关键词1", "关键词2"]
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(keywords,
                mapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
        } catch (Exception e) {
            // 如果解析失败，尝试按逗号分割
            return java.util.Arrays.asList(keywords.replaceAll("[\\[\\]\"]", "").split(",\\s*"));
        }
    }

    /**
     * 获取负向关键词列表
     *
     * <p>{@code @JsonIgnore} required: this is a derived getter (no setter), so Jackson's
     * default typing in Spring's Redis cache fails on deserialize ("setterless property,
     * no way to handle typed deser"). Without this, J2's commit 9a8d33068 added @Cacheable
     * on getAllIntents → 2nd call per request (or 2nd request after cache write) crashes
     * with org.springframework.data.redis.serializer.SerializationException → 500. Confirmed
     * fix 2026-04-25 (J3 task).
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public java.util.List<String> getNegativeKeywordsList() {
        if (negativeKeywords == null || negativeKeywords.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(negativeKeywords,
                mapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
        } catch (Exception e) {
            return java.util.Arrays.asList(negativeKeywords.replaceAll("[\\[\\]\"]", "").split(",\\s*"));
        }
    }

    /**
     * 设置关键词列表
     * 将 List 转换为 JSON 数组字符串
     */
    public void setKeywordsList(java.util.List<String> keywordList) {
        if (keywordList == null || keywordList.isEmpty()) {
            this.keywords = "[]";
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            this.keywords = mapper.writeValueAsString(keywordList);
        } catch (Exception e) {
            this.keywords = "[\"" + String.join("\", \"", keywordList) + "\"]";
        }
    }

    /**
     * 获取示例查询列表
     * 从 JSON 数组字符串解析为 List
     */
    public java.util.List<String> getExampleQueriesList() {
        if (exampleQueries == null || exampleQueries.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(exampleQueries,
                mapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
        } catch (Exception e) {
            return java.util.Arrays.asList(exampleQueries.replaceAll("[\\[\\]\"]", "").split(",\\s*"));
        }
    }

    /**
     * 设置示例查询列表
     * 将 List 转换为 JSON 数组字符串
     */
    public void setExampleQueriesList(java.util.List<String> exampleList) {
        if (exampleList == null || exampleList.isEmpty()) {
            this.exampleQueries = "[]";
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            this.exampleQueries = mapper.writeValueAsString(exampleList);
        } catch (Exception e) {
            this.exampleQueries = "[\"" + String.join("\", \"", exampleList) + "\"]";
        }
    }

    /**
     * 获取反例列表
     * 从 JSON 数组字符串解析为 List
     */
    public java.util.List<String> getNegativeExamplesList() {
        if (negativeExamples == null || negativeExamples.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(negativeExamples,
                mapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
        } catch (Exception e) {
            return java.util.Arrays.asList(negativeExamples.replaceAll("[\\[\\]\"]", "").split(",\\s*"));
        }
    }

    /**
     * 设置反例列表
     * 将 List 转换为 JSON 数组字符串
     */
    public void setNegativeExamplesList(java.util.List<String> negativeList) {
        if (negativeList == null || negativeList.isEmpty()) {
            this.negativeExamples = "[]";
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            this.negativeExamples = mapper.writeValueAsString(negativeList);
        } catch (Exception e) {
            this.negativeExamples = "[\"" + String.join("\", \"", negativeList) + "\"]";
        }
    }

    // ==================== SmartBI 辅助方法 ====================

    /**
     * 获取必需实体类型列表 (SmartBI)
     * 从 JSON 数组字符串解析为 List
     */
    public java.util.List<String> getRequiredEntitiesList() {
        if (requiredEntities == null || requiredEntities.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(requiredEntities,
                mapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
        } catch (Exception e) {
            return java.util.Arrays.asList(requiredEntities.replaceAll("[\\[\\]\"]", "").split(",\\s*"));
        }
    }

    /**
     * 设置必需实体类型列表 (SmartBI)
     * 将 List 转换为 JSON 数组字符串
     */
    public void setRequiredEntitiesList(java.util.List<String> entityList) {
        if (entityList == null || entityList.isEmpty()) {
            this.requiredEntities = "[]";
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            this.requiredEntities = mapper.writeValueAsString(entityList);
        } catch (Exception e) {
            this.requiredEntities = "[\"" + String.join("\", \"", entityList) + "\"]";
        }
    }

    /**
     * 判断是否为 SmartBI 意图
     */
    public boolean isSmartBIIntent() {
        return "SMARTBI".equals(intentCategory);
    }
}
