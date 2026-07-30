package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.FactoryType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.Where;
/**
 * 工厂实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"users", "suppliers", "customers", "productionPlans", "materialBatches", "parent", "children"})
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "factories",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"name"})  // 工厂名称全局唯一
       },
       indexes = {
           @Index(name = "idx_factory_code", columnList = "industry_code, region_code, factory_year"),
           @Index(name = "idx_legacy_id", columnList = "legacy_id"),
           @Index(name = "idx_industry", columnList = "industry_code"),
           @Index(name = "idx_region", columnList = "region_code"),
           @Index(name = "idx_year", columnList = "factory_year"),
           @Index(name = "idx_name", columnList = "name")  // 加速工厂名称查询
       }
)
@Where(clause = "deleted_at IS NULL")
public class Factory extends BaseEntity {
    @Id
    @Column(name = "id", nullable = false)
    private String id;
    @Column(name = "name", nullable = false)
    private String name;

    // ==================== 组织类型与层级 (进销存通用化) ====================

    /**
     * 组织类型: FACTORY/RESTAURANT/HEADQUARTERS/BRANCH/CENTRAL_KITCHEN
     * 默认 FACTORY 保持向下兼容
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private FactoryType type = FactoryType.FACTORY;

    /**
     * 上级组织ID，用于总部-分店层级关系
     * 独立组织(FACTORY/RESTAURANT)此字段为null
     */
    @Column(name = "parent_id", length = 191)
    private String parentId;

    /**
     * 组织层级: 0=独立/集团 1=总部/品牌 2=区域 3=门店
     */
    @Column(name = "level", nullable = false)
    private Integer level = 0;

    // ==================== End 组织类型与层级 ====================

    @Column(name = "industry")
    private String industry;
    @Column(name = "address")
    private String address;
    @Column(name = "employee_count")
    private Integer employeeCount;
    @Column(name = "subscription_plan")
    private String subscriptionPlan;
    @Column(name = "survey_company_id")
    private String surveyCompanyId;
    @Column(name = "contact_name")
    private String contactName;
    @Column(name = "contact_phone")
    private String contactPhone;
    @Column(name = "contact_email")
    private String contactEmail;
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    @Column(name = "confidence")
    private Float confidence;
    @Column(name = "factory_year")
    private Integer factoryYear;
    @Column(name = "industry_code")
    private String industryCode;
    @Column(name = "inference_data", columnDefinition = "json")
    private String inferenceData;
    @Column(name = "legacy_id")
    private String legacyId;
    @Column(name = "manually_verified", nullable = false)
    private Boolean manuallyVerified = false;
    @Column(name = "region_code")
    private String regionCode;
    @Column(name = "sequence_number")
    private Integer sequenceNumber;
    @Column(name = "ai_weekly_quota", nullable = false)
    private Integer aiWeeklyQuota = 20;
    // 关联关系 (使用 CascadeType.PERSIST 防止级联删除，使用 @JsonIgnore 防止循环引用)
    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "factory", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private List<User> users = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "factory", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private List<Supplier> suppliers = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "factory", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private List<Customer> customers = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "factory", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private List<ProductionPlan> productionPlans = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "factory", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private List<MaterialBatch> materialBatches = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "factory", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private List<RawMaterialType> rawMaterialTypes = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "factory", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private List<ProductType> productTypes = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "factory", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private List<WorkType> workTypes = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "factory", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private List<FactoryEquipment> equipment = new ArrayList<>();

    // ==================== 组织层级关联 ====================

    /**
     * 上级组织（自引用）
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory parent;

    /**
     * 下级组织列表
     */
    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "parent", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private List<Factory> children = new ArrayList<>();

    // ==================== 辅助方法 ====================

    /**
     * 是否为独立运营组织（无层级关系）
     */
    @Transient
    public boolean isStandalone() {
        return type != null && type.isStandalone();
    }

    /**
     * 是否为总部
     */
    @Transient
    public boolean isHeadquarters() {
        return type != null && type.isHeadquarters();
    }

    /**
     * 是否有上级组织
     */
    @Transient
    public boolean hasParent() {
        return parentId != null;
    }
}
