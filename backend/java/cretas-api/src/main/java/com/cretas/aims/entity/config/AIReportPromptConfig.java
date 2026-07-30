package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * AI报告提示词配置实体
 *
 * 用于配置化管理不同类型报告的AI提示词模板:
 * - daily - 日报
 * - weekly - 周报
 * - monthly - 月报
 * - quarterly - 季报
 * - yearly - 年报
 *
 * 支持工厂级别覆盖（factoryId = null 表示全局默认）
 *
 * @author Cretas Team
 * @version 1.1.0
 * @since 2026-01-08
 */
@Entity
@Table(name = "ai_report_prompt_configs",
       indexes = {
           @Index(name = "idx_report_prompt_factory_type", columnList = "factory_id, report_type"),
           @Index(name = "idx_report_prompt_active", columnList = "is_active"),
           @Index(name = "idx_report_prompt_priority", columnList = "priority")
       })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class AIReportPromptConfig extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    /**
     * 工厂ID（可选）
     * - null: 平台级配置（所有工厂默认）
     * - 具体工厂ID: 工厂级配置（覆盖平台配置）
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 报告类型
     * daily - 日报
     * weekly - 周报
     * monthly - 月报
     * quarterly - 季报
     * yearly - 年报
     */
    @Column(name = "report_type", nullable = false, length = 20)
    private String reportType;

    /**
     * 配置名称（用于显示）
     */
    @Column(name = "config_name", length = 100)
    private String configName;

    /**
     * 提示词模板
     * 支持变量占位符: {startDate}, {endDate}, {factoryName}, {reportPeriod} 等
     */
    @Column(name = "prompt_template", columnDefinition = "TEXT", nullable = false)
    private String promptTemplate;

    /**
     * 分析方向列表 (JSON数组)
     * 定义AI分析报告时应关注的重点方向
     * 如: ["成本分析", "产量趋势", "质量指标", "效率提升"]
     */
    @Column(name = "analysis_directions", columnDefinition = "JSON")
    private String analysisDirections;

    /**
     * 是否启用
     */
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 优先级（数值越高优先级越高）
     * 当同一报告类型有多个配置时，使用优先级最高的
     */
    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 0;

    /**
     * 配置描述
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 最大响应token数
     */
    @Column(name = "max_tokens")
    @Builder.Default
    private Integer maxTokens = 4000;

    /**
     * 温度参数 (0.0-2.0)
     * 控制AI回复的创造性
     */
    @Column(name = "temperature")
    @Builder.Default
    private Double temperature = 0.7;

    /**
     * 创建者用户名
     */
    @Column(name = "created_by", length = 50)
    private String createdBy;

    /**
     * 最后修改者用户名
     */
    @Column(name = "updated_by", length = 50)
    private String updatedBy;

    /**
     * 扩展元数据 (JSON)
     */
    @Column(name = "metadata", columnDefinition = "JSON")
    private String metadata;

    /**
     * 判断是否为全局配置
     */
    public boolean isGlobalConfig() {
        return factoryId == null;
    }

    /**
     * 获取分析方向列表
     * 从 JSON 数组字符串解析为 List
     */
    @Transient
    public java.util.List<String> getAnalysisDirectionsList() {
        if (analysisDirections == null || analysisDirections.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(analysisDirections,
                mapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
        } catch (Exception e) {
            return java.util.Arrays.asList(analysisDirections.replaceAll("[\\[\\]\"]", "").split(",\\s*"));
        }
    }

    /**
     * 设置分析方向列表
     * 将 List 转换为 JSON 数组字符串
     */
    @Transient
    public void setAnalysisDirectionsList(java.util.List<String> directionList) {
        if (directionList == null || directionList.isEmpty()) {
            this.analysisDirections = "[]";
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            this.analysisDirections = mapper.writeValueAsString(directionList);
        } catch (Exception e) {
            this.analysisDirections = "[\"" + String.join("\", \"", directionList) + "\"]";
        }
    }
}
