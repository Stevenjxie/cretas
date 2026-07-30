package com.cretas.aims.entity;

import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * 低代码组件定义实体类
 * 存储可用组件的元数据、属性schema和默认配置
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
@Table(name = "lowcode_component_definition",
       indexes = {
           @Index(name = "idx_component_type", columnList = "component_type"),
           @Index(name = "idx_component_category", columnList = "category"),
           @Index(name = "idx_component_status", columnList = "status"),
           @Index(name = "idx_component_factory", columnList = "factory_id")
       }
)
@Where(clause = "deleted_at IS NULL")
public class LowcodeComponentDefinition extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * 组件类型标识
     * 如: stats-card, chart-line, data-table等
     */
    @Column(name = "component_type", nullable = false, unique = true, length = 100)
    private String componentType;

    /**
     * 组件显示名称
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * 组件分类
     * 如: display, chart, form, layout等
     */
    @Column(name = "category", nullable = false, length = 50)
    private String category;

    /**
     * 组件图标
     */
    @Column(name = "icon", length = 100)
    private String icon;

    /**
     * 组件文件路径
     * 前端组件的相对路径
     */
    @Column(name = "component_path", length = 255)
    private String componentPath;

    /**
     * 属性Schema JSON
     * 定义组件可接受的所有属性及其类型
     */
    @Column(name = "props_schema", columnDefinition = "TEXT")
    private String propsSchema;

    /**
     * 默认属性配置JSON
     * 组件的默认属性值
     */
    @Column(name = "default_props", columnDefinition = "TEXT")
    private String defaultProps;

    /**
     * 尺寸约束JSON
     * 包含: minWidth, maxWidth, minHeight, maxHeight等
     */
    @Column(name = "size_constraints", columnDefinition = "TEXT")
    private String sizeConstraints;

    /**
     * 数据需求配置JSON
     * 定义组件需要的数据格式和来源
     */
    @Column(name = "data_requirements", columnDefinition = "TEXT")
    private String dataRequirements;

    /**
     * 支持的事件列表JSON
     * 如: ["onClick", "onHover", "onDataLoad"]
     */
    @Column(name = "supported_events", columnDefinition = "TEXT")
    private String supportedEvents;

    /**
     * AI描述
     * 用于AI理解和推荐组件的自然语言描述
     */
    @Column(name = "ai_description", columnDefinition = "TEXT")
    private String aiDescription;

    /**
     * 适用页面类型JSON
     * 如: ["home", "dashboard", "detail"]
     */
    @Column(name = "applicable_page_types", columnDefinition = "TEXT")
    private String applicablePageTypes;

    /**
     * 排序顺序
     */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * 状态
     * 0 - 禁用
     * 1 - 启用
     */
    @Column(name = "status", nullable = false)
    @Builder.Default
    private Integer status = 1;

    /**
     * 是否为系统组件
     * 0 - 否（自定义组件）
     * 1 - 是（系统内置组件）
     */
    @Column(name = "is_system")
    @Builder.Default
    private Integer isSystem = 0;

    /**
     * 所属工厂ID
     * 系统组件此字段为空，自定义组件关联工厂
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;
}
