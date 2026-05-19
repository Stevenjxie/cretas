package com.cretas.aims.security;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Sprint 5 Track G — RBAC 第 2 维 (数据权限) MVP unit test.
 *
 * <p>Covers:
 * <ol>
 *   <li>{@link DataScopeResolver} 解析 SELF / ALL / fallback / 异常路径</li>
 *   <li>{@link DataScopeContext} push/pop ThreadLocal 行为 + 嵌套 stack</li>
 *   <li>SELF scope 时 SalesService 分支判断 (filter 逻辑文档)</li>
 * </ol>
 *
 * <p>真实 service integration test (mvn -Pintegration) defer Sprint 6.
 *
 * @author Cretas Team (Sprint 5 Track G)
 * @since 2026-05-19
 */
@DisplayName("Sprint 5 Track G — RBAC DataScope 单测")
@ExtendWith(MockitoExtension.class)
class DataScopeFilterTest {

    @Mock UserRepository userRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks DataScopeResolver resolver;

    @AfterEach
    void cleanup() {
        // 防止测试间 ThreadLocal 泄露
        while (DataScopeContext.current() != null) {
            DataScopeContext.pop();
        }
    }

    private User user(Long id, String roleCode, String factoryId, String department) {
        User u = new User();
        u.setId(id);
        u.setRoleCode(roleCode);
        u.setFactoryId(factoryId);
        u.setDepartment(department);
        return u;
    }

    @Test
    @DisplayName("R1: null userId → resolver 返 null (匿名访问)")
    void resolve_nullUser_returnsNull() {
        assertNull(resolver.resolve(null));
        verifyNoInteractions(userRepository);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("R2: user 不存在 → resolver 返 null (caller fallback ALL)")
    void resolve_userNotFound_returnsNull() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertNull(resolver.resolve(99L));
    }

    @Test
    @DisplayName("R3: operator 一线员工 + DB 无 data_scope 列 → fallback SELF (hard default)")
    void resolve_operatorFallback_returnsSelf() {
        User u = user(10L, "operator", "F001", "production");
        when(userRepository.findById(10L)).thenReturn(Optional.of(u));
        // 模拟 DB 列不存在 (migration 未跑) 或 exception
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("operator")))
                .thenThrow(new EmptyResultDataAccessException(1));

        DataScopeContext ctx = resolver.resolve(10L);
        assertNotNull(ctx);
        assertEquals(DataScope.SELF, ctx.getScope(), "operator 一线员工硬默认 SELF");
        assertEquals(10L, ctx.getUserId());
        assertEquals("F001", ctx.getFactoryId());
        assertEquals("production", ctx.getDepartment());
        assertTrue(ctx.isFiltered(), "SELF scope 应触发过滤");
    }

    @Test
    @DisplayName("R4: factory_super_admin → ALL (硬默认, 即使 DB 无值)")
    void resolve_superAdmin_returnsAll() {
        User u = user(1L, "factory_super_admin", "F001", "all");
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("factory_super_admin")))
                .thenThrow(new EmptyResultDataAccessException(1));

        DataScopeContext ctx = resolver.resolve(1L);
        assertEquals(DataScope.ALL, ctx.getScope());
        assertFalse(ctx.isFiltered(), "ALL scope 不应过滤");
    }

    @Test
    @DisplayName("R5: DB 有 data_scope 列 + 值 → 优先 DB 值, 覆盖硬默认")
    void resolve_dbValueOverridesHardDefault() {
        User u = user(20L, "sales_manager", "F001", "sales");
        when(userRepository.findById(20L)).thenReturn(Optional.of(u));
        // sales_manager 硬默认 ALL, 但 DB 配为 DEPT_AND_BELOW
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("sales_manager")))
                .thenReturn("DEPT_AND_BELOW");

        DataScopeContext ctx = resolver.resolve(20L);
        assertEquals(DataScope.DEPT_AND_BELOW, ctx.getScope(),
                "DB 值优先于硬默认 (admin 可配)");
        assertEquals("sales", ctx.getDepartment(),
                "department 字段透传");
    }

    @Test
    @DisplayName("R6: DB 值无效 (typo) → 用硬默认 fallback, 不爆")
    void resolve_invalidDbValue_fallsBack() {
        User u = user(30L, "warehouse_worker", "F001", "warehouse");
        when(userRepository.findById(30L)).thenReturn(Optional.of(u));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("warehouse_worker")))
                .thenReturn("BOGUS_VALUE");  // 无效枚举

        DataScopeContext ctx = resolver.resolve(30L);
        assertEquals(DataScope.SELF, ctx.getScope(),
                "无效枚举 → fallback warehouse_worker 硬默认 SELF");
    }

    @Test
    @DisplayName("R7: ThreadLocal stack push/pop — 单层正常")
    void context_pushPop_singleLevel() {
        assertNull(DataScopeContext.current(), "初始无 context");
        DataScopeContext ctx = DataScopeContext.builder()
                .userId(100L)
                .scope(DataScope.SELF)
                .build();
        DataScopeContext.push(ctx);
        assertSame(ctx, DataScopeContext.current(), "push 后 current 返同一个");
        DataScopeContext.pop();
        assertNull(DataScopeContext.current(), "pop 后清空");
    }

    @Test
    @DisplayName("R8: ThreadLocal stack 嵌套 — 内层方法 push/pop 不影响外层")
    void context_pushPop_nested() {
        DataScopeContext outer = DataScopeContext.builder().userId(1L).scope(DataScope.ALL).build();
        DataScopeContext inner = DataScopeContext.builder().userId(2L).scope(DataScope.SELF).build();
        DataScopeContext.push(outer);
        DataScopeContext.push(inner);
        assertSame(inner, DataScopeContext.current(), "栈顶是 inner");
        DataScopeContext.pop();
        assertSame(outer, DataScopeContext.current(), "pop inner 后 outer 仍在");
        DataScopeContext.pop();
        assertNull(DataScopeContext.current());
    }

    @Test
    @DisplayName("R9: isFiltered 正确判定 (ALL=false, SELF/DEPT_AND_BELOW/SELF_AND_BELOW/CUSTOM=true)")
    void context_isFiltered_correctlyDeterminedByScope() {
        for (DataScope s : DataScope.values()) {
            DataScopeContext c = DataScopeContext.builder().scope(s).build();
            if (s == DataScope.ALL) {
                assertFalse(c.isFiltered(), s + " 应不过滤");
            } else {
                assertTrue(c.isFiltered(), s + " 应过滤");
            }
        }
    }
}
