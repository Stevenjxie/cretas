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

    @Test
    void approveDisposal_crossTenant_throws403_noSave() {
        when(repo.findById(50L)).thenReturn(Optional.of(record(50L, "F999")));  // 别家工厂记录

        assertThatThrownBy(() -> service.approveDisposal("F006", 50L, 1, "审批人"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(repo, never()).save(any());
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

        // 本厂记录正常审批 (守卫放行)
        service.approveDisposal("F006", 55L, 1, "审批人");
        verify(repo).save(any(DisposalRecord.class));
        assertThat(r.getIsApproved()).isTrue();
    }
}
