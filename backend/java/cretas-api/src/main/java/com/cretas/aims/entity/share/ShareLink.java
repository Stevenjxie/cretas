package com.cretas.aims.entity.share;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 转发 / 分享链接 entity (PR #872 follow-up to #860).
 *
 * <p>NOT extending {@code BaseEntity} on purpose — share links are immutable
 * after creation (no updated_at needed), the PK is the token itself, and
 * {@code revoked_at} is a different lifecycle concept than soft-delete.
 *
 * <p>Created by admin via {@code POST /api/mobile/{factoryId}/share-links}; consumed
 * anonymously via {@code GET /api/mobile/public/share/{token}}.
 *
 * <p>Steve's 2026-05-18 decision: NO email service. Backend generates the
 * shareable URL; frontend dialog copies it to clipboard.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "share_links",
        indexes = {
                @Index(name = "idx_share_links_factory", columnList = "factory_id"),
                @Index(name = "idx_share_links_entity", columnList = "entity_type, entity_id")
        }
)
public class ShareLink implements Serializable {

    /** Secure-random base62 (~32 chars). URL-safe, primary key. */
    @Id
    @NotBlank
    @Column(name = "token", nullable = false, length = 64)
    private String token;

    @NotBlank
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 'PurchaseOrder' | 'SalesOrder' (matches Java simple class name). */
    @NotBlank
    @Column(name = "entity_type", nullable = false, length = 32)
    private String entityType;

    @NotBlank
    @Column(name = "entity_id", nullable = false, length = 191)
    private String entityId;

    /** Whether the public viewer sees price fields. Default TRUE per Steve's decision. */
    @NotNull
    @Column(name = "include_price", nullable = false)
    @Builder.Default
    private Boolean includePrice = Boolean.TRUE;

    /** Expiry timestamp; NULL = permanent. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** User id that created the share. */
    @NotNull
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Soft-revocation by admin (separate from natural expiry). */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private Integer viewCount = 0;

    @Column(name = "last_viewed_at")
    private LocalDateTime lastViewedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (viewCount == null) {
            viewCount = 0;
        }
        if (includePrice == null) {
            includePrice = Boolean.TRUE;
        }
    }

    /** Returns true if this link is past its expiry or has been revoked. */
    public boolean isExpiredOrRevoked() {
        if (revokedAt != null) {
            return true;
        }
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
}
