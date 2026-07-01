package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.SemiFinishedStocktakeDTO;
import com.cretas.aims.entity.InterimSettleReversalRequest;
import com.cretas.aims.entity.factory.SemiFinishedStocktake;
import com.cretas.aims.repository.InterimSettleReversalRequestRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.factory.SemiFinishedStocktakeItemRepository;
import com.cretas.aims.repository.factory.SemiFinishedStocktakeRepository;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 半成品盘点撤销告警 — getDetail 从撤销审计 (EXECUTED 撤销申请) 在盘点列上标撤销告警点 (READ-ONLY)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SemiFinishedStocktakeReversalWarningTest - 盘点列撤销告警点")
class SemiFinishedStocktakeReversalWarningTest {

    private static final String FACTORY = "F006";
    private static final String STOCKTAKE_ID = "st-1";

    @Mock private SemiFinishedStocktakeRepository stocktakeRepo;
    @Mock private SemiFinishedStocktakeItemRepository stocktakeItemRepo;
    @Mock private SemiFinishedInventoryRepository sfiRepo;
    @Mock private SemiFinishedInventoryTransactionRepository sfiTxnRepo;
    @Mock private VoucherService voucherService;
    @Mock private InterimSettleReversalRequestRepository reversalRequestRepo;

    @InjectMocks private SemiFinishedStocktakeServiceImpl service;

    @BeforeEach
    void setUp() {
        // @Autowired(required=false) 可选字段: Mockito 走构造器注入后不再 field-inject, 手动 set (镜像既有盘点测试)。
        ReflectionTestUtils.setField(service, "reversalRequestRepo", reversalRequestRepo);
    }

    private SemiFinishedStocktake stocktake() {
        SemiFinishedStocktake st = new SemiFinishedStocktake();
        st.setId(STOCKTAKE_ID);
        st.setFactoryId(FACTORY);
        st.setStocktakeNo("SFST-202606-ABCD1234");
        st.setPeriodMonth("2026-06");
        st.setStatus(SemiFinishedStocktake.Status.COUNTING);
        return st;
    }

    @Test
    @DisplayName("getDetail: 期间内已执行撤销 → 盘点列出撤销告警点 (每个受影响批次一条)")
    void getDetailSurfacesReversalWarnings() {
        when(stocktakeRepo.findById(STOCKTAKE_ID)).thenReturn(Optional.of(stocktake()));
        InterimSettleReversalRequest executed = InterimSettleReversalRequest.builder()
                .id("r1").factoryId(FACTORY).productionPlanId("PLAN1").sessionSeq(1)
                .status(InterimSettleReversalRequest.Status.EXECUTED)
                .executedAt(LocalDateTime.of(2026, 6, 15, 10, 0))
                .affectedBatchNumbers("CLK-SEMI-A,FG-PP-001-S2")
                .build();
        when(reversalRequestRepo.findByFactoryIdAndStatusAndExecutedAtBetween(
                eq(FACTORY), eq(InterimSettleReversalRequest.Status.EXECUTED), any(), any()))
                .thenReturn(List.of(executed));

        SemiFinishedStocktakeDTO dto = service.getDetail(STOCKTAKE_ID, FACTORY);

        assertThat(dto.getWarnings()).isNotNull().hasSize(2);
        assertThat(dto.getWarnings()).extracting(SemiFinishedStocktakeDTO.ReversalWarning::getBatchNo)
                .containsExactlyInAnyOrder("CLK-SEMI-A", "FG-PP-001-S2");
        assertThat(dto.getWarnings().get(0).getMessage()).contains("撤销过小结").contains("核实实物");
    }

    @Test
    @DisplayName("getDetail: 期间内无已执行撤销 → 无告警 (warnings null)")
    void getDetailNoReversalNoWarnings() {
        when(stocktakeRepo.findById(STOCKTAKE_ID)).thenReturn(Optional.of(stocktake()));
        when(reversalRequestRepo.findByFactoryIdAndStatusAndExecutedAtBetween(any(), any(), any(), any()))
                .thenReturn(List.of());

        SemiFinishedStocktakeDTO dto = service.getDetail(STOCKTAKE_ID, FACTORY);

        assertThat(dto.getWarnings()).isNull();
    }
}
