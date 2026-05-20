package com.cretas.aims.entity.finance;

import com.cretas.aims.entity.enums.AccountBalanceType;
import com.cretas.aims.entity.enums.AccountCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Account entity unit tests — Sprint 7 T1 Phase B.
 * 仅校验 builder / default value / 字段映射, 不测 DB.
 */
class AccountTest {

    @Test
    void builderDefaultsActiveAndLevelAndSortOrder() {
        Account a = Account.builder()
                .factoryId(null)
                .code("1001")
                .name("库存现金")
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        assertNull(a.getId());           // 由 @PrePersist 生成
        assertEquals(1, a.getLevel());   // default level=1
        assertTrue(a.getActive());        // default active=true
        assertEquals(0, a.getSortOrder()); // default sort_order=0
        assertNull(a.getParentId());
    }

    @Test
    void systemLevelAccountFactoryIdNull() {
        // 系统标准科目 factory_id 为 NULL → 所有 factory 共享
        Account a = Account.builder()
                .factoryId(null)
                .code("1122")
                .name("应收账款")
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        assertNull(a.getFactoryId());
        assertEquals(AccountCategory.ASSET, a.getCategory());
        assertEquals(AccountBalanceType.DEBIT_NORMAL, a.getBalanceType());
    }

    @Test
    void revenueAccountIsCreditNormal() {
        // 收入类科目 应 CREDIT_NORMAL (贷方余额)
        Account a = Account.builder()
                .factoryId(null)
                .code("6001")
                .name("主营业务收入")
                .category(AccountCategory.REVENUE)
                .balanceType(AccountBalanceType.CREDIT_NORMAL)
                .build();
        assertEquals(AccountCategory.REVENUE, a.getCategory());
        assertEquals(AccountBalanceType.CREDIT_NORMAL, a.getBalanceType());
    }

    @Test
    void liabilityAccountIsCreditNormal() {
        // 负债类 应 CREDIT_NORMAL
        Account a = Account.builder()
                .factoryId(null)
                .code("2202")
                .name("应付账款")
                .category(AccountCategory.LIABILITY)
                .balanceType(AccountBalanceType.CREDIT_NORMAL)
                .build();
        assertEquals(AccountCategory.LIABILITY, a.getCategory());
        assertEquals(AccountBalanceType.CREDIT_NORMAL, a.getBalanceType());
    }

    @Test
    void factoryCustomAccountHasFactoryId() {
        // factory 自定义科目 必须有 factoryId
        Account a = Account.builder()
                .factoryId("F006")
                .code("1001.01")
                .name("出纳现金")
                .parentId("sys-acc-1001")
                .level(2)
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        assertEquals("F006", a.getFactoryId());
        assertEquals("sys-acc-1001", a.getParentId());
        assertEquals(2, a.getLevel());
    }

    @Test
    void softDeleteSetsDeletedAt() {
        Account a = Account.builder()
                .factoryId(null)
                .code("1001")
                .name("库存现金")
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        assertNull(a.getDeletedAt());
        a.softDelete();
        assertNotNull(a.getDeletedAt());
        assertTrue(a.isDeleted());
    }
}
