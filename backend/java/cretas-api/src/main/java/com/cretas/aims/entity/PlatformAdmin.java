package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.entity.enums.Status;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.persistence.*;
import java.time.LocalDateTime;
/**
 * 平台管理员实体
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "platform_admins", indexes = {
    @Index(name = "idx_username", columnList = "username", unique = true),
    @Index(name = "idx_email", columnList = "email"),
    @Index(name = "idx_status", columnList = "status")
})
public class PlatformAdmin {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /**
     * 用户名
     */
    @Column(nullable = false, unique = true, length = 50)
    private String username;
     /**
      * 密码哈希
      */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
     /**
      * 真实姓名
      */
    @Column(name = "real_name", nullable = false, length = 100)
    private String realName;
     /**
      * 邮箱
      */
    @Column(length = 255)
    private String email;
     /**
      * 手机号
      */
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;
     /**
      * 平台角色
      */
    @Enumerated(EnumType.STRING)
    @Column(name = "platform_role", nullable = false, length = 50)
    private PlatformRole platformRole;
     /**
      * 状态
      */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.active;
     /**
      * 最后登录时间
      */
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;
     /**
      * 最后登录IP
      */
    @Column(name = "last_login_ip", length = 45)
    private String lastLoginIp;
     /**
      * 创建时间
      */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
     /**
      * 更新时间
      */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
     /**
      * 备注
      */
    @Column(columnDefinition = "TEXT")
    private String remarks;
    // 便捷方法
     /**
      * 获取密码（用于兼容性）
      */
    public String getPassword() {
        return this.passwordHash;
    }
    /**
     * 获取权限列表
     */
    public String[] getPermissions() {
        return this.platformRole != null ? this.platformRole.getPermissions() : new String[0];
    }

    /**
     * 检查是否为超级管理员
     */
    public boolean isSuperAdmin() {
        return this.platformRole == PlatformRole.super_admin;
    }

    /**
     * 检查是否激活
     */
    public boolean isActive() {
        return this.status == Status.active;
    }
}
