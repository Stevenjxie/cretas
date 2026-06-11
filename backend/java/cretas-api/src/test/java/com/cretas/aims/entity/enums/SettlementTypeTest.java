package com.cretas.aims.entity.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SettlementType 枚举独立单测 — SP6 6 类结算方式.
 *
 * <p>补 SP6/采购终审揭示的测试缺口: 终审确认 6 类枚举值存在 +
 * displayName 语义正确, 防止枚举值被意外删除或重命名导致数据库
 * 映射静默失效 (settlementType 以字符串存储, 枚举名变更=历史数据孤儿)。
 */
class SettlementTypeTest {

    // ── T1: 枚举恰好 6 个值 ────────────────────────────────────────────────

    @Test
    void exactlySixValues() {
        // 终审实证: 6 类结算 (预付/赊销/未到票/月结/账期/现结). 不得增删.
        // 如新增结算类型, 必须同步 VoucherSubjectMappingService seed + Flyway + 本测试.
        assertEquals(6, SettlementType.values().length,
                "SP6 spec: SettlementType 必须恰好 6 类, 不得增删; 新增要同步科目映射种子");
    }

    // ── T2: 枚举名顺序 (数据库 ordinal 绑定, 不许重排) ──────────────────

    @Test
    void enumNamesAndOrder() {
        SettlementType[] values = SettlementType.values();
        assertEquals(SettlementType.PREPAID,       values[0]);
        assertEquals(SettlementType.CREDIT_FIRST,  values[1]);
        assertEquals(SettlementType.NO_INVOICE,    values[2]);
        assertEquals(SettlementType.MONTHLY,       values[3]);
        assertEquals(SettlementType.CREDIT_PERIOD, values[4]);
        assertEquals(SettlementType.IMMEDIATE,     values[5]);
    }

    // ── T3-T8: 每类 displayName 语义正确 ────────────────────────────────

    @Test
    void prepaidDisplayName() {
        assertEquals("预付", SettlementType.PREPAID.getDisplayName());
    }

    @Test
    void creditFirstDisplayName() {
        assertEquals("赊销先入库", SettlementType.CREDIT_FIRST.getDisplayName());
    }

    @Test
    void noInvoiceDisplayName() {
        assertEquals("未到票", SettlementType.NO_INVOICE.getDisplayName());
    }

    @Test
    void monthlyDisplayName() {
        assertEquals("月结", SettlementType.MONTHLY.getDisplayName());
    }

    @Test
    void creditPeriodDisplayName() {
        assertEquals("账期", SettlementType.CREDIT_PERIOD.getDisplayName());
    }

    @Test
    void immediateDisplayName() {
        assertEquals("现结", SettlementType.IMMEDIATE.getDisplayName());
    }

    // ── T9: valueOf 双向解析 (数据库字符串 → 枚举 round-trip) ──────────

    @Test
    void valueOfRoundTrip() {
        // 以 PREPAID 代表全部: name() 与 valueOf() 互为反函数.
        // 任何枚举名不得有前后空格 (会导致 JPA @Enumerated(STRING) 解析失败).
        for (SettlementType t : SettlementType.values()) {
            assertEquals(t, SettlementType.valueOf(t.name()),
                    "valueOf(name()) 必须还原原枚举值: " + t.name());
            // 枚举名中不含空白字符 (数据库字段不能存带空格的 ordinal)
            assertFalse(t.name().contains(" "),
                    "枚举名不得含空白字符: " + t.name());
        }
    }
}
