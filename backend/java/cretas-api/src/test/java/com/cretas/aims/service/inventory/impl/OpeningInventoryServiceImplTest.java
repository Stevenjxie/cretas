package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.finance.OpeningApCorrectionRequest;
import com.cretas.aims.dto.finance.OpeningApCorrectionResult;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.dto.material.OpeningInventoryItem;
import com.cretas.aims.dto.material.OpeningInventoryRequest;
import com.cretas.aims.dto.material.OpeningInventoryResult;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * OpeningInventoryServiceImpl 单元测试 (期初建账 onboarding).
 *
 * 覆盖:
 *  1. 期初建账建批次 (无应付) + 过平衡的 借1403/贷4001 凭证 = Σ数量×单价
 *  2. 诚实-null: 未录单价的行建批次但不计入凭证金额
 *  3. 全部未录价 → 不过凭证 (只建批次)
 *  4. 幂等: 同 batchKey 重复提交 → 不双建/不双过账
 *  5. 幽灵应付修正: 红冲应付 + 补期初凭证 + 库存数量不动
 */
@DisplayName("OpeningInventoryServiceImpl 单元测试 (期初建账)")
@ExtendWith(MockitoExtension.class)
class OpeningInventoryServiceImplTest {

    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private VoucherService voucherService;
    @Mock private ArApService arApService;
    @Mock private MaterialBatchService materialBatchService;

    @InjectMocks private OpeningInventoryServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final Long USER_ID = 42L;
    private static final String WH_RAW = "WH-RAW-UUID";

    @BeforeEach
    void setUp() {
        // self-proxy 字段 (@Autowired @Lazy) — 单元测试直接指回自身 (直调 correctSingleOpeningAp)。
        ReflectionTestUtils.setField(service, "self", service);
    }

    private RawMaterialType materialType(String id) {
        RawMaterialType mt = new RawMaterialType();
        mt.setId(id);
        mt.setFactoryId(FACTORY_ID);
        mt.setUnit("kg");
        mt.setName("原料-" + id);
        return mt;
    }

    private OpeningInventoryItem item(String mtId, String qty, String price) {
        OpeningInventoryItem it = new OpeningInventoryItem();
        it.setMaterialTypeId(mtId);
        it.setWarehouseId(WH_RAW);
        it.setQuantity(new BigDecimal(qty));
        if (price != null) it.setUnitPrice(new BigDecimal(price));
        return it;
    }

    private void stubBatchCreation() {
        when(materialTypeRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.of(materialType(inv.getArgument(0))));
        when(materialBatchRepository.existsByBatchNumber(anyString())).thenReturn(false);
        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceDocIdOrderByBatchNumberAsc(
                anyString(), anyString(), anyString())).thenReturn(List.of());
        when(materialBatchRepository.save(any(MaterialBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------------------------------------------------------------
    // 1. 建批次 (无应付) + 过平衡的 借1403/贷4001 凭证 = Σ数量×单价
    // ---------------------------------------------------------------
    @Test
    @DisplayName("期初建账: 建批次不挂应付 + 过 借1403/贷4001 = Σ数量×单价")
    void createOpening_postsBalancedVoucher_noAp() {
        stubBatchCreation();
        when(voucherService.createManual(anyString(), any(), any(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Voucher.builder().id("V1").voucherNumber("PZ-2026-0001").build());

        OpeningInventoryRequest req = new OpeningInventoryRequest();
        // 100 × 12.5 = 1250.00 ; 40 × 3 = 120.00 ; total = 1370.00
        req.setItems(List.of(item("M1", "100", "12.50"), item("M2", "40", "3")));

        OpeningInventoryResult result = service.createOpeningInventory(FACTORY_ID, req, USER_ID);

        assertThat(result.getCreatedCount()).isEqualTo(2);
        assertThat(result.getPricedCount()).isEqualTo(2);
        assertThat(result.getUncostedCount()).isZero();
        assertThat(result.getTotalOpeningValue()).isEqualByComparingTo("1370.00");
        assertThat(result.getVoucherId()).isEqualTo("V1");
        assertThat(result.isIdempotentHit()).isFalse();

        // NO AP created — the whole point of the clean opening path.
        verifyNoInteractions(arApService);

        // 2 batches saved, all sourceDocType=OPENING, no supplier.
        ArgumentCaptor<MaterialBatch> batchCap = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository, times(2)).save(batchCap.capture());
        assertThat(batchCap.getAllValues()).allSatisfy(b -> {
            assertThat(b.getSourceDocType()).isEqualTo("OPENING");
            assertThat(b.getSupplierId()).isNull();
            assertThat(b.getSourceDocId()).isNotBlank();
        });

        // ONE voucher, balanced: debit 1403 == credit 4001 == 1370.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VoucherEntrySpec>> entriesCap = ArgumentCaptor.forClass(List.class);
        verify(voucherService, times(1)).createManual(eq(FACTORY_ID), eq(VoucherType.INVENTORY_STOCKTAKE),
                any(), entriesCap.capture(), eq("OPENING_INVENTORY"), anyString(), anyString(), eq(USER_ID));
        List<VoucherEntrySpec> entries = entriesCap.getValue();
        assertThat(entries).hasSize(2);
        VoucherEntrySpec debit = entries.stream().filter(e -> e.subjectCode().equals("1403")).findFirst().orElseThrow();
        VoucherEntrySpec credit = entries.stream().filter(e -> e.subjectCode().equals("4001")).findFirst().orElseThrow();
        assertThat(debit.debit()).isEqualByComparingTo("1370.00");
        assertThat(debit.credit()).isNull();
        assertThat(credit.credit()).isEqualByComparingTo("1370.00");
        assertThat(credit.debit()).isNull();

        // moving-avg baseline updated for each priced item.
        verify(materialBatchService, times(2)).recalculateMovingAvgPrice(anyString(), any(), any(), anyString());
    }

    // ---------------------------------------------------------------
    // 2. 诚实-null: 未录单价的行建批次但不计入凭证金额
    // ---------------------------------------------------------------
    @Test
    @DisplayName("诚实-null: 未录单价 → 建批次但排除出凭证金额")
    void createOpening_honestNull_excludesUnpricedFromVoucher() {
        stubBatchCreation();
        when(voucherService.createManual(anyString(), any(), any(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Voucher.builder().id("V2").voucherNumber("PZ-2026-0002").build());

        OpeningInventoryRequest req = new OpeningInventoryRequest();
        // M1 priced 100×10=1000 ; M2 NO price → batch created, excluded.
        req.setItems(List.of(item("M1", "100", "10"), item("M2", "55", null)));

        OpeningInventoryResult result = service.createOpeningInventory(FACTORY_ID, req, USER_ID);

        assertThat(result.getCreatedCount()).isEqualTo(2);   // both batches created
        assertThat(result.getPricedCount()).isEqualTo(1);
        assertThat(result.getUncostedCount()).isEqualTo(1);
        assertThat(result.getTotalOpeningValue()).isEqualByComparingTo("1000.00");

        // voucher only for the priced 1000 (no fabricated price for M2).
        ArgumentCaptor<List<VoucherEntrySpec>> entriesCap = ArgumentCaptor.forClass(List.class);
        verify(voucherService).createManual(anyString(), any(), any(), entriesCap.capture(),
                anyString(), anyString(), anyString(), any());
        VoucherEntrySpec debit = entriesCap.getValue().stream()
                .filter(e -> e.subjectCode().equals("1403")).findFirst().orElseThrow();
        assertThat(debit.debit()).isEqualByComparingTo("1000.00");
        // moving-avg only for the 1 priced item.
        verify(materialBatchService, times(1)).recalculateMovingAvgPrice(anyString(), any(), any(), anyString());
        verifyNoInteractions(arApService);
    }

    // ---------------------------------------------------------------
    // 3. 全部未录价 → 不过凭证 (只建批次)
    // ---------------------------------------------------------------
    @Test
    @DisplayName("全部未录价 → 不过凭证, 只建批次")
    void createOpening_allUncosted_noVoucher() {
        stubBatchCreation();

        OpeningInventoryRequest req = new OpeningInventoryRequest();
        req.setItems(List.of(item("M1", "10", null), item("M2", "20", null)));

        OpeningInventoryResult result = service.createOpeningInventory(FACTORY_ID, req, USER_ID);

        assertThat(result.getCreatedCount()).isEqualTo(2);
        assertThat(result.getUncostedCount()).isEqualTo(2);
        assertThat(result.getTotalOpeningValue()).isEqualByComparingTo("0");
        assertThat(result.getVoucherId()).isNull();
        verify(voucherService, never()).createManual(anyString(), any(), any(), any(), anyString(), anyString(), anyString(), any());
        verifyNoInteractions(arApService);
    }

    // ---------------------------------------------------------------
    // 4. 幂等: 同 batchKey 重复提交 → 不双建/不双过账
    // ---------------------------------------------------------------
    @Test
    @DisplayName("幂等: 同 batchKey 已存在 → 返回既有, 不双建不双过账")
    void createOpening_idempotent_returnsExisting() {
        MaterialBatch existing = new MaterialBatch();
        existing.setId("B-existing");
        existing.setBatchNumber("OPEN-1");
        existing.setReceiptQuantity(new BigDecimal("100"));
        existing.setUnitPrice(new BigDecimal("10"));
        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceDocIdOrderByBatchNumberAsc(
                eq(FACTORY_ID), eq("OPENING"), eq("KEY-1"))).thenReturn(List.of(existing));
        when(voucherService.findBySourceBusiness(eq("OPENING_INVENTORY"), eq("KEY-1")))
                .thenReturn(Optional.of(Voucher.builder().id("V-existing").voucherNumber("PZ-old").build()));

        OpeningInventoryRequest req = new OpeningInventoryRequest();
        req.setBatchKey("KEY-1");
        req.setItems(List.of(item("M1", "100", "10")));

        OpeningInventoryResult result = service.createOpeningInventory(FACTORY_ID, req, USER_ID);

        assertThat(result.isIdempotentHit()).isTrue();
        assertThat(result.getCreatedCount()).isEqualTo(1);
        assertThat(result.getVoucherId()).isEqualTo("V-existing");
        assertThat(result.getTotalOpeningValue()).isEqualByComparingTo("1000.00");
        // no new batch, no new voucher, no AP.
        verify(materialBatchRepository, never()).save(any());
        verify(voucherService, never()).createManual(anyString(), any(), any(), any(), anyString(), anyString(), anyString(), any());
        verifyNoInteractions(arApService);
    }

    // ---------------------------------------------------------------
    // 5. 幽灵应付修正: 红冲应付 + 补期初凭证 + 库存数量不动
    // ---------------------------------------------------------------
    @Test
    @DisplayName("幽灵应付修正: 红冲应付 + 补 借1403/贷4001 + 库存不动")
    void correctMisroutedAp_reversesAndPostsVoucher_noInventoryChange() {
        // reversal returned by ArApService: negative amount (red-reverse of a 436632 phantom AP).
        ArApTransaction reversal = new ArApTransaction();
        reversal.setId("REV-1");
        reversal.setTransactionType(ArApTransactionType.AP_CREDIT_NOTE);
        reversal.setCounterpartyType(CounterpartyType.SUPPLIER);
        reversal.setAmount(new BigDecimal("-436632.00"));
        when(arApService.reverseOpeningPayable(eq(FACTORY_ID), eq("AP-1"), any(), eq(USER_ID)))
                .thenReturn(reversal);
        when(voucherService.findBySourceBusiness(eq("OPENING_INVENTORY_AP_CORRECTION"), eq("AP-1")))
                .thenReturn(Optional.empty());
        when(voucherService.createManual(anyString(), any(), any(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Voucher.builder().id("VC-1").voucherNumber("PZ-corr").build());

        OpeningApCorrectionRequest req = new OpeningApCorrectionRequest();
        req.setApTransactionIds(List.of("AP-1"));
        req.setReason("期初存货误走采购入库");

        OpeningApCorrectionResult result = service.correctMisroutedOpeningAp(FACTORY_ID, req, USER_ID);

        assertThat(result.getCorrectedCount()).isEqualTo(1);
        assertThat(result.getSkippedCount()).isZero();
        assertThat(result.getTotalReversedAmount()).isEqualByComparingTo("436632.00");
        assertThat(result.getOutcomes().get(0).getStatus()).isEqualTo("CORRECTED");
        assertThat(result.getOutcomes().get(0).getVoucherId()).isEqualTo("VC-1");

        // opening voucher posted at the abs amount, balanced 1403/4001.
        ArgumentCaptor<List<VoucherEntrySpec>> entriesCap = ArgumentCaptor.forClass(List.class);
        verify(voucherService).createManual(eq(FACTORY_ID), eq(VoucherType.INVENTORY_STOCKTAKE), any(),
                entriesCap.capture(), eq("OPENING_INVENTORY_AP_CORRECTION"), eq("AP-1"), anyString(), eq(USER_ID));
        VoucherEntrySpec debit = entriesCap.getValue().stream()
                .filter(e -> e.subjectCode().equals("1403")).findFirst().orElseThrow();
        VoucherEntrySpec credit = entriesCap.getValue().stream()
                .filter(e -> e.subjectCode().equals("4001")).findFirst().orElseThrow();
        assertThat(debit.debit()).isEqualByComparingTo("436632.00");
        assertThat(credit.credit()).isEqualByComparingTo("436632.00");

        // inventory qty NOT touched — no material batch writes in the correction path.
        verify(materialBatchRepository, never()).save(any());
    }

    // ---------------------------------------------------------------
    // 5b. AP 修正: 类型不符 (非 AP_INVOICE) → 跳过, 不毁整批
    // ---------------------------------------------------------------
    @Test
    @DisplayName("AP 修正: 单笔校验失败 → SKIPPED, 不影响其余")
    void correctMisroutedAp_skipInvalid_doesNotDoomBatch() {
        // AP-bad throws (wrong type); AP-ok succeeds → independent per-id tx.
        when(arApService.reverseOpeningPayable(eq(FACTORY_ID), eq("AP-bad"), any(), eq(USER_ID)))
                .thenThrow(new com.cretas.aims.exception.BusinessException(400, "只能红冲应付挂账"));
        ArApTransaction ok = new ArApTransaction();
        ok.setId("REV-ok");
        ok.setAmount(new BigDecimal("-100.00"));
        when(arApService.reverseOpeningPayable(eq(FACTORY_ID), eq("AP-ok"), any(), eq(USER_ID))).thenReturn(ok);
        when(voucherService.findBySourceBusiness(eq("OPENING_INVENTORY_AP_CORRECTION"), eq("AP-ok")))
                .thenReturn(Optional.empty());
        when(voucherService.createManual(anyString(), any(), any(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Voucher.builder().id("VC-ok").voucherNumber("PZ").build());

        OpeningApCorrectionRequest req = new OpeningApCorrectionRequest();
        req.setApTransactionIds(List.of("AP-bad", "AP-ok"));

        OpeningApCorrectionResult result = service.correctMisroutedOpeningAp(FACTORY_ID, req, USER_ID);

        assertThat(result.getCorrectedCount()).isEqualTo(1);
        assertThat(result.getSkippedCount()).isEqualTo(1);
        assertThat(result.getTotalReversedAmount()).isEqualByComparingTo("100.00");
    }
}
