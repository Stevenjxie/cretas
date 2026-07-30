package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.WhitelistStatus;
import com.cretas.aims.entity.enums.FactoryUserRole;
import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 白名单实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "addedByUser"})
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "whitelists",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"factory_id", "phone_number"})
       },
       indexes = {
           @Index(name = "idx_whitelist_factory", columnList = "factory_id"),
           @Index(name = "idx_whitelist_phone", columnList = "phone_number"),
           @Index(name = "idx_whitelist_status", columnList = "status"),
           @Index(name = "idx_whitelist_expires", columnList = "expires_at")
       }
)
@Where(clause = "deleted_at IS NULL")
public class Whitelist extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "name", length = 50)
    private String name;

    @Column(name = "department", length = 50)
    private String department;

    @Column(name = "position", length = 50)
    private String position;

    /**
     * 管理员邀请时指定的工厂角色。
     *
     * <p>为空表示历史白名单，仅允许完成传统“待管理员激活”注册；
     * 非空表示可信邀请，注册时由服务端赋予该角色并激活账号。</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "invited_role_code", length = 50)
    private FactoryUserRole invitedRoleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private WhitelistStatus status = WhitelistStatus.ACTIVE;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "added_by", nullable = false)
    private Long addedBy;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User addedByUser;

    // 辅助方法
    /**
     * 检查白名单是否有效
     */
    public boolean isValid() {
        // 状态必须是ACTIVE
        if (status != WhitelistStatus.ACTIVE) {
            return false;
        }
        // 检查是否过期
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            return false;
        }
        return true;
    }

    /**
     * 检查白名单是否即将过期（7天内）
     */
    public boolean isExpiringSoon() {
        if (expiresAt == null || status != WhitelistStatus.ACTIVE) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime warningDate = now.plusDays(7);
        return expiresAt.isAfter(now) && expiresAt.isBefore(warningDate);
    }
}
