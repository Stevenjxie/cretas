package com.cretas.aims.dto.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Skill定义DTO - 运行时技能表示
 *
 * <p>Represents a complete skill definition that can be loaded from either
 * the database (SmartBiSkill entity) or from SKILL.md files in the classpath.</p>
 *
 * <p>描述一个Skill的完整定义，包括：</p>
 * <ul>
 *   <li>基本信息 (名称、描述、版本)</li>
 *   <li>触发条件 (triggers)</li>
 *   <li>可用工具 (tools)</li>
 *   <li>上下文需求 (contextNeeded)</li>
 *   <li>提示模板 (promptTemplate)</li>
 * </ul>
 *
 * @author Cretas Team
 * @version 1.1.0
 * @since 2026-01-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDefinition {

    /**
     * Skill唯一标识名称
     * 例如: inventory-analysis, production-tracking
     */
    private String name;

    /**
     * 显示名称
     * 用于UI展示的友好名称
     * 例如: "库存分析", "生产追踪"
     */
    private String displayName;

    /**
     * Skill描述
     * 详细说明Skill的功能和适用场景
     */
    private String description;

    /**
     * 版本号
     * 用于版本管理和兼容性检查
     * 格式: 主版本.次版本.修订号 (如 1.0.0)
     */
    @Builder.Default
    private String version = "1.0.0";

    /**
     * 触发词列表
     * 用于匹配用户查询的关键词或短语
     * 例如: ["库存", "剩余多少", "还有多少"]
     */
    private List<String> triggers;

    /**
     * 可用工具列表
     * Skill执行时可调用的工具名称
     * 例如: ["inventory_query", "material_batch_search"]
     */
    private List<String> tools;

    /**
     * 所需上下文列表
     * Skill执行所需的上下文类型
     * 例如: ["factoryId", "dateRange", "productType"]
     */
    private List<String> contextNeeded;

    /**
     * 提示模板
     * 用于生成LLM提示的模板字符串
     * 支持变量替换: {{variableName}}
     */
    private String promptTemplate;

    /**
     * 配置参数
     * Skill的额外配置项
     * 例如: 超时时间、重试次数、默认值等
     */
    private Map<String, Object> config;

    /**
     * 是否启用
     * 控制Skill是否可被调用
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Priority order (lower = higher priority)
     * 优先级，数值越小优先级越高
     */
    @Builder.Default
    private Integer priority = 100;

    /**
     * Category for grouping skills
     * 分类，用于分组管理
     * 例如: "analytics", "alerting", "reporting"
     */
    private String category;

    /**
     * Required permission to use this skill
     * 使用此Skill所需的权限
     */
    private String requiredPermission;

    /**
     * 显式访问模式声明 (spec §8.2), 取值 "READ" / "WRITE"; null = 不声明, 完全按编排推导。
     *
     * <p><b>这个字段不是最终答案</b> —— 有效访问模式 =
     * {@code max(显式声明, 编排的全部 Tool 的最大值)}。声明只能<b>抬高</b>不能<b>压低</b>:
     * 一个编排了写 Tool 的 Skill 声明成 READ 也仍然是 WRITE。见
     * {@link com.cretas.aims.ai.tool.ToolAccessModes#resolveSkill}。
     */
    private String accessMode;

    /**
     * DAG执行图 (P0: 条件分支 + 错误恢复)
     * 定义工具执行的依赖关系和条件分支
     * 当非空时，覆盖 tools 列表的顺序执行模式
     */
    private List<ExecutionNode> executionGraph;

    /**
     * 错误处理策略
     * 控制工具执行失败时的行为
     */
    @Builder.Default
    private ErrorStrategy errorStrategy = ErrorStrategy.STOP;

    /**
     * Source of the skill definition
     * Skill定义来源
     * Either "database" or "file:{path}"
     */
    private String source;

    /**
     * Database ID if loaded from database
     * 如果从数据库加载，则为数据库ID
     * Null if loaded from file
     */
    private Long databaseId;

    /**
     * 检查用户查询是否匹配任一触发词
     *
     * @param userQuery 用户查询文本
     * @return 是否匹配
     */
    public boolean matchesTriggers(String userQuery) {
        if (triggers == null || triggers.isEmpty()) {
            return false;
        }
        String lowerQuery = userQuery.toLowerCase();
        return triggers.stream()
                .anyMatch(trigger -> lowerQuery.contains(trigger.toLowerCase()));
    }

    /**
     * 计算用户查询与触发词的匹配得分
     * 使用加权算法考虑匹配数量和覆盖率
     *
     * @param userQuery 用户查询文本
     * @return 匹配得分 (0.0 - 1.0)
     *         0.0 表示无匹配
     *         接近1.0 表示高度匹配
     */
    public double calculateMatchScore(String userQuery) {
        if (triggers == null || triggers.isEmpty() || userQuery == null) {
            return 0.0;
        }

        String lowerQuery = userQuery.toLowerCase();
        int matchedTriggers = 0;
        int totalMatchLength = 0;

        for (String trigger : triggers) {
            String lowerTrigger = trigger.toLowerCase();
            if (lowerQuery.contains(lowerTrigger)) {
                matchedTriggers++;
                totalMatchLength += lowerTrigger.length();
            }
        }

        if (matchedTriggers == 0) {
            return 0.0;
        }

        // Score based on: number of matched triggers and coverage of query
        double triggerRatio = (double) matchedTriggers / triggers.size();
        double coverageRatio = Math.min(1.0, (double) totalMatchLength / lowerQuery.length());

        // Weighted combination: 60% trigger ratio, 40% coverage
        return Math.min(1.0, (triggerRatio * 0.6) + (coverageRatio * 0.4));
    }

    /**
     * Check if this skill has the specified tool
     *
     * @param toolName the tool name
     * @return true if the skill uses this tool
     */
    public boolean hasTool(String toolName) {
        if (tools == null || tools.isEmpty() || toolName == null) {
            return false;
        }
        return tools.stream()
                .anyMatch(tool -> tool.equalsIgnoreCase(toolName));
    }

    /**
     * Get config value with default
     *
     * @param key the config key
     * @param defaultValue the default value
     * @param <T> the value type
     * @return the config value or default
     */
    @SuppressWarnings("unchecked")
    public <T> T getConfigValue(String key, T defaultValue) {
        if (config == null || !config.containsKey(key)) {
            return defaultValue;
        }
        try {
            return (T) config.get(key);
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    /**
     * Check if this skill requires a specific context field
     *
     * @param contextField the context field name
     * @return true if the skill needs this context
     */
    public boolean needsContext(String contextField) {
        if (contextNeeded == null || contextNeeded.isEmpty() || contextField == null) {
            return false;
        }
        return contextNeeded.stream()
                .anyMatch(ctx -> ctx.equalsIgnoreCase(contextField));
    }

    /**
     * Check if skill is from database
     *
     * @return true if loaded from database
     */
    public boolean isFromDatabase() {
        return "database".equals(source) || databaseId != null;
    }

    /**
     * Check if skill is from file
     *
     * @return true if loaded from file
     */
    public boolean isFromFile() {
        return source != null && source.startsWith("file:");
    }

    /**
     * Check if this skill has a DAG execution graph defined
     */
    public boolean hasExecutionGraph() {
        return executionGraph != null && !executionGraph.isEmpty();
    }

    /**
     * Get execution nodes that have no dependencies (entry points)
     */
    public List<ExecutionNode> getRootNodes() {
        if (!hasExecutionGraph()) return List.of();
        return executionGraph.stream()
                .filter(n -> n.getDependsOn() == null || n.getDependsOn().isEmpty())
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get execution nodes that depend on a given node
     */
    public List<ExecutionNode> getDependentNodes(String nodeId) {
        if (!hasExecutionGraph()) return List.of();
        return executionGraph.stream()
                .filter(n -> n.getDependsOn() != null && n.getDependsOn().contains(nodeId))
                .collect(java.util.stream.Collectors.toList());
    }

    // ==================== Inner Types ====================

    /**
     * 错误处理策略枚举
     */
    public enum ErrorStrategy {
        /** 首个失败即停止 (默认，向后兼容) */
        STOP,
        /** 失败后继续执行后续工具，收集部分结果 */
        CONTINUE_ON_ERROR,
        /** 失败后重试 (使用 ExecutionNode.maxRetries) */
        RETRY,
        /** 失败后使用备选工具 (使用 ExecutionNode.fallbackTool) */
        FALLBACK
    }

    /**
     * DAG执行图节点 — 描述一个工具的执行条件和依赖关系
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExecutionNode {
        /** 节点唯一标识 */
        private String id;

        /** 要执行的工具名称 */
        private String toolName;

        /** 依赖的前置节点ID列表 */
        private List<String> dependsOn;

        /**
         * 执行条件表达式
         * 支持的语法:
         * - "{nodeId}.success" — 前置节点是否成功
         * - "{nodeId}.data.{field}" — 访问前置节点结果数据
         * - "&&", "||", "!" — 布尔运算符
         * - 空或null — 无条件执行(仅需依赖完成)
         */
        private String condition;

        /** 工具执行参数 (覆盖/补充全局参数) */
        private Map<String, Object> params;

        /** 备选工具名称 (ErrorStrategy.FALLBACK 时使用) */
        private String fallbackTool;

        /** 最大重试次数 (ErrorStrategy.RETRY 时使用, 默认1) */
        @Builder.Default
        private int maxRetries = 1;

        /**
         * Check if this node has a condition
         */
        public boolean hasCondition() {
            return condition != null && !condition.trim().isEmpty();
        }

        /**
         * Check if this node has dependencies
         */
        public boolean hasDependencies() {
            return dependsOn != null && !dependsOn.isEmpty();
        }
    }
}
