package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.TransferDiffDecision;
import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.enums.TransferType;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.entity.inventory.TransferDiffRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.TransferDiffRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 调拨差异找单服务单元测试。
 *
 * <p>覆盖:
 * <ol>
 *   <li>差异检测：实收 < 发货 → 产生差异记录（PENDING）</li>
 *   <li>无差异：实收 = 发货 → 空列表，不写 DB</li>
 *   <li>部分差异：多行 items，只有少收行产生记录</li>
 *   <li>幂等：同一 transferItemId 已有 PENDING 记录 → 跳过，不重复创建</li>
 *   <li>找单决策：FOUND_BACK → status=RESOLVED</li>
 *   <li>找单决策：WRITE_OFF_LOSS → status=RESOLVED</li>
 *   <li>找单决策：TRANSFER_DAMAGE → status=RESOLVED</li>
 *   <li>决策已处理（RESOLVED）再次决策 → 409 BusinessException</li>
 *   <li>工厂隔离：非 source/target 工厂 → 403 BusinessException</li>
 *   <li>null 实收量 → 视为无差异，跳过</li>
 * </ol>
 */
@DisplayName("TransferDiffServiceImpl — 调拨差异找单")
@ExtendWith(MockitoExtension.class)
class TransferDiffServiceImplTest {

    @Mock
    private TransferDiffRecordRepository diffRepository;

    private TransferDiffServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TransferDiffServiceImpl(diffRepository);
    }

    // ─── 辅助构建方法 ──────────────────────────────────────────────────────

    private InternalTransfer buildReceivedTransfer(long itemId, BigDecimal shipped, BigDecimal received) {
        InternalTransferItem item = new InternalTransferItem();
        item.setId(itemId);
        item.setItemType(TransferItemType.RAW_MATERIAL);
        item.setMaterialTypeId("MT-001");
        item.setItemName("猪舌");
        item.setQuantity(shipped);
        item.setReceivedQuantity(received);
        item.setUnit("kg");

        InternalTransfer transfer = new InternalTransfer();
        transfer.setId("TRF-TEST-001");
        transfer.setTransferNumber("TR-TEST-001");
        transfer.setSourceFactoryId("F001");
        transfer.setTargetFactoryId("F006");
        transfer.setStatus(TransferStatus.RECEIVED);
        transfer.setTransferType(TransferType.HQ_TO_BRANCH);
        transfer.setItems(new ArrayList<>(List.of(item)));
        return transfer;
    }

    private TransferDiffRecord buildDiffRecord(String id, String status) {
        TransferDiffRecord rec = new TransferDiffRecord();
        rec.setId(id);
        rec.setSourceFactoryId("F001");
        rec.setTargetFactoryId("F006");
        rec.setDiffNumber("TDIFF-F001-20261023-0001");
        rec.setTransferId("TRF-TEST-001");
        rec.setTransferItemId(10L);
        rec.setShippedQuantity(new BigDecimal("34000"));
        rec.setReceivedQuantity(new BigDecimal("31000"));
        rec.setDiffQuantity(new BigDecimal("3000"));
        rec.setUnit("kg");
        rec.setStatus(status);
        rec.setCreatedBy(99L);
        return rec;
    }

    // ─── Test 1: 差异检测 — 产生记录 ──────────────────────────────────────

    @Test
    @DisplayName("实收 < 发货 → 产生差异记录（PENDING），差异量 = 3000kg")
    void detectDiff_underReceive_generatesDiffRecord() {
        InternalTransfer transfer = buildReceivedTransfer(10L,
                new BigDecimal("34000"), new BigDecimal("31000"));

        when(diffRepository.findByTransferIdOrderByCreatedAtDesc("TRF-TEST-001"))
                .thenReturn(List.of());
        when(diffRepository.countPendingBySourceFactory("F001")).thenReturn(0L);
        when(diffRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<TransferDiffRecord> result = service.detectAndGenerateDiffs(transfer, 99L);

        assertThat(result).hasSize(1);
        TransferDiffRecord rec = result.get(0);
        assertThat(rec.getShippedQuantity()).isEqualByComparingTo("34000");
        assertThat(rec.getReceivedQuantity()).isEqualByComparingTo("31000");
        assertThat(rec.getDiffQuantity()).isEqualByComparingTo("3000");
        assertThat(rec.getStatus()).isEqualTo("PENDING");
        assertThat(rec.getSourceFactoryId()).isEqualTo("F001");
        assertThat(rec.getTargetFactoryId()).isEqualTo("F006");
        assertThat(rec.getCreatedBy()).isEqualTo(99L);
        assertThat(rec.getDiffNumber()).startsWith("TDIFF-");

        verify(diffRepository, times(1)).save(any());
    }

    // ─── Test 2: 无差异 ────────────────────────────────────────────────────

    @Test
    @DisplayName("实收 = 发货 → 空列表，不写 DB")
    void detectDiff_noShortage_returnsEmpty() {
        InternalTransfer transfer = buildReceivedTransfer(10L,
                new BigDecimal("100"), new BigDecimal("100"));

        List<TransferDiffRecord> result = service.detectAndGenerateDiffs(transfer, 99L);

        assertThat(result).isEmpty();
        verify(diffRepository, never()).save(any());
    }

    // ─── Test 3: 实收 > 发货（超收）— 不产生差异单 ────────────────────────

    @Test
    @DisplayName("实收 > 发货（超收）→ 不产生差异单")
    void detectDiff_overReceive_returnsEmpty() {
        InternalTransfer transfer = buildReceivedTransfer(10L,
                new BigDecimal("100"), new BigDecimal("105"));

        List<TransferDiffRecord> result = service.detectAndGenerateDiffs(transfer, 99L);

        assertThat(result).isEmpty();
        verify(diffRepository, never()).save(any());
    }

    // ─── Test 4: 部分差异（多 items，只有少收行产生记录）─────────────────

    @Test
    @DisplayName("多行 items: 一行少收一行正常 → 只产生一条差异记录")
    void detectDiff_partialShortage_onlyShortageItemGeneratesRecord() {
        // item 10: 少收
        InternalTransferItem itemShort = new InternalTransferItem();
        itemShort.setId(10L);
        itemShort.setItemType(TransferItemType.RAW_MATERIAL);
        itemShort.setItemName("猪舌");
        itemShort.setQuantity(new BigDecimal("1000"));
        itemShort.setReceivedQuantity(new BigDecimal("900"));
        itemShort.setUnit("kg");

        // item 20: 正常
        InternalTransferItem itemOk = new InternalTransferItem();
        itemOk.setId(20L);
        itemOk.setItemType(TransferItemType.RAW_MATERIAL);
        itemOk.setItemName("牛腱");
        itemOk.setQuantity(new BigDecimal("500"));
        itemOk.setReceivedQuantity(new BigDecimal("500"));
        itemOk.setUnit("kg");

        InternalTransfer transfer = new InternalTransfer();
        transfer.setId("TRF-002");
        transfer.setTransferNumber("TR-002");
        transfer.setSourceFactoryId("F001");
        transfer.setTargetFactoryId("F006");
        transfer.setStatus(TransferStatus.RECEIVED);
        transfer.setTransferType(TransferType.HQ_TO_BRANCH);
        transfer.setItems(new ArrayList<>(List.of(itemShort, itemOk)));

        when(diffRepository.findByTransferIdOrderByCreatedAtDesc("TRF-002"))
                .thenReturn(List.of());
        when(diffRepository.countPendingBySourceFactory("F001")).thenReturn(0L);
        when(diffRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<TransferDiffRecord> result = service.detectAndGenerateDiffs(transfer, 99L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getItemName()).isEqualTo("猪舌");
        assertThat(result.get(0).getDiffQuantity()).isEqualByComparingTo("100");
        verify(diffRepository, times(1)).save(any());
    }

    // ─── Test 5: 幂等 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("同一 transferItemId 已有 PENDING → 幂等跳过，不重复创建")
    void detectDiff_idempotent_skipsExistingPending() {
        InternalTransfer transfer = buildReceivedTransfer(10L,
                new BigDecimal("34000"), new BigDecimal("31000"));

        TransferDiffRecord existing = buildDiffRecord("EXIST-1", "PENDING");
        when(diffRepository.findByTransferIdOrderByCreatedAtDesc("TRF-TEST-001"))
                .thenReturn(List.of(existing));

        List<TransferDiffRecord> result = service.detectAndGenerateDiffs(transfer, 99L);

        assertThat(result).isEmpty();
        verify(diffRepository, never()).save(any());
    }

    // ─── Test 6: 决策 FOUND_BACK ──────────────────────────────────────────

    @Test
    @DisplayName("决策 FOUND_BACK → status=RESOLVED, decision=FOUND_BACK")
    void decideRecord_foundBack_resolvesRecord() {
        TransferDiffRecord rec = buildDiffRecord("DIFF-1", "PENDING");
        when(diffRepository.findById("DIFF-1")).thenReturn(Optional.of(rec));
        when(diffRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransferDiffRecord result = service.decideRecord("DIFF-1", "F001",
                TransferDiffDecision.FOUND_BACK, "货物在途找回", 5L);

        assertThat(result.getStatus()).isEqualTo("RESOLVED");
        assertThat(result.getDecision()).isEqualTo(TransferDiffDecision.FOUND_BACK);
        assertThat(result.getDecisionBy()).isEqualTo(5L);
        assertThat(result.getDecisionNotes()).isEqualTo("货物在途找回");
        assertThat(result.getDecisionAt()).isNotNull();
    }

    // ─── Test 7: 决策 WRITE_OFF_LOSS ──────────────────────────────────────

    @Test
    @DisplayName("决策 WRITE_OFF_LOSS → status=RESOLVED")
    void decideRecord_writeOffLoss_resolvesRecord() {
        TransferDiffRecord rec = buildDiffRecord("DIFF-2", "PENDING");
        when(diffRepository.findById("DIFF-2")).thenReturn(Optional.of(rec));
        when(diffRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransferDiffRecord result = service.decideRecord("DIFF-2", "F006",
                TransferDiffDecision.WRITE_OFF_LOSS, "运输途中自然损耗", 7L);

        assertThat(result.getStatus()).isEqualTo("RESOLVED");
        assertThat(result.getDecision()).isEqualTo(TransferDiffDecision.WRITE_OFF_LOSS);
    }

    // ─── Test 8: 决策 TRANSFER_DAMAGE ─────────────────────────────────────

    @Test
    @DisplayName("决策 TRANSFER_DAMAGE → status=RESOLVED")
    void decideRecord_transferDamage_resolvesRecord() {
        TransferDiffRecord rec = buildDiffRecord("DIFF-3", "PENDING");
        when(diffRepository.findById("DIFF-3")).thenReturn(Optional.of(rec));
        when(diffRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransferDiffRecord result = service.decideRecord("DIFF-3", "F001",
                TransferDiffDecision.TRANSFER_DAMAGE, "货物破损转报损", 8L);

        assertThat(result.getStatus()).isEqualTo("RESOLVED");
        assertThat(result.getDecision()).isEqualTo(TransferDiffDecision.TRANSFER_DAMAGE);
    }

    // ─── Test 9: 重复决策 409 ──────────────────────────────────────────────

    @Test
    @DisplayName("已处理（RESOLVED）再次决策 → 409 BusinessException")
    void decideRecord_alreadyResolved_throws409() {
        TransferDiffRecord rec = buildDiffRecord("DIFF-4", "RESOLVED");
        when(diffRepository.findById("DIFF-4")).thenReturn(Optional.of(rec));

        assertThatThrownBy(() ->
                service.decideRecord("DIFF-4", "F001", TransferDiffDecision.FOUND_BACK, null, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("RESOLVED");
    }

    // ─── Test 10: 工厂隔离 403 ────────────────────────────────────────────

    @Test
    @DisplayName("非 source/target 工厂操作 → 403 BusinessException")
    void decideRecord_wrongFactory_throws403() {
        TransferDiffRecord rec = buildDiffRecord("DIFF-5", "PENDING");
        when(diffRepository.findById("DIFF-5")).thenReturn(Optional.of(rec));

        assertThatThrownBy(() ->
                service.decideRecord("DIFF-5", "F999",  // 既不是 F001 也不是 F006
                        TransferDiffDecision.FOUND_BACK, null, 5L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
    }

    // ─── Test 11: null 实收量 → 跳过 ──────────────────────────────────────

    @Test
    @DisplayName("item.receivedQuantity = null → 视为无差异，跳过")
    void detectDiff_nullReceivedQuantity_skips() {
        InternalTransfer transfer = buildReceivedTransfer(10L, new BigDecimal("100"), null);
        // override: set receivedQuantity=null
        transfer.getItems().get(0).setReceivedQuantity(null);

        List<TransferDiffRecord> result = service.detectAndGenerateDiffs(transfer, 99L);

        assertThat(result).isEmpty();
        verify(diffRepository, never()).save(any());
    }
}
