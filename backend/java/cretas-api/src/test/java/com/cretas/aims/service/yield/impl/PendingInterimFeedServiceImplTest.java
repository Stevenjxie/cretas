package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.service.wip.WipInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 🔴🔒🔒 延迟扣减盲区 — 待小结 SFI 投料聚合核心逻辑单测 (sibling #1216/#1219, 2026-07-05)。
 *
 * <p>覆盖: SAFETY_STOCK 族门控 (repo 层保证); 只聚合 semiFinished 引用 (in-plan WIP / FG 不减);
 * 折算诚实 null 跳过; 折算与 strict 扣减同源 (盒装折盒数); 多行同批次累加。
 */
@DisplayName("PendingInterimFeedServiceImpl — 待小结 SFI 投料聚合")
class PendingInterimFeedServiceImplTest {

    private ProcessSheetRowRepository rowRepository;
    private WipInventoryService wipInventoryService;
    private PendingInterimFeedServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String F = "F006";

    @BeforeEach
    void setUp() {
        rowRepository = mock(ProcessSheetRowRepository.class);
        wipInventoryService = mock(WipInventoryService.class);
        service = new PendingInterimFeedServiceImpl(rowRepository, wipInventoryService, objectMapper);
    }

    /** 构造一条待小结行, payload 含给定 upstream refs。 */
    private ProcessSheetRow row(ProcessSheetRowRequest.UpstreamRef... refs) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("cr-1");
        req.setProcessCode("chaoshui");
        req.setProcessOrder(1);
        req.setProductTypeId("pt-1");
        req.setOutputQuantity(BigDecimal.TEN);
        List<ProcessSheetRowRequest.UpstreamRef> list = new ArrayList<>();
        for (ProcessSheetRowRequest.UpstreamRef r : refs) list.add(r);
        req.setUpstreamSources(list);
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(F);
        row.setPlanId("plan-1");
        try {
            row.setRowPayload(objectMapper.writeValueAsString(req));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return row;
    }

    private ProcessSheetRowRequest.UpstreamRef ref(String batchNo, String feedKg, boolean semi, boolean fg) {
        ProcessSheetRowRequest.UpstreamRef r = new ProcessSheetRowRequest.UpstreamRef();
        r.setSourceBatchNumber(batchNo);
        r.setFeedQuantityKg(new BigDecimal(feedKg));
        r.setSemiFinished(semi);
        r.setFinishedGoods(fg);
        return r;
    }

    @Test
    @DisplayName("SFI 投料: 只聚合 semiFinished ref (折算源=resolveSemiFeed); in-plan WIP / FG 引用不计入")
    void pendingSemiFeed_onlySemiFinishedRefs() {
        // 一行含: 1 个常驻 SFI 投料 (387.6), 1 个 FG 投料 (10, 不减), 1 个 in-plan WIP 引用 (5, 不减)
        when(rowRepository.findUnsettledSafetyStockRows(F)).thenReturn(List.of(
                row(ref("SFI-1", "387.6", true, false),
                    ref("FG-1", "10", false, true),
                    ref("WIP-1", "5", false, false))));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(F, "SFI-1", new BigDecimal("387.6")))
                .thenReturn(new BigDecimal("387.6"));

        Map<String, BigDecimal> semi = service.pendingSemiFeedByBatchNo(F);

        assertThat(semi).containsOnlyKeys("SFI-1");
        assertThat(semi.get("SFI-1")).isEqualByComparingTo("387.6");
        // in-plan WIP-1 与 FG-1 都不进 SFI map (只 semiFinished 才折算)
        verify(wipInventoryService, never()).resolveSemiFeedQtyInSourceUnit(eq(F), eq("WIP-1"), any());
        verify(wipInventoryService, never()).resolveSemiFeedQtyInSourceUnit(eq(F), eq("FG-1"), any());
    }

    @Test
    @DisplayName("盒装 SFI 折算: 20kg → 40 盒 (与 consumeClerkSemiStrict 折算同源)")
    void pendingSemiFeed_countUnitConversion() {
        when(rowRepository.findUnsettledSafetyStockRows(F)).thenReturn(List.of(
                row(ref("SFI-1", "20", true, false))));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(F, "SFI-1", new BigDecimal("20")))
                .thenReturn(new BigDecimal("40"));   // 盒数

        assertThat(service.pendingSemiFeedByBatchNo(F).get("SFI-1")).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("诚实 null: 折算返回 null (盒装缺每盒克重/批次缺失) → 跳过不减 (不造假差异)")
    void honestNull_conversionNull_skipped() {
        when(rowRepository.findUnsettledSafetyStockRows(F)).thenReturn(List.of(
                row(ref("SFI-1", "100", true, false))));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(F, "SFI-1", new BigDecimal("100")))
                .thenReturn(null);   // 诚实 null

        assertThat(service.pendingSemiFeedByBatchNo(F)).isEmpty();   // 不减: 此时小结本身也 loud-fail, 货不离库
    }

    @Test
    @DisplayName("多行同批次累加: 两道 SFI 投料同一 intermediateBatchNo → Σ")
    void multipleRows_sameBatch_accumulate() {
        when(rowRepository.findUnsettledSafetyStockRows(F)).thenReturn(List.of(
                row(ref("SFI-1", "100", true, false)),
                row(ref("SFI-1", "50", true, false))));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(F, "SFI-1", new BigDecimal("100")))
                .thenReturn(new BigDecimal("100"));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(F, "SFI-1", new BigDecimal("50")))
                .thenReturn(new BigDecimal("50"));

        assertThat(service.pendingSemiFeedByBatchNo(F).get("SFI-1")).isEqualByComparingTo("150");
    }

    @Test
    @DisplayName("无待小结行 → 空 map (调用方减 0, 口径不变)")
    void noUnsettledRows_emptyMap() {
        when(rowRepository.findUnsettledSafetyStockRows(F)).thenReturn(List.of());
        assertThat(service.pendingSemiFeedByBatchNo(F)).isEmpty();
    }
}
