package com.cretas.aims.service;

import com.cretas.aims.entity.EmployeeWorkSession;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.EmployeeWorkSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计 round3 多租户隔离: 工作会话按 id 加载的写/读方法必须校验记录归属工厂。
 * 此前 endSession/cancelSession/updateSession/getById 用 findById(id) 不校验 factoryId →
 * F006 用户可结束/取消/修改/查看别家工厂的工作会话(含改人工成本)。
 */
class EmployeeWorkSessionServiceCrossTenantTest {

    private EmployeeWorkSessionRepository repo;
    private EmployeeWorkSessionService service;

    @BeforeEach
    void setUp() {
        repo = mock(EmployeeWorkSessionRepository.class);
        service = new EmployeeWorkSessionService(repo);
    }

    private EmployeeWorkSession session(long id, String factoryId, String status) {
        EmployeeWorkSession s = new EmployeeWorkSession();
        s.setId(id);
        s.setFactoryId(factoryId);
        s.setStatus(status);
        s.setStartTime(LocalDateTime.now().minusHours(2));
        s.setBreakMinutes(0);
        return s;
    }

    @Test
    void endSession_crossTenant_throws403_noSave() {
        when(repo.findById(60L)).thenReturn(Optional.of(session(60L, "F999", "active")));
        assertThatThrownBy(() -> service.endSession("F006", 60L, 0, "x"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(repo, never()).save(any());
    }

    @Test
    void cancelSession_crossTenant_throws403_noSave() {
        when(repo.findById(61L)).thenReturn(Optional.of(session(61L, "F999", "active")));
        assertThatThrownBy(() -> service.cancelSession("F006", 61L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(repo, never()).save(any());
    }

    @Test
    void updateSession_crossTenant_throws403_noSave() {
        when(repo.findById(62L)).thenReturn(Optional.of(session(62L, "F999", "active")));
        assertThatThrownBy(() -> service.updateSession("F006", 62L, new EmployeeWorkSession()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(repo, never()).save(any());
    }

    @Test
    void getById_crossTenant_returnsEmpty() {
        when(repo.findById(63L)).thenReturn(Optional.of(session(63L, "F999", "active")));
        assertThat(service.getById("F006", 63L)).isEmpty();      // 跨租户 → 空 (404)
        assertThat(service.getById("F999", 63L)).isPresent();    // 本厂 → 可见
    }

    @Test
    void cancelSession_sameFactory_proceeds() {
        EmployeeWorkSession s = session(64L, "F006", "active");
        when(repo.findById(64L)).thenReturn(Optional.of(s));
        when(repo.save(any(EmployeeWorkSession.class))).thenAnswer(i -> i.getArgument(0));
        service.cancelSession("F006", 64L);
        verify(repo).save(any(EmployeeWorkSession.class));
        assertThat(s.getStatus()).isEqualTo("cancelled");
    }
}
