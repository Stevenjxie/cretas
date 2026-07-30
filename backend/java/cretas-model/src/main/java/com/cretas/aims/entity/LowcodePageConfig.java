package com.cretas.aims.entity;

import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * 低代码页面配置实体类
 * 存储页面的布局、主题、数据绑定等配置信息
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "lowcode_page_config",
       indexes = {
           @Index(name = "idx_page_factory", columnList = "factory_id"),
           @Index(name = "idx_page_type", columnList = "page_type"),
           @Index(name = "idx_page_status", columnList = "status")
       },
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"page_id", "factory_id", "role_code"})
       }
)
@Where(clause = "deleted_at IS NULL")
public class LowcodePageConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * 页面唯一标识
     */
    @Column(name = "page_id", nullable = false, length = 100)
    private String pageId;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 角色代码（用于角色级别的页面定制）
     */
    @Column(name = "role_code", length = 50)
    private String roleCode;

    /**
     * 页面类型
     * home - 首页
     * dashboard - 仪表盘
     * form - 表单页
     * list - 列表页
     * detail - 详情页
     */
    @Column(name = "page_type", nullable = false, length = 30)
    private String pageType;

    /**
     * 页面名称
     */
    @Column(name = "page_name", nullable = false, length = 100)
    private String pageName;

    /**
     * 布局配置JSON
     * 包含: grid, components, positions等
     */
    @Column(name = "layout_config", columnDefinition = "TEXT")
    private String layoutConfig;

    /**
     * 主题配置JSON
     * 包含: colors, fonts, spacing等
     */
    @Column(name = "theme_config", columnDefinition = "TEXT")
    private String themeConfig;

    /**
     * 数据绑定配置JSON
     * 包含: apiEndpoints, dataMapping, refreshInterval等
     */
    @Column(name = "data_bindings", columnDefinition = "TEXT")
    private String dataBindings;

    /**
     * 事件处理器配置JSON
     * 包含: onClick, onLoad, onSubmit等
     */
    @Column(name = "event_handlers", columnDefinition = "TEXT")
    private String eventHandlers;

    /**
     * 权限配置JSON
     * 包含: viewRoles, editRoles, deleteRoles等
     */
    @Column(name = "permissions", columnDefinition = "TEXT")
    private String permissions;

    /**
     * 状态
     * 0 - 草稿
     * 1 - 已发布
     */
    @Column(name = "status", nullable = false)
    @Builder.Default
    private Integer status = 0;

    /**
     * 版本号
     */
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    /**
     * 是否由AI生成
     * 0 - 否
     * 1 - 是
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
     * 父配置ID（用于版本管理或继承）
     */
    @Column(name = "parent_config_id")
    private Long parentConfigId;

    /**
     * 创建人ID
     */
    @Column(name = "created_by")
    private Long createdBy;
}
