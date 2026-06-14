package com.cretas.aims.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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

    @Column(name = "granted_by", length = 64)
    private String grantedBy;

    @Column(name = "remark", length = 500)
    private String remark;
}
