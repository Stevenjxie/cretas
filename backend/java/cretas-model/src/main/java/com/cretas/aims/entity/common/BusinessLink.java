package com.cretas.aims.entity.common;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 通用业务单跨域关联 (Sprint 3 Track-F C-LINKARRAY-1).
 *
 * <p>对齐宏见 ERP 的 linkListArray 8 类 link_type:
 * sale / sample / request / produce / outsource / stock / project / free.
 *
 * <p>owner_* = 持有 link 的业务单 (e.g. ReturnOrder), target_* = 关联对象
 * (e.g. SalesOrder). 一个业务单可以挂 N 个 link.
 *
 * <p>查询 API 见 {@link com.cretas.aims.service.LinkArrayService}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "business_links",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"owner_type", "owner_id", "target_type", "target_id"})
        },
        indexes = {
                @Index(name = "idx_business_link_owner", columnList = "factory_id, owner_type, owner_id"),
                @Index(name = "idx_business_link_target", columnList = "factory_id, target_type, target_id"),
                @Index(name = "idx_business_link_type", columnList = "factory_id, link_type")
        }
)
@Where(clause = "deleted_at IS NULL")
public class BusinessLink extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (linkedAt == null) {
            linkedAt = LocalDateTime.now();
        }
    }

    @NotBlank
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 持有 link 的业务单类型: RETURN_ORDER / PRODUCTION_PLAN / SALES_ORDER ... */
    @NotBlank
    @Column(name = "owner_type", nullable = false, length = 64)
    private String ownerType;

    /** 持有 link 的业务单 ID */
    @NotBlank
    @Column(name = "owner_id", nullable = false, length = 191)
    private String ownerId;

    /** 关联维度: sale / sample / request / produce / outsource / stock / project / free */
    @NotBlank
    @Column(name = "link_type", nullable = false, length = 32)
    private String linkType;

    /** 关联对象类型: SALES_ORDER / PURCHASE_ORDER / PRODUCTION_PLAN ... */
    @NotBlank
    @Column(name = "target_type", nullable = false, length = 64)
    private String targetType;

    /** 关联对象 ID */
    @NotBlank
    @Column(name = "target_id", nullable = false, length = 191)
    private String targetId;

    @Column(name = "description", length = 255)
    private String description;

    /** 创建 link 的 userId */
    @Column(name = "linked_by", length = 64)
    private String linkedBy;

    @Column(name = "linked_at")
    private LocalDateTime linkedAt;
}
