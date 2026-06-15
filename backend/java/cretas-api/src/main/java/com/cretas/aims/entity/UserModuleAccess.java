package com.cretas.aims.entity;

import com.cretas.aims.permission.PermissionLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "user_module_access",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_uma",
                columnNames = {"factory_id", "user_id", "module_code"}))
@Where(clause = "deleted_at IS NULL")
public class UserModuleAccess extends BaseEntity {

    public enum AccessType {
        GRANT,
        DENY
    }

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "module_code", nullable = false, length = 100)
    private String moduleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_type", nullable = false, length = 8)
    private AccessType accessType;

    @Column(name = "permission_level", length = 16)
    private String permissionLevel;

    @Column(name = "granted_by", length = 64)
    private String grantedBy;

    @Column(name = "remark", length = 500)
    private String remark;

    public PermissionLevel getEffectivePermissionLevel() {
        if (permissionLevel != null && !permissionLevel.isBlank()) {
            return PermissionLevel.fromAny(permissionLevel);
        }
        if (accessType == AccessType.GRANT) {
            return PermissionLevel.WRITE;
        }
        if (accessType == AccessType.DENY) {
            return PermissionLevel.HIDDEN;
        }
        return PermissionLevel.HIDDEN;
    }

    public void setEffectivePermissionLevel(PermissionLevel permissionLevel) {
        PermissionLevel normalized = permissionLevel == null ? PermissionLevel.HIDDEN : permissionLevel;
        this.permissionLevel = normalized.apiCode();
        this.accessType = normalized.canWrite() || normalized.canRead()
                ? AccessType.GRANT
                : AccessType.DENY;
    }

    public AccessType getAccessType() {
        PermissionLevel effective = getEffectivePermissionLevel();
        return effective == PermissionLevel.HIDDEN ? AccessType.DENY : AccessType.GRANT;
    }

    public void setAccessType(AccessType accessType) {
        this.accessType = accessType;
        if (accessType == AccessType.GRANT) {
            this.permissionLevel = PermissionLevel.WRITE.apiCode();
        } else {
            this.permissionLevel = PermissionLevel.HIDDEN.apiCode();
        }
    }

    @PrePersist
    @PreUpdate
    private void syncPermissionLevel() {
        if (permissionLevel == null || permissionLevel.isBlank()) {
            setAccessType(accessType == null ? AccessType.DENY : accessType);
            return;
        }

        PermissionLevel normalized = PermissionLevel.fromAny(permissionLevel);
        this.permissionLevel = normalized.apiCode();
        this.accessType = normalized == PermissionLevel.HIDDEN ? AccessType.DENY : AccessType.GRANT;
    }
}
