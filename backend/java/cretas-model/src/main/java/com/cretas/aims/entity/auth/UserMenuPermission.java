package com.cretas.aims.entity.auth;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Where;

/**
 * 用户级菜单权限覆盖 (P0-6 昆山六扇门)
 *
 * <p>在 role 默认权限基础上追加 (GRANT) 或撤销 (REVOKE) 特定菜单/权限 code。
 * 客户需求: 某员工只能看/操作被指定的菜单和数据,不够 role 级。
 *
 * <p>最终有效菜单 = (role 默认菜单 ∪ GRANT) - REVOKE
 *
 * @since 2026-04-08 P0-6
 */
@Entity
@Table(
        name = "user_menu_permissions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_menu",
                columnNames = {"factory_id", "user_id", "menu_code"}
        ),
        indexes = @Index(name = "idx_user_menu_perm_user", columnList = "factory_id,user_id")
)
@Getter
@Setter
@NoArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class UserMenuPermission extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    /** 菜单/权限 code,如 "production:read" / "sales.orders" */
    @Column(name = "menu_code", length = 128, nullable = false)
    private String menuCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "grant_type", length = 16, nullable = false)
    private GrantType grantType;

    @Column(name = "granted_by", length = 64)
    private String grantedBy;

    @Column(name = "remark", length = 500)
    private String remark;

    public enum GrantType {
        /** 追加授权 (role 没有,强制给) */
        GRANT,
        /** 撤销授权 (role 有,强制拿走) */
        REVOKE
    }
}
