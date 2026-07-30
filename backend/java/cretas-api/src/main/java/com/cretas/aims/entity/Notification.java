package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.NotificationType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 通知实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-26
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"user", "factory"})
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "notifications",
       indexes = {
           @Index(name = "idx_notification_factory", columnList = "factory_id"),
           @Index(name = "idx_notification_user", columnList = "user_id"),
           @Index(name = "idx_notification_factory_role", columnList = "factory_id, target_role"),
           @Index(name = "idx_notification_read", columnList = "is_read"),
           @Index(name = "idx_notification_type", columnList = "type")
       }
)
@Where(clause = "deleted_at IS NULL")
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id")
    private Long userId;  // 目标用户ID，null表示发送给工厂所有用户

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    @Builder.Default
    private NotificationType type = NotificationType.INFO;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "target_role", length = 50)
    private String targetRole;  // 目标角色代码 (e.g. WORKSHOP_SUPERVISOR), null = specific user or broadcast

    @Column(name = "source", length = 50)
    private String source;  // 来源：SYSTEM, ALERT, BATCH, QUALITY, etc.

    @Column(name = "source_id", length = 100)
    private String sourceId;  // 关联的业务ID

    @Column(name = "action_url", length = 500)
    private String actionUrl;  // 点击跳转的URL

    // 关联关系
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User user;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;
}
