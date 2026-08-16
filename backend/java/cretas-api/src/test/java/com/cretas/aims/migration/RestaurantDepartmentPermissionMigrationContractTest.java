package com.cretas.aims.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住迁移写入的权限形状。断言的是「每个角色只有一个部门是 rw」这个不变量,
 * 不是字符串出现次数 —— 后者改个格式就绕过去了。
 */
class RestaurantDepartmentPermissionMigrationContractTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/flyway/V20261029_52__restaurant_department_module_permissions.sql");

    private static final String[] DEPARTMENTS = {
        "restaurantOps", "restaurantMarketing", "restaurantHr", "restaurantFinance" };

    @Test
    @DisplayName("每个载体角色恰好一个部门是 rw, 其余三个是 '-'")
    void eachRoleOwnsExactlyOneDepartment() throws Exception {
        String sql = Files.readString(MIGRATION);
        record Expected(String role, String ownedDepartment) {}
        var cases = new Expected[] {
            new Expected("restaurant_manager", "restaurantOps"),
            new Expected("sales_manager",      "restaurantMarketing"),
            new Expected("finance_manager",    "restaurantFinance"),
            new Expected("hr_admin",           "restaurantHr"),
        };
        for (var c : cases) {
            for (String dept : DEPARTMENTS) {
                String expectedLevel = dept.equals(c.ownedDepartment()) ? "rw" : "-";
                assertThat(rowLevel(sql, c.role(), dept))
                        .as("%s 对 %s 应为 %s", c.role(), dept, expectedLevel)
                        .isEqualTo(expectedLevel);
            }
        }
    }

    @Test
    @DisplayName("四个载体角色的 restaurant 上限都是 rw —— 上限是 '-' 时细分声明全部失效")
    void ceilingIsReadWriteForAllCarriers() throws Exception {
        String sql = Files.readString(MIGRATION);
        for (String role : new String[]{"restaurant_manager","sales_manager","finance_manager","hr_admin"}) {
            assertThat(rowLevel(sql, role, "restaurant"))
                    .as("%s 的 restaurant 上限", role)
                    .isEqualTo("rw");
        }
    }

    @Test
    @DisplayName("不碰 restaurant_owner / restaurant_chef —— 它们是本轮明确排除的既有问题")
    void doesNotTouchOwnerOrChef() throws Exception {
        String sql = Files.readString(MIGRATION);
        assertThat(sql).doesNotContain("'restaurant_owner'");
        assertThat(sql).doesNotContain("'restaurant_chef'");
    }

    /** 从 VALUES 行里取出 (role, module) 对应的 permission_level。 */
    private static String rowLevel(String sql, String role, String module) {
        var m = java.util.regex.Pattern
            .compile("\\('" + java.util.regex.Pattern.quote(role) + "'\\s*,\\s*'"
                   + java.util.regex.Pattern.quote(module) + "'\\s*,\\s*'([^']+)'\\)")
            .matcher(sql);
        return m.find() ? m.group(1) : null;
    }
}
