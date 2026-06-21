package com.cretas.aims.service;

import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.DisposalRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计 round2 多租户隔离: 报废记录按 id 加载的写/读方法必须校验记录归属工厂。
 * 此前 approveDisposal/updateDisposalRecord/deleteDisposalRecord/submitForApproval/getById
 * 用 findById(id) 不校验 factoryId → F006 用户可操作其它工厂的报废记录 (id 可枚举)。
 */
class DisposalRecordServiceCrossTenantTest {

    private DisposalRecordRepository repo;
    private DisposalRecordService service;

    @BeforeEach
    void setUp() {
        repo = mock(DisposalRecordRepository.class);
        service = new DisposalRecordService(repo);
    }

    private DisposalRecord record(long id, String factoryId) {
        DisposalRecord r = new DisposalRecord();
        r.setId(id);
        r.setFactoryId(factoryId);
        r.setIsApproved(false);
        return r;
    }

    /** 财务角色 — 通过 F-BUG-5 角色守卫, 以验证后续的工厂归属守卫。 */
    private static final String FINANCE_ROLE = "finance_manager";

    @Test
    void approveDisposal_crossTenant_throws403_noSave() {
        when(repo.findById(50L)).thenReturn(Optional.of(record(50L, "F999")));  // 别家工厂记录

        // 用合法财务角色, 确保 403 来自工厂归属守卫 (而非角色守卫)
        assertThatThrownBy(() -> service.approveDisposal("F006", 50L, 1, "审批人", FINANCE_ROLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(repo, never()).save(any());
    }

    @Test
    void approveDisposal_nonFinanceRole_throws403_noSave() {
        // F-BUG-5: 仓管员 (warehouse_manager) 不能自批报废
        when(repo.findById(56L)).thenReturn(Optional.of(record(56L, "F006")));

        assertThatThrownBy(() -> service.approveDisposal("F006", 56L, 1, "仓管员", "warehouse_manager"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(repo, never()).save(any());
    }

    @Test
    void rejectDisposal_sameFactory_setsRejected_noStockDeduction() {
        DisposalRecord r = record(57L, "F006");
        when(repo.findById(57L)).thenReturn(Optional.of(r));
        when(repo.save(any(DisposalRecord.class))).thenAnswer(i -> i.getArgument(0));

        service.rejectDisposal("F006", 57L, 1, "财务", "证据不足", FINANCE_ROLE);

        verify(repo).save(any(DisposalRecord.class));
        assertThat(r.getIsApproved()).isFalse();              // 不扣库存
        assertThat(r.getStatus()).isEqualTo("REJECTED");
        assertThat(r.getRejectReason()).isEqualTo("证据不足");
    }

    @Test
    void updateDisposalRecord_crossTenant_throws403_noSave() {
        when(repo.findById(51L)).thenReturn(Optional.of(record(51L, "F999")));

        assertThatThrownBy(() -> service.updateDisposalRecord("F006", 51L, new DisposalRecord()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(repo, never()).save(any());
    }

    @Test
    void deleteDisposalRecord_crossTenant_throws403_noSave() {
        when(repo.findById(52L)).thenReturn(Optional.of(record(52L, "F999")));

        assertThatThrownBy(() -> service.deleteDisposalRecord("F006", 52L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(repo, never()).save(any());
    }

    @Test
    void submitForApproval_crossTenant_throws403() {
        when(repo.findById(53L)).thenReturn(Optional.of(record(53L, "F999")));

        assertThatThrownBy(() -> service.submitForApproval(53L, "F006", 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
    }

    @Test
    void getById_crossTenant_returnsEmpty() {
        when(repo.findById(54L)).thenReturn(Optional.of(record(54L, "F999")));

        assertThat(service.getById("F006", 54L)).isEmpty();          // 跨租户 → 空 (404)
        assertThat(service.getById("F999", 54L)).isPresent();        // 本厂 → 可见
    }

    @Test
    void approveDisposal_sameFactory_proceeds() {
        DisposalRecord r = record(55L, "F006");
        when(repo.findById(55L)).thenReturn(Optional.of(r));
        when(repo.save(any(DisposalRecord.class))).thenAnswer(i -> i.getArgument(0));

        // 本厂记录 + 财务角色 → 正常审批 (两道守卫放行)
        service.approveDisposal("F006", 55L, 1, "审批人", FINANCE_ROLE);
        verify(repo).save(any(DisposalRecord.class));
        assertThat(r.getIsApproved()).isTrue();
    }
}
