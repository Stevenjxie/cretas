package com.cretas.aims.ai.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link WorkdeskRole}. */
class WorkdeskRoleTest {

    @Test
    @DisplayName("UT-WR-01: 三个岗位的显示名与代码注释里的标记逐字一致")
    void displayNames() {
        assertEquals("仓管员", WorkdeskRole.WAREHOUSE_KEEPER.displayName());
        assertEquals("采购员", WorkdeskRole.PURCHASER.displayName());
        assertEquals("质量主管", WorkdeskRole.QUALITY_SUPERVISOR.displayName());
    }

    @Test
    @DisplayName("UT-WR-02: 按显示名反查")
    void fromDisplayName() {
        assertSame(WorkdeskRole.PURCHASER, WorkdeskRole.fromDisplayName("采购员"));
        assertSame(WorkdeskRole.WAREHOUSE_KEEPER, WorkdeskRole.fromDisplayName("仓管员"));
        assertSame(WorkdeskRole.QUALITY_SUPERVISOR, WorkdeskRole.fromDisplayName("质量主管"));
    }

    @Test
    @DisplayName("UT-WR-03: 🔴 认不出的岗位名必须抛异常, 不得返回 null")
    void unknownDisplayNameThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WorkdeskRole.fromDisplayName("采够员"));
        assertTrue(e.getMessage().contains("采够员"),
                "异常信息要带上传进来的原值, 否则用户不知道自己打错了什么: " + e.getMessage());
    }

    @Test
    @DisplayName("UT-WR-04: null 与空串同样抛异常")
    void nullAndBlankThrow() {
        assertThrows(IllegalArgumentException.class, () -> WorkdeskRole.fromDisplayName(null));
        assertThrows(IllegalArgumentException.class, () -> WorkdeskRole.fromDisplayName("  "));
    }

    @Test
    @DisplayName("UT-WR-05: 枚举只含这三个岗位 —— 新增岗位必须显式改测试")
    void exactlyThreeRoles() {
        assertEquals(3, WorkdeskRole.values().length,
                "岗位集合来自代码里的 Sprint 8 P4a/P4b/P4c 标记, 不能随手扩");
    }
}
