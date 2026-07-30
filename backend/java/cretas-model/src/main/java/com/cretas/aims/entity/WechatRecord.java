package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.WechatDirection;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

/**
 * 微信记录 — 业务员手工补录的客户微信会话记录 (Sprint 6 W2-C-1, 2026-05-19).
 *
 * <p>背景: HJ Round 11 §F.2 finding — Customer 详情 "微信记录" tab.
 * F006 卤制品业务员场景: 跟客户微信聊单进度, 因微信 OAuth API 第三方限制,
 * 改用手工补录到 CRM 最实际.
 *
 * <p>跟 {@link CustomerTrackingRecord} 区别:
 * <ul>
 *   <li>CustomerTrackingRecord = 通用拜访 / 跟进记录 (有 trackingType=WECHAT 但不分方向)</li>
 *   <li>WechatRecord = 专门聊天流水, 必有 direction (INBOUND/OUTBOUND/INTERNAL)</li>
 * </ul>
 *
 * <p>软删除: deleted_at IS NULL via @Where + @SQLDelete.
 * factory_id RLS 列: 防租户串数据.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "wechat_records",
        indexes = {
                @Index(name = "idx_wechat_customer", columnList = "customer_id"),
                @Index(name = "idx_wechat_factory", columnList = "factory_id"),
                @Index(name = "idx_wechat_record_time", columnList = "record_time")
        })
@SQLDelete(sql = "UPDATE wechat_records SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class WechatRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "customer_id", nullable = false, length = 191)
    private String customerId;

    /** 微信记录时间 (业务员手工填或默认现在). */
    @Column(name = "record_time", nullable = false)
    private java.time.LocalDateTime recordTime;

    /**
     * 方向 enum (R3 dropdown): INBOUND / OUTBOUND / INTERNAL.
     * Controller 层也做白名单校验, entity 层用 EnumType.STRING 持久化便于 SQL grep.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    private WechatDirection direction;

    /** 消息内容 (必填, 业务员手工录入或粘贴微信聊天截图后整理). */
    @Column(name = "message_content", nullable = false, columnDefinition = "TEXT")
    private String messageContent;

    /** 创建人 ID (业务员). */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** 创建人显示名 (冗余字段, 避免列表渲染时再去 join users 表). */
    @Column(name = "created_by_name", length = 100)
    private String createdByName;

    /** 客户端联系人 (可选, 谁参与了对话). */
    @Column(name = "contact_person", length = 100)
    private String contactPerson;

    /** 备注 (可选). */
    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;
}
