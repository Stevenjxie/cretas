package com.cretas.aims.entity.permission;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

/**
 * Layer 1: Platform global default permission matrix.
 * role × module → rw/r/w/-
 *
 * See: docs/superpowers/specs/2026-04-18-permission-matrix-ai-driven-design.md §4.1
 * Seed: Flyway V20260419_01 (from PermissionServiceImpl.PERMISSION_MATRIX)
 */
@Entity
@Table(name = "platform_role_permissions",
    uniqueConstraints = @UniqueConstraint(name = "uk_role_module", columnNames = {"role_code", "module_code"}),
    indexes = @Index(name = "idx_platform_role_permissions_role", columnList = "role_code"))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@SuperBuilder
@Where(clause = "deleted_at IS NULL")
public class PlatformRolePermission extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_code", nullable = false, length = 64)
    private String roleCode;

    @Column(name = "module_code", nullable = false, length = 32)
    private String moduleCode;

    @Column(name = "permission_level", nullable = false, length = 8)
    private String permissionLevel;  // rw, r, w, -

    @Column(name = "updated_by")
    private Long updatedBy;
}
