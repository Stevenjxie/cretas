package com.cretas.aims.entity.factory;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 工厂仓库查询表 (P1-4, v1 §2.9 "双仓体系 - 物流仓 + 鲜棉仓").
 *
 * <p>客户原话 (会议 3225s):
 * <pre>
 * "我们这边是双仓体系, 物流仓 + 鲜棉仓, 鲜棉仓当天清仓, 不留原辅料"
 * </pre>
 *
 * <p><b>仅为查询表</b>: 提供 FactoryMaterialRequisition.sourceWarehouseId /
 * targetWarehouseId 字段的可读参考数据 (物流仓 / 鲜棉仓 等). 当前系统库存粒度
 * 仍为 factory 级 (MaterialBatch 无 warehouseId 字段), 此 entity 不参与 stock
 * query. 若未来引入 warehouse 维度库存管理 epic (见 docs/plans/p0-5-b2-warehouse-dimension-adr.md),
 * 此 entity 可扩展为真实仓库管理.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "factory_warehouses",
        indexes = {
                @Index(name = "idx_fw_factory", columnList = "factory_id"),
                @Index(name = "idx_fw_factory_type", columnList = "factory_id,type"),
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_fw_factory_code", columnNames = {"factory_id", "code"})
        }
)
@Where(clause = "deleted_at IS NULL")
public class FactoryWarehouse extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 仓库编码 (per-factory 唯一), 例如 "WH-LOG" / "WH-WKS". */
    @Column(name = "code", nullable = false, length = 32)
    private String code;

    /** 仓库名称, 例如 "物流仓" / "鲜棉仓". */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private WarehouseType type;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = Boolean.TRUE;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    /**
     * 仓库类型枚举.
     *
     * <p>Sprint 4 W1 W-CLASS-1: 扩 10 个阶段语义类型 (RAW/WIP/.../TRANSFER). 旧 3 类
     * (LOGISTICS/WORKSHOP/OTHER) 仅为 backwards-compat, 新仓库录入应使用阶段语义类型.
     */
    public enum WarehouseType {
        /** @deprecated W-CLASS-1: 用 RAW / FINISHED / TEMP 等阶段语义类型替代 */
        @Deprecated
        LOGISTICS,
        /** @deprecated W-CLASS-1: 用 WIP / LINESIDE 等阶段语义类型替代 */
        @Deprecated
        WORKSHOP,
        /** @deprecated W-CLASS-1: 显式指定阶段语义类型 */
        @Deprecated
        OTHER,
        /** 原料仓 - 存放采购入库的原辅料 */
        RAW,
        /** 在制品仓 - 存放未完工的中间产品 (WIP = work-in-progress) */
        WIP,
        /** 成品仓 - 存放完工待出库的成品 */
        FINISHED,
        /** 线边仓 - 紧邻产线的物料缓冲区 */
        LINESIDE,
        /** 退货仓 - 客户退货 / 供应商退回的物料 */
        RETURNS,
        /** 废料仓 - 报废 / 不合格物料隔离区 */
        SCRAP,
        /** 暂存仓 - 临时存放, 未分类 */
        TEMP,
        /** 质检仓 - 待检验 / 检验中的物料 */
        QC,
        /** 委外仓 - 委外加工方持有的物料 */
        OUTSOURCE,
        /** 调拨在途仓 - 跨厂 / 跨仓库调拨中物料 */
        TRANSFER,
        /** 盐化仓 - 盐水/腌制工序专属仓库，独立出量记录 (SP7 §3.1) */
        SALTED,
        /** 研发/中试库 - 试制批次 (is_trial=true) 产出专属仓库，不混入可售库存 (SP10 §RD-1) */
        RD
    }
}
