package com.cretas.aims.entity;

import lombok.*;
import org.hibernate.annotations.GenericGenerator;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;
/**
 * 会话实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"user", "factory"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "sessions",
       indexes = {
           @Index(name = "idx_sessions_factory_id", columnList = "factory_id"),
           @Index(name = "idx_sessions_user_id", columnList = "user_id")
       }
)
@Where(clause = "deleted_at IS NULL")
public class Session extends BaseEntity {
    @Id
    @GeneratedValue(generator = "uuid")
    @GenericGenerator(name = "uuid", strategy = "uuid2")
    @Column(name = "id", nullable = false)
    private String id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "factory_id")
    private String factoryId;
    @Column(name = "token", nullable = false, unique = true, length = 500)
    private String token;
    @Column(name = "refresh_token", nullable = false, unique = true, length = 500)
    private String refreshToken;
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
    @Column(name = "is_revoked", nullable = false)
    private Boolean isRevoked = false;
    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User user;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;
}
