package com.cretas.aims.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 标签实体类
 * 用于管理产品追溯标签、条码、二维码等
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Data
@Entity
@Table(name = "labels")
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class Label extends BaseEntity {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 20)
    private String factoryId;

    /**
     * 标签编码（唯一标识）
     */
    @Column(name = "label_code", nullable = false, unique = true, length = 100)
    private String labelCode;

    /**
     * 标签类型: BARCODE/QR_CODE/RFID/NFC
     */
    @Column(name = "label_type", length = 20)
    private String labelType;

    /**
     * 关联的批次类型: PRODUCTION/MATERIAL/SHIPMENT
     */
    @Column(name = "batch_type", length = 20)
    private String batchType;

    /**
     * 关联的批次ID
     */
    @Column(name = "batch_id", length = 50)
    private String batchId;

    /**
     * 关联的生产批次ID（如果是生产批次）
     */
    @Column(name = "production_batch_id")
    private Long productionBatchId;

    /**
     * 追溯码
     */
    @Column(name = "trace_code", length = 100)
    private String traceCode;

    /**
     * 二维码内容/URL
     */
    @Column(name = "qr_content", length = 500)
    private String qrContent;

    /**
     * 标签状态: ACTIVE/PRINTED/APPLIED/VOIDED
     */
    @Column(name = "status", length = 20)
    private String status;

    /**
     * 打印次数
     */
    @Column(name = "print_count")
    private Integer printCount;

    /**
     * 最后打印时间
     */
    @Column(name = "last_printed_at")
    private LocalDateTime lastPrintedAt;

    /**
     * 打印人ID
     */
    @Column(name = "printed_by")
    private Long printedBy;

    /**
     * 贴标时间
     */
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    /**
     * 贴标人ID
     */
    @Column(name = "applied_by")
    private Long appliedBy;

    /**
     * 产品名称
     */
    @Column(name = "product_name", length = 200)
    private String productName;

    /**
     * 规格
     */
    @Column(name = "specification", length = 100)
    private String specification;

    /**
     * 生产日期
     */
    @Column(name = "production_date")
    private LocalDateTime productionDate;

    /**
     * 保质期（天）
     */
    @Column(name = "shelf_life_days")
    private Integer shelfLifeDays;

    /**
     * 过期日期
     */
    @Column(name = "expiry_date")
    private LocalDateTime expiryDate;

    /**
     * 扩展数据（JSON格式）
     */
    @Column(name = "extra_data", columnDefinition = "TEXT")
    private String extraData;

    /**
     * 创建者ID
     */
    @Column(name = "created_by")
    private Long createdBy;
}
