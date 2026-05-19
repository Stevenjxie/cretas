package com.cretas.aims.security;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 根据 userId 从 DB 解析当前用户的 {@link DataScope} (RBAC 第 2 维, Sprint 5 Track G).
 *
 * <h3>解析逻辑</h3>
 * 优先级 (高 → 低):
 * <ol>
 *   <li>{@code role_definitions.data_scope} 列 (V20260519_05 migration 新加, per Role 配置)</li>
 *   <li>角色硬默认: factory_super_admin / platform_admin → ALL; 一线员工 → SELF; 其他 → ALL (兼容)</li>
 *   <li>未知角色 → ALL (fail-open, 不破坏现有行为)</li>
 * </ol>
 *
 * <p>Fail-open 设计理由: Phase 1 MVP 不能因为 scope 解析失败 break 现有功能. Sprint 6
 * sweep 时配合 audit 切 fail-closed.
 *
 * @author Cretas Team (Sprint 5 Track G)
 * @since 2026-05-19
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataScopeResolver {

    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 解析当前用户 scope. 返回 null 仅当 user 不存在 (caller 应 fallback ALL).
     *
     * @param userId 当前 user id (从 JWT). null 时返 null (匿名访问).
     * @return {@link DataScopeContext}; user 不存在返 null
     */
    public DataScopeContext resolve(Long userId) {
        if (userId == null) {
            return null;
        }
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            log.debug("DataScopeResolver: user {} not found, return null", userId);
            return null;
        }
        User user = userOpt.get();
        String roleCode = user.getRoleCode();
        DataScope scope = resolveScopeForRole(roleCode);

        return DataScopeContext.builder()
                .userId(userId)
                .factoryId(user.getFactoryId())
                .roleCode(roleCode)
                .scope(scope)
                .department(user.getDepartment())
                .column("created_by")
                .build();
    }

    /**
     * Lookup data_scope from {@code role_definitions} table. Fallback to hard-coded role defaults.
     *
     * <p>用 raw JdbcTemplate 而非 JPA — role_definitions 当前无 JPA entity, MVP 不引入新 entity.
     * Sprint 6 sweep 时考虑 promote 为 entity.
     */
    private DataScope resolveScopeForRole(String roleCode) {
        if (roleCode == null || roleCode.isEmpty()) {
            return DataScope.ALL;
        }

        // 优先查 role_definitions.data_scope 列
        try {
            String dbValue = jdbcTemplate.queryForObject(
                    "SELECT data_scope FROM role_definitions WHERE role_code = ? LIMIT 1",
                    String.class,
                    roleCode);
            if (dbValue != null && !dbValue.isEmpty()) {
                try {
                    return DataScope.valueOf(dbValue);
                } catch (IllegalArgumentException ex) {
                    log.warn("DataScopeResolver: role_definitions has invalid data_scope='{}' for role={}, fallback to default",
                            dbValue, roleCode);
                }
            }
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            // role 不在 role_definitions 表, 用硬默认
            log.debug("DataScopeResolver: role {} not in role_definitions, use hard default", roleCode);
        } catch (Exception ex) {
            // DB 异常 (e.g. 列不存在 — migration 未跑) → fail-open
            log.warn("DataScopeResolver: SQL error resolving role {}: {}", roleCode, ex.getMessage());
        }

        // Hard defaults based on role hierarchy
        FactoryUserRole role = FactoryUserRole.fromRoleCode(roleCode);
        switch (role) {
            case platform_admin:
            case factory_super_admin:
                return DataScope.ALL;
            case operator:
            case warehouse_worker:
            case quality_inspector:
                // 一线员工 → 自己创建的数据
                return DataScope.SELF;
            default:
                return DataScope.ALL;
        }
    }
}
