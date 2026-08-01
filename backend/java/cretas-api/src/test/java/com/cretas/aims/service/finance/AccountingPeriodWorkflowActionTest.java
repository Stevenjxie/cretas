package com.cretas.aims.service.finance;

import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.service.finance.impl.AccountingPeriodServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 会计期间接入统一 OA 动作。
 *
 * <p>🔒 <b>本类覆盖的是关账链路</b>: APPROVE 会把期间转 CLOSED 并触发库存台账快照,
 * 凭证随后进入 20 天调整窗口、逾期硬锁。
 *
 * <p>背景: BUDGET 的 OA 实例此前是「孤儿」—— {@code requestClose} 会启动它, 但批准与否
 * 都不影响期间状态(fail-open 设计: 「期间结账是合规级业务, 不能因 workflow 没配就阻塞,
 * 由 finance director 手工 confirmClose 推进」)。所以待办里那条只能看不能点。
 * 本次把它接上: APPROVE → confirmClose, REJECT → 回 OPEN(撤销本次 requestClose)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountingPeriodWorkflowActionTest {

    private static final String FACTORY_ID = "LIUSHANMEN";
    private static final String PERIOD_ID = "b67922a2-e4b9-4143-bd6e-33d42ed98ae0";
    private static final Long ACTOR = 1638L;

    @Mock private AccountingPeriodRepository repo;

    private AccountingPeriodServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AccountingPeriodServiceImpl(repo);
        when(repo.save(any(AccountingPeriod.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AccountingPeriod period(AccountingPeriod.Status status) {
        AccountingPeriod p = new AccountingPeriod();
        p.setId(PERIOD_ID);
        p.setFactoryId(FACTORY_ID);
        p.setYear(2026);
        p.setMonth(7);
        p.setStatus(status);
        when(repo.findById(PERIOD_ID)).thenReturn(Optional.of(p));
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY_ID, 2026, 7))
                .thenReturn(Optional.of(p));
        return p;
    }

    @Test
    @DisplayName("🔒 审批通过则期间关账")
    void approveClosesPeriod() {
        period(AccountingPeriod.Status.PENDING_CLOSE);

        AccountingPeriod after = service.applyWorkflowAction(
                FACTORY_ID, PERIOD_ID, ACTOR, HistoryAction.APPROVE, null);

        assertThat(after.getStatus()).isEqualTo(AccountingPeriod.Status.CLOSED);
        assertThat(after.getClosedBy()).isEqualTo(ACTOR);
    }

    @Test
    @DisplayName("驳回则期间回到 OPEN —— 撤销本次 requestClose")
    void rejectReopensPeriod() {
        period(AccountingPeriod.Status.PENDING_CLOSE);

        AccountingPeriod after = service.applyWorkflowAction(
                FACTORY_ID, PERIOD_ID, ACTOR, HistoryAction.REJECT, "成本还没核完");

        assertThat(after.getStatus()).isEqualTo(AccountingPeriod.Status.OPEN);
    }

    @Test
    @DisplayName("重复驳回幂等 —— 已是 OPEN 不报错")
    void rejectIsIdempotent() {
        period(AccountingPeriod.Status.OPEN);

        AccountingPeriod after = service.applyWorkflowAction(
                FACTORY_ID, PERIOD_ID, ACTOR, HistoryAction.REJECT, "第二次");

        assertThat(after.getStatus()).isEqualTo(AccountingPeriod.Status.OPEN);
    }

    @Test
    @DisplayName("🔒 已 CLOSED 的期间不能被驳回掀翻 —— 反结账要走专门通道")
    void rejectCannotUndoAClosedPeriod() {
        period(AccountingPeriod.Status.CLOSED);

        assertThatThrownBy(() -> service.applyWorkflowAction(
                FACTORY_ID, PERIOD_ID, ACTOR, HistoryAction.REJECT, "反悔了"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PENDING_CLOSE");
    }

    @Test
    @DisplayName("跨工厂访问被拒 —— 多租户隔离")
    void crossFactoryAccessIsRejected() {
        period(AccountingPeriod.Status.PENDING_CLOSE);

        assertThatThrownBy(() -> service.applyWorkflowAction(
                "F001", PERIOD_ID, ACTOR, HistoryAction.APPROVE, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("期间不存在时报 404 而不是 NPE")
    void missingPeriodReports404() {
        when(repo.findById("no-such-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyWorkflowAction(
                FACTORY_ID, "no-such-id", ACTOR, HistoryAction.APPROVE, null))
                .isInstanceOf(BusinessException.class);
    }
}
