package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.logistics.entity.enums.LocationStatus;
import com.cretas.aims.logistics.entity.enums.StoreMasterSource;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 门店主数据 — 门店坐标"解析一次, 逐日复用"(客户第一诉求)。
 *
 * <p>今天每次导入订单都对每一家门店重新按地址字符串 geocode (受
 * {@code LogisticsOrderImportServiceImpl.GEOCODE_ON_COMMIT_CAP} 逐次限流,
 * ~200 家门店天天重解析很多留 UNRESOLVED)。本表按 <b>门店名称</b>
 * (稳定跨天身份 —— 不是 {@link LogisticsDeliveryOrder#getStoreCode()}, 后者手动录入时是
 * 每天不同的 {@code SM-{date}-{seq}} 订单号, 不是门店身份) 落一条主数据行：一次解析/一次
 * 调度员手工修正之后, 后续所有导入直接复用坐标, 不再消耗 geocode 预算。
 *
 * <p><b>诚实降级</b>: 坐标缺失 (geocode 失败/尚未解析) 时 {@code longitude}/{@code latitude}
 * 保持 {@code null}, {@code locationStatus=UNRESOLVED} —— 绝不伪造坐标 (对齐 {@link
 * com.cretas.aims.logistics.service.routing.AmapClient} 类头诚实降级铁律)。
 *
 * <p>Maps to table {@code logistics_store_master} (V20261028_58)。同厂+同门店名称 只允许一条
 * 主数据行 (DB {@code uq_lsm_factory_name}, 部分索引 WHERE deleted_at IS NULL —
 * JPA 侧约束语义略严, 见 {@link LogisticsOrderBatch} 类注释同类说明)。
 *
 * <p>软删除: {@code deleted_at IS NULL} via @Where + @SQLDelete (per {@code BaseEntity} R68 note,
 * @SQLDelete on @MappedSuperclass 不可靠传播, 需在每个子类显式声明)。
 */
@Entity
@Table(name = "logistics_store_master",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_lsm_factory_name_jpa",
                        columnNames = {"factory_id", "store_name"})
        },
        indexes = {
                @Index(name = "idx_lsm_factory_area", columnList = "factory_id, area_code")
        })
@SQLDelete(sql = "UPDATE logistics_store_master SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsStoreMaster extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (locationStatus == null) {
            locationStatus = LocationStatus.UNRESOLVED;
        }
        if (source == null) {
            source = StoreMasterSource.GEOCODED;
        }
    }

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    /** 归一化门店名称 (trim + 折叠内部空白, 不 lowercase — 中文场景), 跨天稳定身份/查重键。 */
    @Column(name = "store_name", length = 256, nullable = false)
    private String storeName;

    @Column(name = "address", length = 512)
    private String address;

    @Column(name = "area_code", length = 64)
    private String areaCode;

    @Column(name = "longitude", precision = 11, scale = 7)
    private BigDecimal longitude;

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_status", length = 24, nullable = false)
    private LocationStatus locationStatus;

    /** 坐标来源 — GEOCODED (自动解析) / MANUAL (调度员修正) / IMPORT (导入行自带坐标)。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 24, nullable = false)
    private StoreMasterSource source;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
