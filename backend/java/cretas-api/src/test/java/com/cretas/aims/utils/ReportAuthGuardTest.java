package com.cretas.aims.utils;

import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ReportAuthGuard 单元测试 — 纯静态工具, 无 Spring 依赖.
 *
 * <p>Covers:
 * <ul>
 *   <li>isSupervisor: 各角色码判断 (大小写不敏感 + null)
 *   <li>currentRole: 无请求上下文时返回 null (单元测试环境)
 *   <li>assertCanReport: null / 自己 / 他人 × supervisor 四场景
 * </ul>
 */
@DisplayName("ReportAuthGuard 鉴权守卫")
class ReportAuthGuardTest {

    // ==================== isSupervisor ====================

    @Test
    @DisplayName("isSupervisor: operator → false")
    void isSupervisor_operator_returnsFalse() {
        assertThat(ReportAuthGuard.isSupervisor("operator")).isFalse();
    }

    @Test
    @DisplayName("isSupervisor: factory_super_admin → true")
    void isSupervisor_factorySuperAdmin_returnsTrue() {
        assertThat(ReportAuthGuard.isSupervisor("factory_super_admin")).isTrue();
    }

    @Test
    @DisplayName("isSupervisor: workshop_supervisor → true")
    void isSupervisor_workshopSupervisor_returnsTrue() {
        assertThat(ReportAuthGuard.isSupervisor("workshop_supervisor")).isTrue();
    }

    @Test
    @DisplayName("isSupervisor: department_admin → true")
    void isSupervisor_departmentAdmin_returnsTrue() {
        assertThat(ReportAuthGuard.isSupervisor("department_admin")).isTrue();
    }

    @Test
    @DisplayName("isSupervisor: null → false")
    void isSupervisor_null_returnsFalse() {
        assertThat(ReportAuthGuard.isSupervisor(null)).isFalse();
    }

    @Test
    @DisplayName("isSupervisor: OPERATOR (uppercase) → false (不是主管)")
    void isSupervisor_upperCaseOperator_returnsFalse() {
        assertThat(ReportAuthGuard.isSupervisor("OPERATOR")).isFalse();
    }

    @Test
    @DisplayName("isSupervisor: Factory_Super_Admin (mixed case) → true (大小写不敏感)")
    void isSupervisor_mixedCaseSuperAdmin_returnsTrue() {
        assertThat(ReportAuthGuard.isSupervisor("Factory_Super_Admin")).isTrue();
    }

    @Test
    @DisplayName("isSupervisor: WORKSHOP_SUPERVISOR (uppercase) → true")
    void isSupervisor_upperCaseSupervisor_returnsTrue() {
        assertThat(ReportAuthGuard.isSupervisor("WORKSHOP_SUPERVISOR")).isTrue();
    }

    @Test
    @DisplayName("isSupervisor: empty string → false")
    void isSupervisor_empty_returnsFalse() {
        assertThat(ReportAuthGuard.isSupervisor("")).isFalse();
    }

    // ==================== currentRole ====================

    @Test
    @DisplayName("currentRole: 无请求上下文 (单元测试) → null")
    void currentRole_noRequestContext_returnsNull() {
        // JUnit5 runs without a Spring HTTP request → RequestContextHolder has no attributes
        assertThat(ReportAuthGuard.currentRole()).isNull();
    }

    // ==================== assertCanReport ====================

    @Test
    @DisplayName("assertCanReport: assignedTo=null (未指派) 任何人均可 → 不抛异常")
    void assertCanReport_unassigned_allowsAnyone() {
        assertThatCode(() -> ReportAuthGuard.assertCanReport(null, 1615L, false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertCanReport: assignedTo=self (本人任务) → 不抛异常")
    void assertCanReport_assignedToSelf_allows() {
        assertThatCode(() -> ReportAuthGuard.assertCanReport(1615L, 1615L, false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertCanReport: assignedTo=他人, 非主管 → BusinessException(403)")
    void assertCanReport_assignedToOther_nonSupervisor_throws403() {
        assertThatThrownBy(() -> ReportAuthGuard.assertCanReport(1616L, 1615L, false))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(403);
                    assertThat(be.getMessage()).contains("无权报工");
                });
    }

    @Test
    @DisplayName("assertCanReport: assignedTo=他人, 主管 → 不抛异常 (主管豁免)")
    void assertCanReport_assignedToOther_supervisor_allows() {
        assertThatCode(() -> ReportAuthGuard.assertCanReport(1616L, 1615L, true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertCanReport: actionHint 包含指引文本")
    void assertCanReport_exceptionContainsHint() {
        assertThatThrownBy(() -> ReportAuthGuard.assertCanReport(1616L, 1615L, false))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getActionHint()).isNotNull().contains("责任小组长");
                });
    }
}
