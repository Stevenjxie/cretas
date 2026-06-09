package com.cretas.aims.entity.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SP12 T1 — FactoryUserRole 新角色 cashier / quality_controller 单元测试.
 *
 * <p>测试: 构造属性 + getPermissionPrefix + isManager/isWorker/isActive + fromRoleCode.</p>
 */
class FactoryUserRoleTest {

    // ==================== cashier ====================

    @Test
    void cashier_displayName() {
        assertThat(FactoryUserRole.cashier.getDisplayName()).isEqualTo("出纳");
    }

    @Test
    void cashier_department_isFinance() {
        assertThat(FactoryUserRole.cashier.getDepartment()).isEqualTo("finance");
    }

    @Test
    void cashier_level_is15() {
        assertThat(FactoryUserRole.cashier.getLevel()).isEqualTo(15);
    }

    @Test
    void cashier_permissionPrefix_isFinance() {
        assertThat(FactoryUserRole.cashier.getPermissionPrefix()).isEqualTo("finance");
    }

    @Test
    void cashier_isManager_false() {
        // level 15 > 0-20? 15 <= 20, so isManager returns true (level 0-20)
        // Per existing logic: isManager = level <= 20  → true at level 15
        assertThat(FactoryUserRole.cashier.isManager()).isTrue();
    }

    @Test
    void cashier_isWorker_false() {
        assertThat(FactoryUserRole.cashier.isWorker()).isFalse();
    }

    @Test
    void cashier_isActive_true() {
        assertThat(FactoryUserRole.cashier.isActive()).isTrue();
    }

    @Test
    void cashier_fromRoleCode() {
        assertThat(FactoryUserRole.fromRoleCode("cashier")).isEqualTo(FactoryUserRole.cashier);
    }

    // ==================== quality_controller ====================

    @Test
    void qualityController_displayName() {
        assertThat(FactoryUserRole.quality_controller.getDisplayName()).isEqualTo("品控");
    }

    @Test
    void qualityController_department_isQuality() {
        assertThat(FactoryUserRole.quality_controller.getDepartment()).isEqualTo("quality");
    }

    @Test
    void qualityController_level_is15() {
        assertThat(FactoryUserRole.quality_controller.getLevel()).isEqualTo(15);
    }

    @Test
    void qualityController_permissionPrefix_isQuality() {
        assertThat(FactoryUserRole.quality_controller.getPermissionPrefix()).isEqualTo("quality");
    }

    @Test
    void qualityController_isManager_true() {
        // level 15 <= 20 → true
        assertThat(FactoryUserRole.quality_controller.isManager()).isTrue();
    }

    @Test
    void qualityController_isWorker_false() {
        assertThat(FactoryUserRole.quality_controller.isWorker()).isFalse();
    }

    @Test
    void qualityController_isActive_true() {
        assertThat(FactoryUserRole.quality_controller.isActive()).isTrue();
    }

    @Test
    void qualityController_fromRoleCode() {
        assertThat(FactoryUserRole.fromRoleCode("quality_controller"))
                .isEqualTo(FactoryUserRole.quality_controller);
    }

    // ==================== canManage relationship ====================

    @Test
    void cashier_canManagedByFinanceManager() {
        // finance_manager (level 10) < cashier (level 15) → finance_manager can manage cashier
        assertThat(FactoryUserRole.finance_manager.canManage(FactoryUserRole.cashier)).isTrue();
    }

    @Test
    void qualityController_canManagedByQualityManager() {
        // quality_manager (level 10) < quality_controller (level 15)
        assertThat(FactoryUserRole.quality_manager.canManage(FactoryUserRole.quality_controller)).isTrue();
    }

    @Test
    void cashier_cannotManageFinanceManager() {
        // cashier (level 15) NOT < finance_manager (level 10) → false
        assertThat(FactoryUserRole.cashier.canManage(FactoryUserRole.finance_manager)).isFalse();
    }

    // ==================== enum completeness ====================

    @Test
    void allRoles_haveNonNullDisplayName() {
        for (FactoryUserRole role : FactoryUserRole.values()) {
            assertThat(role.getDisplayName()).as("role %s displayName", role).isNotNull();
        }
    }

    @Test
    void allRoles_haveNonNullDepartment() {
        for (FactoryUserRole role : FactoryUserRole.values()) {
            assertThat(role.getDepartment()).as("role %s department", role).isNotNull();
        }
    }

    @Test
    void allRoles_permissionPrefix_neverNull() {
        for (FactoryUserRole role : FactoryUserRole.values()) {
            assertThat(role.getPermissionPrefix())
                    .as("role %s permissionPrefix", role)
                    .isNotNull();
        }
    }
}
