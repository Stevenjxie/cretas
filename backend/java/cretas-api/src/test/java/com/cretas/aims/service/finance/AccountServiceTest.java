package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.AccountBalanceType;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AccountService unit tests — Sprint 7 T1 Phase B.
 * Mock repository, 测 Rule 1 (dup detect) / Rule 5 (dead-end nav on delete with children).
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepo;

    @Mock
    private VoucherEntryRepository voucherEntryRepo;

    @InjectMocks
    private AccountService accountService;

    private Account validAccount;

    @BeforeEach
    void setUp() {
        validAccount = Account.builder()
                .factoryId("F006")
                .code("1001.01")
                .name("出纳备用金")
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
    }

    @Test
    void createAccountSucceedsForUniqueCode() {
        when(accountRepo.existsByFactoryIdAndCodeAndDeletedAtIsNull("F006", "1001.01"))
                .thenReturn(false);
        when(accountRepo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account saved = accountService.create(validAccount);
        assertNotNull(saved.getId(), "id should be UUID-generated");
        assertEquals(1, saved.getLevel());
        assertTrue(saved.getActive());
        verify(accountRepo).save(any(Account.class));
    }

    @Test
    void createDuplicateCodeThrows409WithHint() {
        when(accountRepo.existsByFactoryIdAndCodeAndDeletedAtIsNull("F006", "1001.01"))
                .thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService.create(validAccount));
        assertEquals(Integer.valueOf(409), ex.getCode());
        assertTrue(ex.getMessage().contains("1001.01"));
        assertNotNull(ex.getActionHint(), "actionHint required for 4-in-1 防呆");
        verify(accountRepo, never()).save(any());
    }

    @Test
    void createMissingCodeThrows400() {
        Account bad = Account.builder()
                .factoryId("F006")
                .code("")  // blank
                .name("test")
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService.create(bad));
        assertEquals(Integer.valueOf(400), ex.getCode());
        assertTrue(ex.getMessage().contains("编码"));
    }

    @Test
    void createWithParentInheritsLevelPlusOne() {
        Account parent = Account.builder()
                .id("parent-id")
                .factoryId(null)  // 系统级
                .code("1001")
                .name("库存现金")
                .level(1)
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        Account child = Account.builder()
                .factoryId("F006")
                .code("1001.01")
                .name("F006 出纳")
                .parentId("parent-id")
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        when(accountRepo.existsByFactoryIdAndCodeAndDeletedAtIsNull("F006", "1001.01"))
                .thenReturn(false);
        when(accountRepo.findByIdAndDeletedAtIsNull("parent-id"))
                .thenReturn(Optional.of(parent));
        when(accountRepo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account saved = accountService.create(child);
        assertEquals(2, saved.getLevel(), "child should inherit parent.level + 1");
    }

    @Test
    void createWithLevel5Throws() {
        Account level4Parent = Account.builder()
                .id("L4-parent")
                .level(4)
                .factoryId(null)
                .code("1001.01.001")
                .name("L4 parent")
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        Account level5Child = Account.builder()
                .factoryId("F006")
                .code("1001.01.001.A")
                .name("over-depth")
                .parentId("L4-parent")
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        when(accountRepo.existsByFactoryIdAndCodeAndDeletedAtIsNull(eq("F006"), eq("1001.01.001.A")))
                .thenReturn(false);
        when(accountRepo.findByIdAndDeletedAtIsNull("L4-parent")).thenReturn(Optional.of(level4Parent));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService.create(level5Child));
        assertTrue(ex.getMessage().contains("层级"));
    }

    @Test
    void softDeleteWithChildrenThrows409WithHint() {
        Account existing = Account.builder()
                .id("acc-id")
                .factoryId("F006")
                .code("1001")
                .name("库存现金")
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        when(accountRepo.findByIdAndDeletedAtIsNull("acc-id")).thenReturn(Optional.of(existing));
        when(accountRepo.countByParentIdAndDeletedAtIsNull("acc-id")).thenReturn(3L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService.softDelete("F006", "acc-id"));
        assertEquals(Integer.valueOf(409), ex.getCode());
        assertTrue(ex.getMessage().contains("3 个子科目"));
        assertNotNull(ex.getActionHint(), "actionHint required for Rule 5 dead-end nav");
        verify(accountRepo, never()).save(any());
    }

    @Test
    void softDeleteSucceedsWhenNoChildren() {
        Account existing = Account.builder()
                .id("acc-id")
                .factoryId("F006")
                .code("1001")
                .name("库存现金")
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        when(accountRepo.findByIdAndDeletedAtIsNull("acc-id")).thenReturn(Optional.of(existing));
        when(accountRepo.countByParentIdAndDeletedAtIsNull("acc-id")).thenReturn(0L);
        // H-BUG-3: 无凭证分录引用 → 允许删除
        when(voucherEntryRepo.countBySubjectCodeAndFactory("F006", "1001")).thenReturn(0L);

        assertDoesNotThrow(() -> accountService.softDelete("F006", "acc-id"));
        verify(accountRepo).save(argThat(a -> a.getDeletedAt() != null));
    }

    @Test
    void softDeleteWithVoucherEntryReferenceThrows409() {
        // H-BUG-3 (2026-06-21 transcript-e2e R1): 已被凭证分录引用的科目禁删。
        Account existing = Account.builder()
                .id("acc-id")
                .factoryId("F006")
                .code("1001")
                .name("库存现金")
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        when(accountRepo.findByIdAndDeletedAtIsNull("acc-id")).thenReturn(Optional.of(existing));
        when(accountRepo.countByParentIdAndDeletedAtIsNull("acc-id")).thenReturn(0L);
        when(voucherEntryRepo.countBySubjectCodeAndFactory("F006", "1001")).thenReturn(5L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService.softDelete("F006", "acc-id"));
        assertEquals(Integer.valueOf(409), ex.getCode());
        assertTrue(ex.getMessage().contains("5 条凭证分录"), "应说明被几条分录引用");
        assertNotNull(ex.getActionHint(), "actionHint required for Rule 5 dead-end nav");
        verify(accountRepo, never()).save(any());
    }

    @Test
    void updatePreservesImmutableFields() {
        Account existing = Account.builder()
                .id("acc-id")
                .factoryId("F006")
                .code("1001")  // immutable
                .name("库存现金")
                .category(AccountCategory.ASSET)  // immutable
                .balanceType(AccountBalanceType.DEBIT_NORMAL)  // immutable
                .level(1)  // immutable
                .active(true)
                .build();
        when(accountRepo.findByIdAndDeletedAtIsNull("acc-id")).thenReturn(Optional.of(existing));
        when(accountRepo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        // Try changing immutable fields — they should be ignored
        Account patch = Account.builder()
                .name("新名称")
                .description("更新描述")
                .active(false)
                .sortOrder(99)
                .code("9999")  // attempt to change immutable — ignored
                .build();
        Account result = accountService.update("F006", "acc-id", patch);
        assertEquals("新名称", result.getName());
        assertEquals("更新描述", result.getDescription());
        assertFalse(result.getActive());
        assertEquals(99, result.getSortOrder());
        assertEquals("1001", result.getCode(), "code should not change");
        assertEquals(AccountCategory.ASSET, result.getCategory(), "category should not change");
    }
}
