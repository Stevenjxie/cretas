package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.Where;

/**
 * 生产工序Prompt配置实体
 *
 * 用于配置化管理不同生产工序的AI视觉检测提示词:
 * - PACKAGING - 包装工序
 * - SLICING - 切片工序
 * - TRIMMING - 修整工序
 * - WEIGHING - 称重工序
 * - LABELING - 贴标工序
 * 等
 *
 * 支持工厂级别和产品类型级别的覆盖配置:
 * - factoryId = null, productTypeId = null: 全局默认配置
 * - factoryId = 具体工厂ID, productTypeId = null: 工厂级配置
 * - factoryId = 具体工厂ID, productTypeId = 具体产品类型ID: 工厂+产品类型级配置
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-14
 */
@Entity
@Table(name = "production_process_prompt_configs",
       indexes = {
           @Index(name = "idx_process_prompt_factory_type", columnList = "factory_id, process_stage_type"),
           @Index(name = "idx_process_prompt_product", columnList = "factory_id, product_type_id"),
           @Index(name = "idx_process_prompt_active", columnList = "is_active"),
           @Index(name = "idx_process_prompt_priority", columnList = "priority"),
           @Index(name = "idx_process_prompt_effective", columnList = "effective_from, effective_to")
       })
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class ProductionProcessPromptConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ==================== 基础配置 ====================

    /**
     * 工厂ID（可选）
     * - null: 平台级配置（所有工厂默认）
     * - 具体工厂ID: 工厂级配置（覆盖平台配置）
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 产品类型ID（可选）
     * - null: 通用配置（适用于所有产品类型）
     * - 具体产品类型ID: 针对特定产品类型的配置
     */
    @Column(name = "product_type_id", length = 50)
    private String productTypeId;

    /**
     * 工序类型
     * PACKAGING - 包装
     * SLICING - 切片
     * TRIMMING - 修整
     * WEIGHING - 称重
     * LABELING - 贴标
     * INSPECTION - 检验
     * ASSEMBLY - 组装
     */
    @Column(name = "process_stage_type", nullable = false, length = 50)
    private String processStageType;

    /**
     * 配置名称（用于显示和识别）
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * 配置描述
     */
    @Column(name = "description", length = 500)
    private String description;

    // ==================== Prompt配置 ====================

    /**
     * 系统提示词
     * 定义AI的角色、背景知识和通用规则
     */
    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    /**
     * 完成动作检测提示词
     * 指导AI如何识别和判断工序完成动作
     * 支持变量占位符: {processType}, {expectedActions}, {confidenceLevel} 等
     */
    @Column(name = "completion_detection_prompt", columnDefinition = "TEXT")
    private String completionDetectionPrompt;

    /**
     * 预期的完成动作描述 (JSON数组)
     * 定义AI应该识别的具体完成动作
     * 如: ["封口完成", "标签贴附", "产品放入包装盒", "封箱完成"]
     */
    @Column(name = "expected_completion_actions", columnDefinition = "TEXT")
    private String expectedCompletionActions;

    /**
     * 期望的返回格式模板 (JSON)
     * 定义AI返回结果的结构
     * 如: {"completed": true, "action": "...", "confidence": 0.95, "details": {...}}
     */
    @Column(name = "response_format", columnDefinition = "TEXT")
    private String responseFormat;

    // ==================== AI参数 ====================

    /**
     * 使用的AI模型名称
     * 如: gpt-4-vision-preview, llm-vl, qwen-vl-max
     */
    @Column(name = "model_name", length = 100)
    @Builder.Default
    private String modelName = "llm-vl";

    /**
     * 温度参数 (0.0-2.0)
     * 控制AI回复的创造性，视觉检测通常使用较低值
     */
    @Column(name = "temperature", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal temperature = new BigDecimal("0.3");

    /**
     * 最大响应token数
     */
    @Column(name = "max_tokens")
    @Builder.Default
    private Integer maxTokens = 1000;

    /**
     * 置信度阈值
     * 低于此值的检测结果不计入完成计数
     */
    @Column(name = "confidence_threshold", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal confidenceThreshold = new BigDecimal("0.80");

    // ==================== 检测配置 ====================

    /**
     * 检测间隔（毫秒）
     * 两次检测之间的最小时间间隔
     */
    @Column(name = "detection_interval")
    @Builder.Default
    private Integer detectionInterval = 1000;

    /**
     * 防重复冷却时间（秒）
     * 识别到一次完成动作后，需要等待的冷却时间
     * 防止同一动作被重复计数
     */
    @Column(name = "cooldown_seconds")
    @Builder.Default
    private Integer cooldownSeconds = 3;

    /**
     * 最小置信度要求
     * 低于此值的结果直接丢弃，不做任何处理
     */
    @Column(name = "min_confidence", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal minConfidence = new BigDecimal("0.50");

    // ==================== 状态配置 ====================

    /**
     * 是否启用
     */
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 优先级（数值越高优先级越高）
     * 当同一工序类型有多个匹配配置时，使用优先级最高的
     */
    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 0;

    /**
     * 生效开始日期
     * null表示立即生效
     */
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /**
     * 生效结束日期
     * null表示永久有效
     */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    // ==================== 示例数据 ====================

    /**
     * 示例图片URL (JSON数组)
     * 用于测试和演示配置效果
     * 如: ["https://oss.../sample1.jpg", "https://oss.../sample2.jpg"]
     */
    @Column(name = "sample_image_urls", columnDefinition = "TEXT")
    private String sampleImageUrls;

    /**
     * 示例预期结果 (JSON)
     * 对应示例图片的预期检测结果
     * 如: [{"imageIndex": 0, "expected": {"completed": true, "action": "封口"}}]
     */
    @Column(name = "sample_expected_results", columnDefinition = "TEXT")
    private String sampleExpectedResults;

    // ==================== 辅助方法 ====================

    /**
     * 判断是否为全局配置
     */
    public boolean isGlobalConfig() {
        return factoryId == null && productTypeId == null;
    }

    /**
     * 判断是否为工厂级配置
     */
    public boolean isFactoryLevelConfig() {
        return factoryId != null && productTypeId == null;
    }

    /**
     * 判断是否为产品类型级配置
     */
    public boolean isProductTypeLevelConfig() {
        return factoryId != null && productTypeId != null;
    }

    /**
     * 判断配置是否在有效期内
     */
    public boolean isEffective() {
        if (!Boolean.TRUE.equals(isActive)) {
            return false;
        }
        LocalDate today = LocalDate.now();
        if (effectiveFrom != null && today.isBefore(effectiveFrom)) {
            return false;
        }
        if (effectiveTo != null && today.isAfter(effectiveTo)) {
            return false;
        }
        return true;
    }

    /**
     * 获取预期完成动作列表
     * 从 JSON 数组字符串解析为 List
     */
    @Transient
    public java.util.List<String> getExpectedCompletionActionsList() {
        if (expectedCompletionActions == null || expectedCompletionActions.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(expectedCompletionActions,
                mapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
        } catch (Exception e) {
            return java.util.Arrays.asList(expectedCompletionActions.replaceAll("[\\[\\]\"]", "").split(",\\s*"));
        }
    }

    /**
     * 设置预期完成动作列表
     * 将 List 转换为 JSON 数组字符串
     */
    @Transient
    public void setExpectedCompletionActionsList(java.util.List<String> actionList) {
        if (actionList == null || actionList.isEmpty()) {
            this.expectedCompletionActions = "[]";
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            this.expectedCompletionActions = mapper.writeValueAsString(actionList);
        } catch (Exception e) {
            this.expectedCompletionActions = "[\"" + String.join("\", \"", actionList) + "\"]";
        }
    }

    /**
     * 获取示例图片URL列表
     */
    @Transient
    public java.util.List<String> getSampleImageUrlsList() {
        if (sampleImageUrls == null || sampleImageUrls.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(sampleImageUrls,
                mapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }
}
