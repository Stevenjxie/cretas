package com.cretas.aims.security;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    // ==================== Sprint 6 W2-B: chain resolution ====================

    /** BFS 最大深度, 防 reportsTo 环 (理论不该有) 或意外深嵌套. */
    private static final int MAX_CHAIN_DEPTH = 10;

    /**
     * Sprint 6 W2-B — 给定 DataScopeContext, 解析 created_by IN list 的 user ids.
     *
     * <ul>
     *   <li>SELF → [userId]</li>
     *   <li>SELF_AND_BELOW → BFS reportsTo chain (含自己)</li>
     *   <li>DEPT_AND_BELOW → 本部门所有 active user (MVP, Sprint 7 加 sub-department)</li>
     *   <li>ALL / CUSTOM → empty list (caller 不应调用, defensive)</li>
     * </ul>
     *
     * <p>失败 fallback: 返 [userId] (SELF semantics, fail-closed for scope tightening).
     */
    public List<Long> resolveCreatedByChain(DataScopeContext ctx) {
        if (ctx == null || ctx.getUserId() == null || ctx.getScope() == null) {
            return Collections.emptyList();
        }
        Long selfId = ctx.getUserId();
        String factoryId = ctx.getFactoryId();
        switch (ctx.getScope()) {
            case SELF:
                return List.of(selfId);
            case SELF_AND_BELOW:
                return resolveSelfAndBelowChain(factoryId, selfId);
            case DEPT_AND_BELOW:
                return resolveDeptChain(factoryId, ctx.getDepartment(), selfId);
            case ALL:
            case CUSTOM:
            default:
                return Collections.emptyList();
        }
    }

    /**
     * BFS down reportsTo links 从 selfId 开始, 含 self.
     * MAX_CHAIN_DEPTH 防御 — 不应有环, 但容错.
     */
    private List<Long> resolveSelfAndBelowChain(String factoryId, Long selfId) {
        Set<Long> visited = new HashSet<>();
        visited.add(selfId);
        Deque<Long> frontier = new ArrayDeque<>();
        frontier.add(selfId);
        int depth = 0;
        try {
            while (!frontier.isEmpty() && depth < MAX_CHAIN_DEPTH) {
                int levelSize = frontier.size();
                for (int i = 0; i < levelSize; i++) {
                    Long mgrId = frontier.poll();
                    if (mgrId == null) continue;
                    List<Long> subs = userRepository.findSubordinateIdsByReportsTo(factoryId, mgrId);
                    for (Long subId : subs) {
                        if (subId != null && visited.add(subId)) {
                            frontier.add(subId);
                        }
                    }
                }
                depth++;
            }
        } catch (Exception ex) {
            log.warn("DataScopeResolver: SELF_AND_BELOW chain resolution failed for user {}: {}, fallback [self]",
                    selfId, ex.getMessage());
            return List.of(selfId);
        }
        return new ArrayList<>(visited);
    }

    /**
     * DEPT_AND_BELOW MVP — 本部门所有 active user, 不递归 sub-department.
     * department null → fallback [selfId] (SELF semantics).
     */
    private List<Long> resolveDeptChain(String factoryId, String department, Long selfId) {
        if (department == null || department.isEmpty()) {
            log.debug("DataScopeResolver: DEPT_AND_BELOW with null department for user {}, fallback [self]", selfId);
            return List.of(selfId);
        }
        try {
            List<Long> deptUsers = userRepository.findUserIdsByDepartment(factoryId, department);
            // 总会包含 self (self 在自己部门), 但容错: ensure included
            if (!deptUsers.contains(selfId)) {
                List<Long> result = new ArrayList<>(deptUsers);
                result.add(selfId);
                return result;
            }
            return deptUsers;
        } catch (Exception ex) {
            log.warn("DataScopeResolver: DEPT_AND_BELOW chain resolution failed for user {} dept {}: {}, fallback [self]",
                    selfId, department, ex.getMessage());
            return List.of(selfId);
        }
    }
}
