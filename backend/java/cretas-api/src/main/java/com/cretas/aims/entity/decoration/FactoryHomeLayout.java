package com.cretas.aims.entity.decoration;

import com.cretas.aims.entity.BaseEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.Where;

/**
 * 工厂首页布局配置实体
 *
 * 管理App首页的模块布局、主题配置和时段布局:
 * - modules_config: 模块显示顺序和配置
 * - theme_config: 主题样式配置
 * - time_based_enabled: 是否启用时段布局
 * - usage_stats: 用户行为统计数据
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-14
 */
@Entity
@Table(name = "factory_home_layout",
       uniqueConstraints = @UniqueConstraint(columnNames = {"factory_id"}),
       indexes = {
           @Index(name = "idx_factory_home_layout_factory", columnList = "factory_id"),
           @Index(name = "idx_factory_home_layout_status", columnList = "status")
       })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class FactoryHomeLayout extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * 工厂ID (唯一)
     */
    @Column(name = "factory_id", nullable = false, unique = true, length = 50)
    private String factoryId;

    /**
     * 模块配置JSON
     * 包含模块列表、顺序、可见性等
     * 格式:
     * {
     *   "modules": [
     *     { "id": "today_stats", "visible": true, "order": 1, "colSpan": 2 },
     *     { "id": "quick_actions", "visible": true, "order": 2, "colSpan": 1 }
     *   ]
     * }
     */
    @Column(name = "modules_config", columnDefinition = "JSON", nullable = false)
    private String modulesConfig;

    /**
     * 主题配置JSON
     * 包含颜色方案、字体大小等
     * 格式:
     * {
     *   "primaryColor": "#1890ff",
     *   "backgroundColor": "#f5f5f5",
     *   "cardRadius": 8
     * }
     */
    @Column(name = "theme_config", columnDefinition = "JSON")
    private String themeConfig;

    /**
     * 状态: 0草稿 1发布
     */
    @Column(name = "status")
    @Builder.Default
    private Integer status = 1;

    /**
     * 版本号
     */
    @Column(name = "version")
    @Builder.Default
    private Integer version = 1;

    /**
     * 是否AI生成: 0否 1是
     */
    @Column(name = "ai_generated")
    @Builder.Default
    private Integer aiGenerated = 0;

    /**
     * AI生成时使用的提示词
     */
    @Column(name = "ai_prompt", columnDefinition = "TEXT")
    private String aiPrompt;

    /**
     * 创建人ID
     */
    @Column(name = "created_by")
    private Long createdBy;

    // ==================== Bento Grid 支持 ====================

    /**
     * 网格列数 (默认2列)
     */
    @Column(name = "grid_columns")
    @Builder.Default
    private Integer gridColumns = 2;

    // ==================== 时段布局 ====================

    /**
     * 是否启用时段布局: 0否 1是
     */
    @Column(name = "time_based_enabled")
    @Builder.Default
    private Integer timeBasedEnabled = 0;

    /**
     * 早间布局 (6-12点) JSON
     */
    @Column(name = "morning_layout", columnDefinition = "JSON")
    private String morningLayout;

    /**
     * 午间布局 (12-18点) JSON
     */
    @Column(name = "afternoon_layout", columnDefinition = "JSON")
    private String afternoonLayout;

    /**
     * 晚间布局 (18-24点) JSON
     */
    @Column(name = "evening_layout", columnDefinition = "JSON")
    private String eveningLayout;

    // ==================== 行为学习 ====================

    /**
     * 模块使用统计 JSON
     * 格式:
     * {
     *   "moduleClicks": { "today_stats": 120, "quick_actions": 85 },
     *   "lastUpdated": "2026-01-14T10:30:00"
     * }
     */
    @Column(name = "usage_stats", columnDefinition = "JSON")
    private String usageStats;

    /**
     * 上次建议时间
     */
    @Column(name = "last_suggestion_at")
    private LocalDateTime lastSuggestionAt;

    // ==================== 辅助方法 ====================

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 判断是否已发布
     */
    public boolean isPublished() {
        return Integer.valueOf(1).equals(status);
    }

    /**
     * 判断是否AI生成
     */
    public boolean isAiGenerated() {
        return Integer.valueOf(1).equals(aiGenerated);
    }

    /**
     * 判断是否启用时段布局
     */
    public boolean isTimeBasedEnabled() {
        return Integer.valueOf(1).equals(timeBasedEnabled);
    }

    /**
     * 获取模块配置Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getModulesConfigMap() {
        if (modulesConfig == null || modulesConfig.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(modulesConfig, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * 设置模块配置Map
     */
    public void setModulesConfigMap(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            this.modulesConfig = "{}";
            return;
        }
        try {
            this.modulesConfig = objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            this.modulesConfig = "{}";
        }
    }

    /**
     * 获取主题配置Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getThemeConfigMap() {
        if (themeConfig == null || themeConfig.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(themeConfig, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * 设置主题配置Map
     */
    public void setThemeConfigMap(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            this.themeConfig = "{}";
            return;
        }
        try {
            this.themeConfig = objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            this.themeConfig = "{}";
        }
    }

    /**
     * 获取当前时段的布局配置
     * 根据当前时间返回对应时段的布局
     */
    public String getCurrentTimeBasedLayout() {
        if (!isTimeBasedEnabled()) {
            return modulesConfig;
        }

        int hour = LocalDateTime.now().getHour();
        if (hour >= 6 && hour < 12) {
            return morningLayout != null ? morningLayout : modulesConfig;
        } else if (hour >= 12 && hour < 18) {
            return afternoonLayout != null ? afternoonLayout : modulesConfig;
        } else if (hour >= 18 && hour < 24) {
            return eveningLayout != null ? eveningLayout : modulesConfig;
        } else {
            // 0-6点使用默认布局
            return modulesConfig;
        }
    }
}
