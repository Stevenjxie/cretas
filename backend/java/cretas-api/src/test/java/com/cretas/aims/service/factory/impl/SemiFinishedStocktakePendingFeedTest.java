package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.CreateSemiFinishedStocktakeRequest;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.factory.SemiFinishedStocktake;
import com.cretas.aims.entity.factory.SemiFinishedStocktakeItem;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.factory.SemiFinishedStocktakeItemRepository;
import com.cretas.aims.repository.factory.SemiFinishedStocktakeRepository;
import com.cretas.aims.service.voucher.VoucherService;
import com.cretas.aims.service.yield.PendingInterimFeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 🔴🔒🔒 A1 — 半成品盘点「延迟扣减盲区」修复单测 (sibling #1216, 2026-07-05)。
 *
 * <p>快照账面 systemQty = availableQuantity − 待小结 SFI 投料 (货架实物)。核心: 有待扣时不再假盘亏 +
 * 后续小结不二次扣减; 无待扣时口径不变; 超投 clamp≥0。
 */
@DisplayName("A1 半成品盘点 — 待小结投料减除 (延迟扣减盲区)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SemiFinishedStocktakePendingFeedTest {

    @Mock private SemiFinishedStocktakeRepository stocktakeRepo;
    @Mock private SemiFinishedStocktakeItemRepository stocktakeItemRepo;
    @Mock private SemiFinishedInventoryRepository sfiRepo;
    @Mock private SemiFinishedInventoryTransactionRepository sfiTxnRepo;
    @Mock private VoucherService voucherService;
    @Mock private PendingInterimFeedService pendingInterimFeedService;

    @InjectMocks private SemiFinishedStocktakeServiceImpl service;

    private static final String F = "F006";
    private static final Long USER = 42L;

    @BeforeEach
    void wireOptionalField() {
        // @InjectMocks 用构造器注入 5 个 final 依赖, 不会再 field-inject 可选字段 → 手动注入。
        ReflectionTestUtils.setField(service, "pendingInterimFeedService", pendingInterimFeedService);
    }

    private SemiFinishedInventory sfi(String batchNo, String avail) {
        return SemiFinishedInventory.builder()
                .id(100L).factoryId(F).intermediateBatchNo(batchNo)
                .productTypeId("PT-1").unit("kg")
                .producedQuantity(new BigDecimal(avail)).consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal(avail)).status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
    }

    private SemiFinishedStocktakeItem initiateAndGetItem(String batchNo, String avail,
                                                         Map<String, BigDecimal> pending) {
        CreateSemiFinishedStocktakeRequest req = new CreateSemiFinishedStocktakeRequest();
        req.setPeriodMonth("2026-07");
        when(stocktakeRepo.countActiveStocktake(F)).thenReturn(0L);
        when(sfiRepo.findByFactoryIdAndStatusForStocktake(F, SemiFinishedInventory.Status.AVAILABLE))
                .thenReturn(List.of(sfi(batchNo, avail)));
        when(pendingInterimFeedService.pendingSemiFeedByBatchNo(F)).thenReturn(pending);
        when(stocktakeRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.initiate(F, req, USER);

        ArgumentCaptor<SemiFinishedStocktake> captor = ArgumentCaptor.forClass(SemiFinishedStocktake.class);
        verify(stocktakeRepo).saveAndFlush(captor.capture());
        return captor.getValue().getItems().get(0);
    }

    @Test
    @DisplayName("有待小结 SFI 投料: avail=1292, pending=387.6 → 账面=904.4 (不再假盘亏 387.6)")
    void snapshot_subtractsPendingFeed() {
        SemiFinishedStocktakeItem item = initiateAndGetItem("SFI-1", "1292",
                Map.of("SFI-1", new BigDecimal("387.6")));
        assertThat(item.getSystemQty()).isEqualByComparingTo("904.4");
    }

    @Test
    @DisplayName("无待小结投料 → 账面=availableQuantity (旧口径不变)")
    void snapshot_noPending_unchanged() {
        SemiFinishedStocktakeItem item = initiateAndGetItem("SFI-1", "1292", Map.of());
        assertThat(item.getSystemQty()).isEqualByComparingTo("1292");
    }

    @Test
    @DisplayName("超投 clamp: pending > available → 账面=0 (不产负数假盘盈; 小结本身会 SFI_INSUFFICIENT 拦)")
    void snapshot_overFeed_clampsToZero() {
        SemiFinishedStocktakeItem item = initiateAndGetItem("SFI-1", "100",
                Map.of("SFI-1", new BigDecimal("150")));
        assertThat(item.getSystemQty()).isEqualByComparingTo("0");
    }
}
