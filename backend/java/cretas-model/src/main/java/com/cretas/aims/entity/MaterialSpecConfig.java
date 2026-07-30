package com.cretas.aims.entity;

import lombok.*;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * 原材料规格配置实体类
 *
 * 用于存储每个工厂的原材料类别对应的规格选项
 * 例如：海鲜类别 -> ["整条", "切片", "去骨切片"]
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-11-04
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "material_spec_configs",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"factory_id", "category"})
       },
       indexes = {
           @Index(name = "idx_spec_factory", columnList = "factory_id"),
           @Index(name = "idx_spec_category", columnList = "category")
       }
)
@Where(clause = "deleted_at IS NULL")
public class MaterialSpecConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "category", nullable = false, length = 50)
    private String category;

    /**
     * 规格选项列表，存储为JSON文本
     * 例如：["整条", "切片", "去骨切片", "鱼块", "鱼排"]
     * 注意：存储为TEXT，由Service层负责JSON序列化/反序列化
     */
    @Column(name = "specifications", nullable = false, columnDefinition = "TEXT")
    private String specifications;

    /**
     * 是否系统默认配置
     * true: 系统默认配置
     * false: 用户自定义配置
     */
    @Column(name = "is_system_default", nullable = false)
    private Boolean isSystemDefault = false;

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;
}
