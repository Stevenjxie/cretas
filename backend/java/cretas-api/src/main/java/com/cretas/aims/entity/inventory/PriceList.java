package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.Factory;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 价格表（总部统一定价）
 * 总部为下属门店/工厂设定标准采购价/调拨价
 * 支持按时间段生效
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "items"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "price_lists",
        indexes = {
                @Index(name = "idx_pl_factory", columnList = "factory_id"),
                @Index(name = "idx_pl_effective", columnList = "effective_from,effective_to"),
                @Index(name = "idx_pl_customer", columnList = "factory_id,customer_id,price_type")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PriceList extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** 总部factoryId（发布价格表的组织） */
    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** 客户ID（null=全局价格表，非null=客户专属价格） */
    @Column(name = "customer_id", length = 191)
    private String customerId;

    /** 价格表类型: PURCHASE_PRICE / TRANSFER_PRICE / SELLING_PRICE */
    @Column(name = "price_type", nullable = false, length = 32)
    private String priceType;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** 是否启用 */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @OneToMany(mappedBy = "priceList", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PriceListItem> items = new ArrayList<>();

    // ==================== 计算属性 ====================

    @Transient
    public boolean isCurrentlyEffective() {
        LocalDate now = LocalDate.now();
        boolean afterStart = !now.isBefore(effectiveFrom);
        boolean beforeEnd = effectiveTo == null || !now.isAfter(effectiveTo);
        return isActive && afterStart && beforeEnd;
    }
}
