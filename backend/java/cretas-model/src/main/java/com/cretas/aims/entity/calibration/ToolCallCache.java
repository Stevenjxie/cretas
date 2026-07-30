package com.cretas.aims.entity.calibration;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 工具调用缓存实体
 * 用于冗余检测的短期缓存，支持相同参数调用的结果复用
 */
@Entity
@Table(name = "tool_call_cache",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_cache_key",
        columnNames = {"cache_key"}
    ),
    indexes = {
        @Index(name = "idx_session_id", columnList = "session_id"),
        @Index(name = "idx_expires_at", columnList = "expires_at"),
        @Index(name = "idx_tool_parameters", columnList = "tool_name, parameters_hash")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cache_key", length = 256, nullable = false)
    private String cacheKey;

    @Column(name = "session_id", length = 128, nullable = false)
    private String sessionId;

    @Column(name = "tool_name", length = 128, nullable = false)
    private String toolName;

    @Column(name = "parameters_hash", length = 64, nullable = false)
    private String parametersHash;

    @Column(name = "cached_result", columnDefinition = "JSON")
    private String cachedResult;

    @Column(name = "original_call_id", nullable = false)
    private Long originalCallId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "hit_count")
    @Builder.Default
    private Integer hitCount = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * 生成缓存键
     * 格式: session_id:tool_name:parameters_hash
     */
    public static String generateCacheKey(String sessionId, String toolName, String parametersHash) {
        return sessionId + ":" + toolName + ":" + parametersHash;
    }

    /**
     * 检查缓存是否过期
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * 增加命中次数
     */
    public void incrementHitCount() {
        this.hitCount = (this.hitCount == null ? 0 : this.hitCount) + 1;
    }

    /**
     * 延长过期时间
     */
    public void extendExpiration(int minutes) {
        this.expiresAt = LocalDateTime.now().plusMinutes(minutes);
    }
}
