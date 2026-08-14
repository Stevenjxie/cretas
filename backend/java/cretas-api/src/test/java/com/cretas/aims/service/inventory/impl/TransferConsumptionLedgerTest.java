package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.inventory.InternalTransferItemRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调拨出库必须为**每一个**被扣减的批次留下消耗流水。
 *
 * <h3>为什么有这个文件(2026-08-14 生产实测)</h3>
 *
 * {@code deductSourceInventory} 此前只做 {@code setUsedQuantity(...add(deduct))} + 置 DEPLETED,
 * <b>不写任何 {@code MaterialConsumption}</b>。生产库实测后果: 100 个活跃批次
 * {@code used_quantity > 0}, 而 material_consumptions / material_batch_adjustments /
 * production_settlement_consumptions <b>三张表都查无痕迹</b>, 且本周仍在新增 9 个。
 * 对一个食品溯源系统, 这是"库存数字对得上, 但答不出这批料去哪了"。
 *
 * <h3>核心断言: FEFO 跨多个批次</h3>
 *
 * 单批次的情况就算没有流水, 靠 {@code item.sourceBatchId} 还能倒推。真正无解的是
 * <b>FEFO 一次扣多个批次</b> —— {@code sourceBatchId} 是单值列, 代码里也确实只记
 * {@code firstConsumedBatchId}, 第二个以后的批次在全库任何地方都不存在。
 * 所以本测试刻意构造"一次扣两个批次", 断言 <b>2 条</b>流水而不是 1 条。
 *
 * ⚠️ {@code materialConsumptionRepository} 是 {@code @Autowired(required = false)} ——
 * 若测试里不打桩, 它为 null, 生产代码会走 WARN 分支直接 return, 于是
 * "断言流水存在"会变成恒真式。本测试显式注入该 mock, 并另有一条断言证明
 * null 时不会把发货流程搞挂。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("调拨出库消耗流水")
class TransferConsumptionLedgerTest {

    private static final String FACTORY = "F006";
    private static final String MATERIAL_TYPE = "MT-BEEF";

    @Mock private InternalTransferRepository transferRepository;
    @Mock private InternalTransferItemRepository transferItemRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;

    private TransferServiceImpl service;
    private InternalTransfer transfer;

    private static MaterialBatch batch(String id, String number, double receipt, double used, double price) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setBatchNumber(number);
        b.setFactoryId(FACTORY);
        b.setMaterialTypeId(MATERIAL_TYPE);
        b.setReceiptQuantity(BigDecimal.valueOf(receipt));
        b.setUsedQuantity(BigDecimal.valueOf(used));
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setUnitPrice(BigDecimal.valueOf(price));
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        return b;
    }

    @BeforeEach
    void setUp() {
        // 7 参数构造器是本类既有单测的入口, 不动它; 剩余依赖按字段注入。
        service = new TransferServiceImpl(transferRepository, transferItemRepository,
                materialBatchRepository, null, null, null, null);
        ReflectionTestUtils.setField(service, "materialConsumptionRepository", materialConsumptionRepository);
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", inventoryLowStockEventPublisher);

        InternalTransferItem item = new InternalTransferItem();
        item.setItemType(TransferItemType.RAW_MATERIAL);
        item.setMaterialTypeId(MATERIAL_TYPE);
        item.setQuantity(BigDecimal.valueOf(15));   // 15 = 10(B1 全部) + 5(B2 部分) → 必然跨两个批次
        item.setSourceBatchId(null);                // 不预选 → 纯 FEFO

        transfer = new InternalTransfer();
        transfer.setId("TR-1");
        transfer.setTransferNumber("TRF-20260814-0001");
        transfer.setSourceFactoryId(FACTORY);
        transfer.setTargetFactoryId("F007");        // 跨厂, 才走 shipTransfer
        transfer.setStatus(TransferStatus.APPROVED);
        transfer.setItems(new ArrayList<>(List.of(item)));

        when(transferRepository.findByIdAndEitherFactoryId(anyString(), anyString()))
                .thenReturn(Optional.of(transfer));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));
        when(materialBatchRepository.saveAndFlush(any(MaterialBatch.class))).thenAnswer(i -> i.getArgument(0));
        when(materialBatchRepository.findAvailableBatchesFEFO(FACTORY, MATERIAL_TYPE))
                .thenReturn(new ArrayList<>(List.of(
                        batch("B1", "B-0001", 10, 0, 20),
                        batch("B2", "B-0002", 20, 0, 30))));
    }

    @Test
    @DisplayName("🔒 FEFO 扣了两个批次 → 必须有两条流水(单值 sourceBatchId 表达不了第二个)")
    void everyDeductedBatchGetsItsOwnConsumptionRow() {
        service.shipTransfer(FACTORY, "TR-1", 77L);

        ArgumentCaptor<MaterialConsumption> captor = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(materialConsumptionRepository, times(2)).save(captor.capture());

        List<MaterialConsumption> rows = captor.getAllValues();
        assertThat(rows).extracting(MaterialConsumption::getBatchId)
                .as("两个被扣的批次都要有自己的流水 —— 只写第一个正是这次要修的缺陷")
                .containsExactly("B1", "B2");
        assertThat(rows.get(0).getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
        assertThat(rows.get(1).getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(5));
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getFactoryId()).isEqualTo(FACTORY);
            assertThat(r.getSourceType()).isEqualTo("TRANSFER_OUT");
            assertThat(r.getRecordedBy()).isEqualTo(77L);
            assertThat(r.getConsumptionTime()).isNotNull();
        });
    }

    @Test
    @DisplayName("流水金额按各自批次单价算, 不是拿第一个批次的价钱套全部")
    void costUsesEachBatchOwnUnitPrice() {
        service.shipTransfer(FACTORY, "TR-1", 77L);

        ArgumentCaptor<MaterialConsumption> captor = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(materialConsumptionRepository, times(2)).save(captor.capture());

        // B1: 10 × 20 = 200 ; B2: 5 × 30 = 150
        assertThat(captor.getAllValues().get(0).getTotalCost()).isEqualByComparingTo(BigDecimal.valueOf(200));
        assertThat(captor.getAllValues().get(1).getTotalCost()).isEqualByComparingTo(BigDecimal.valueOf(150));
    }

    @Test
    @DisplayName("对照: 单值 sourceBatchId 确实只留得住第一个 —— 证明流水不是多余的")
    void theSingleValuedSourceBatchIdOnlyKeepsTheFirstBatch() {
        service.shipTransfer(FACTORY, "TR-1", 77L);

        assertThat(transfer.getItems().get(0).getSourceBatchId())
                .as("sourceBatchId 是单值列, 第二个被扣的批次它装不下 —— 这正是必须写流水的理由")
                .isEqualTo("B1");
    }

    @Test
    @DisplayName("repository 未注入时: 留 WARN 但不能把发货流程搞挂")
    void missingRepositoryDoesNotBreakShipping() {
        ReflectionTestUtils.setField(service, "materialConsumptionRepository", null);

        service.shipTransfer(FACTORY, "TR-1", 77L);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.SHIPPED);
        verify(materialConsumptionRepository, never()).save(any());
    }
}
