package com.cretas.aims.entity.pos;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.PosBrand;
import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * POS系统连接配置
 *
 * 每个工厂/门店可配置一个或多个POS品牌的连接。
 * 连接凭证按品牌不同：
 * - 客如云(OAuth2): appKey + appSecret + accessToken + refreshToken
 * - 二维火(SHA1): appKey + appSecret
 * - 银豹(HMAC): appKey + appSecret
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "pos_connections",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pos_factory_brand", columnNames = {"factory_id", "brand"})
        },
        indexes = {
                @Index(name = "idx_posc_factory", columnList = "factory_id"),
                @Index(name = "idx_posc_brand", columnList = "brand"),
                @Index(name = "idx_posc_active", columnList = "is_active")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PosConnection extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "brand", nullable = false, length = 32)
    private PosBrand brand;

    /** 连接名称（用户自定义，如"一楼前台POS"） */
    @Column(name = "connection_name", length = 200)
    private String connectionName;

    /** POS平台分配的应用Key */
    @Column(name = "app_key", length = 200)
    private String appKey;

    /** POS平台分配的应用密钥 */
    @Column(name = "app_secret", length = 500)
    private String appSecret;

    /** OAuth2 Access Token（客如云等） */
    @Column(name = "access_token", length = 1000)
    private String accessToken;

    /** OAuth2 Refresh Token */
    @Column(name = "refresh_token", length = 1000)
    private String refreshToken;

    /** Token过期时间 */
    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    /** Webhook签名密钥（用于验证回调） */
    @Column(name = "webhook_secret", length = 200)
    private String webhookSecret;

    /** POS平台的门店/商户ID */
    @Column(name = "pos_store_id", length = 100)
    private String posStoreId;

    /** 是否启用 */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /** 上次成功同步时间 */
    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    /** 上次同步错误信息 */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "remark", length = 500)
    private String remark;
}
