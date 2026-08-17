package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 冲销一条报工的库存影响。
 *
 * <p>口径（2026-08-17 定，设计卡 {@code docs/decisions/2026-08-17-驳回报工不回退库存.md}）：
 * <b>下游已经领走的，拒绝冲销并指名是哪一道</b>。理由是「事先拦住」优于「事后留下一个
 * 不自洽的库存」—— 允许冲销会让下游那条报工建立在一批已经不存在的料上，而且不报错。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReverseReportPostingTest {

    private static final String FACTORY = "F006";

    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private BatchLineageEdgeRepository lineageEdgeRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository workProcessRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private ProductFamilyResolver productFamilyResolver;
    @Mock private ApplicationEventPublisher eventPublisher;

    private WipInventoryServiceImpl svc;
    private ProductionReport report;
    private WorkProcessTask task;

    @BeforeEach
    void setUp() {
        svc = new WipInventoryServiceImpl(wipRepo, txnRepo, reportRepo, lineageEdgeRepo,
                taskRepo, workProcessRepo, productTypeRepo, productFamilyResolver, eventPublisher);

        report = new ProductionReport();
        report.setId(23814L);
        report.setFactoryId(FACTORY);
        report.setReportKind("OUTPUT");
        report.setOutputQuantity(new BigDecimal("2.5"));

        task = new WorkProcessTask();
        task.setId(1786L);
        task.setFactoryId(FACTORY);
        task.setProductionBatchId(10759L);
        task.setProcessOrder(2);
        task.setProductTypeId("PT_F006_LSM");

        when(txnRepo.findByFactoryIdAndReportId(anyString(), any())).thenReturn(List.of());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
    }

    private SemiFinishedInventory wip(String produced, String consumed) {
        SemiFinishedInventory w = new SemiFinishedInventory();
        w.setId(336L);
        w.setFactoryId(FACTORY);
        w.setBatchId(10759L);
        w.setSourceWorkProcessTaskId(1786L);
        w.setIntermediateBatchNo("PT_F006_LSM-B10759-S2-1786");
        w.setUnit("kg");
        w.setProducedQuantity(new BigDecimal(produced));
        w.setConsumedQuantity(new BigDecimal(consumed));
        w.setAvailableQuantity(new BigDecimal(produced).subtract(new BigDecimal(consumed)));
        w.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        return w;
    }

    private void wipIs(SemiFinishedInventory w) {
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                anyString(), anyString())).thenReturn(Optional.ofNullable(w));
    }

    @Test
    @DisplayName("🔴 没被领用过 → 产出退回, 余额归零, 并留一条 REVERSE 流水")
    void reversesProducedOutput() {
        wipIs(wip("2.5", "0"));

        svc.reverseReportPosting(FACTORY, report, task, 1310L);

        ArgumentCaptor<SemiFinishedInventory> saved = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(wipRepo).save(saved.capture());
        assertThat(saved.getValue().getProducedQuantity()).isEqualByComparingTo("0");
        assertThat(saved.getValue().getAvailableQuantity()).isEqualByComparingTo("0");
        assertThat(saved.getValue().getStatus()).isEqualTo(SemiFinishedInventory.Status.DEPLETED);
        assertThat(saved.getValue().getUnitCost())
                .as("退空了还留着均价, 下一次入库会被它带偏")
                .isNull();

        ArgumentCaptor<SemiFinishedInventoryTransaction> txn =
                ArgumentCaptor.forClass(SemiFinishedInventoryTransaction.class);
        verify(txnRepo).save(txn.capture());
        assertThat(txn.getValue().getTxnType())
                .isEqualTo(SemiFinishedInventoryTransaction.TxnType.REVERSE);
        assertThat(txn.getValue().getQuantity()).isEqualByComparingTo("-2.5");
        assertThat(txn.getValue().getBalanceAfter())
                .as("⛔ 余额不许算成负数 —— 上一轮就是构造点放错边算出 -2.000000")
                .isEqualByComparingTo("0");
        assertThat(txn.getValue().getReportId()).isEqualTo(23814L);
    }

    @Test
    @DisplayName("🔴 下游已领用 → 拒绝冲销并指名领了多少, ⛔ 不留下不自洽的库存")
    void refusesWhenDownstreamAlreadyConsumed() {
        wipIs(wip("2.5", "2.5"));

        assertThatThrownBy(() -> svc.reverseReportPosting(FACTORY, report, task, 1310L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被下一道领用")
                .hasMessageContaining("2.5");

        verify(wipRepo, never()).save(any());
        verify(txnRepo, never()).save(any());
    }

    @Test
    @DisplayName("⛔ 阴性对照: 已经冲销过的报工再调一次是 no-op, 不退第二次")
    void idempotentOnSecondCall() {
        wipIs(wip("2.5", "0"));
        SemiFinishedInventoryTransaction prior = SemiFinishedInventoryTransaction.builder()
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE).build();
        when(txnRepo.findByFactoryIdAndReportId(anyString(), any())).thenReturn(List.of(prior));

        svc.reverseReportPosting(FACTORY, report, task, 1310L);

        verify(wipRepo, never()).save(any());
        verify(txnRepo, never()).save(any());
    }

    @Test
    @DisplayName("⛔ 阴性对照: 本次没有产出(纯投入报工) → 不碰产出侧库存")
    void noOutputMeansNoProducedReversal() {
        report.setReportKind("INPUT");
        report.setOutputQuantity(null);
        wipIs(wip("2.5", "0"));

        svc.reverseReportPosting(FACTORY, report, task, 1310L);

        verify(wipRepo, never()).save(any());
        verify(txnRepo, never()).save(any());
    }

    @Test
    @DisplayName("🔴 领用侧: 本道领过上道的料 → 把领用量还回去, 上道恢复 AVAILABLE")
    void returnsConsumedSourceWip() {
        report.setReportKind("INPUT");
        report.setOutputQuantity(null);
        report.setSourceWipNo("CLK-SEMI-src");
        report.setInputQuantity(new BigDecimal("2"));

        SemiFinishedInventory src = wip("2.0", "2.0");
        src.setIntermediateBatchNo("CLK-SEMI-src");
        src.setStatus(SemiFinishedInventory.Status.DEPLETED);
        wipIs(src);

        svc.reverseReportPosting(FACTORY, report, task, 1310L);

        ArgumentCaptor<SemiFinishedInventory> saved = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(wipRepo).save(saved.capture());
        assertThat(saved.getValue().getConsumedQuantity()).isEqualByComparingTo("0");
        assertThat(saved.getValue().getAvailableQuantity()).isEqualByComparingTo("2.0");
        assertThat(saved.getValue().getStatus()).isEqualTo(SemiFinishedInventory.Status.AVAILABLE);

        ArgumentCaptor<SemiFinishedInventoryTransaction> txn =
                ArgumentCaptor.forClass(SemiFinishedInventoryTransaction.class);
        verify(txnRepo).save(txn.capture());
        assertThat(txn.getValue().getQuantity())
                .as("还回去是【正】数量, 与领用的负数量相对")
                .isEqualByComparingTo("2");
        assertThat(txn.getValue().getBalanceAfter()).isEqualByComparingTo("2.0");
    }
}
