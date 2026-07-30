package com.cretas.aims.entity.auth;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Where;

/**
 * 中央权限登记 (Sprint 4 Wave 2 Chat J — C-CHECKPOWER-1).
 *
 * <p>跨 controller 扫描 {@code @RequirePermission} annotation 自动注册的权限编码。
 * 也支持 admin API 手动新增 (source=MANUAL) 与种子数据 (source=SEED)。
 *
 * <p>用途:
 * <ul>
 *   <li>RBAC 审计 — 哪些权限被使用、哪些未使用、哪些声明但未被引用</li>
 *   <li>权限矩阵 export — ManifestExporter 导出 JSON 给运维 / 合规审计</li>
 *   <li>AIChat tool {@code permission_audit} — 按 role / module / factory 查权限分布</li>
 * </ul>
 *
 * <p>唯一索引 (permission_code, COALESCE(factory_id, '__GLOBAL__')) — 工厂级权限可覆盖全局。
 *
 * @since 2026-05-24
 */
@Entity
@Table(name = "permission_registry")
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionRegistry extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    /** 权限编码 module:action 形式, e.g. {@code production:read}. */
    @Column(name = "permission_code", nullable = false, length = 100)
    private String permissionCode;

    /** module 段 (permission_code split on ':' [0]). */
    @Column(name = "module", nullable = false, length = 50)
    private String module;

    /** action 段 (permission_code split on ':' [1]). */
    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 来源: ANNOTATION / MANUAL / SEED. */
    @Column(name = "source", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Source source = Source.ANNOTATION;

    /** Java class 全限定名 (ANNOTATION 来源时填). */
    @Column(name = "source_class", length = 255)
    private String sourceClass;

    /** Java method name (ANNOTATION 来源时填). */
    @Column(name = "source_method", length = 255)
    private String sourceMethod;

    /** NULL = 全局; 非 NULL = 工厂级权限. */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 权限注册来源。
     */
    public enum Source {
        /** 启动时反射扫描 {@code @RequirePermission} 自动注册。 */
        ANNOTATION,
        /** 通过 admin API 手动注册。 */
        MANUAL,
        /** 由 Flyway / data seed 注入。 */
        SEED
    }
}
