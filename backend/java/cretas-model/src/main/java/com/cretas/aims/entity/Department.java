package com.cretas.aims.entity;

import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;
/**
 * 部门实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "manager", "parentDepartment"})
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "departments",
       indexes = {
           @Index(name = "idx_department_factory", columnList = "factory_id"),
           @Index(name = "idx_department_code", columnList = "factory_id,code"),
           @Index(name = "idx_department_active", columnList = "factory_id,is_active")
       })
@Where(clause = "deleted_at IS NULL")
public class Department extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "code", length = 50)
    private String code;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "manager_user_id")
    private Long managerUserId;

    @Column(name = "parent_department_id")
    private Integer parentDepartmentId;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(name = "color", length = 20)
    private String color;

    @Column(name = "icon", length = 50)
    private String icon;

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_user_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User manager;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_department_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Department parentDepartment;
}
