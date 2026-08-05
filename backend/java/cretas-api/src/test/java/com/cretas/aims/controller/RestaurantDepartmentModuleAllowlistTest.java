package com.cretas.aims.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 四个部门 module 的白名单有两个承载点(L2 工厂级 / L1 平台级)。这组断言
 * 分别打这两处 —— 只改一个的话另一个会红。
 */
class RestaurantDepartmentModuleAllowlistTest {

    private static final List<String> DEPARTMENT_MODULES = List.of(
            "restaurantOps", "restaurantMarketing", "restaurantHr", "restaurantFinance");

    @Test
    @DisplayName("L2 工厂级覆盖接受四个部门 module")
    void factoryOverrideAcceptsDepartmentModules() {
        for (String module : DEPARTMENT_MODULES) {
            assertThatCode(() -> FactoryRoleModuleOverrideController.assertModuleAllowed(module))
                    .as("L2 应接受 %s", module)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("L1 平台级接受四个部门 module")
    void platformPermissionAcceptsDepartmentModules() {
        for (String module : DEPARTMENT_MODULES) {
            assertThatCode(() -> com.cretas.aims.controller.platform.PlatformRolePermissionController
                    .assertModuleAllowed(module))
                    .as("L1 应接受 %s", module)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("拼错的 module 名仍被拒 —— 白名单不是形同虚设")
    void typoStillRejected() {
        assertThatThrownBy(() -> FactoryRoleModuleOverrideController.assertModuleAllowed("restaurantops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> com.cretas.aims.controller.platform.PlatformRolePermissionController
                .assertModuleAllowed("restaurantHR"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
