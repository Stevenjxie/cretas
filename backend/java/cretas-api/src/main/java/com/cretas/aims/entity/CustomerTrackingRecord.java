package com.cretas.aims.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

/**
 * 客户追踪记录 — 记录客户跟进/拜访/沟通历史
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "customer_tracking_records",
        indexes = {
                @Index(name = "idx_ctr_customer", columnList = "customer_id"),
                @Index(name = "idx_ctr_factory", columnList = "factory_id")
        })
@SQLDelete(sql = "UPDATE customer_tracking_records SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class CustomerTrackingRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "customer_id", nullable = false, length = 191)
    private String customerId;

    @Column(name = "record_time", nullable = false)
    private java.time.LocalDateTime recordTime;

    @Column(name = "recorder_name", length = 100)
    private String recorderName;

    @Column(name = "recorder_id")
    private Long recorderId;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "contact_person", length = 100)
    private String contactPerson;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    /**
     * Sprint 4 W1 S-CUSTOMER-TAB-1 R3 dropdown: 跟踪类型 enum.
     * Values: PHONE / WECHAT / EMAIL / VISIT / VIDEO / OTHER (validated at controller).
     */
    @Column(name = "tracking_type", length = 32)
    private String trackingType;
}
