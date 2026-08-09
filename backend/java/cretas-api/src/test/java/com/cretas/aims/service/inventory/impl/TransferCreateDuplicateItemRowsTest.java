package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 同一物料在一张调拨单里写成多行 → 建单即拒 (2026-08-09 六膳门 prod 事故).
 *
 * <p><b>事故</b>: {@code TRF-20260809-1790} (LIUSHANMEN, 主仓→生产仓) 的 10 行明细里
 * 「金蒜牛排调味料 滚揉用」({@code RMT_1786268511123}) 出现两次, 各 1000 kg, 而主仓该原料
 * 只有一个批次共 1000 kg。三道闸全部放行, 因为它们都<b>按行</b>比库存:
 * 建单表单 {@code list.vue} 逐行 {@code quantity > _currentStock}, 后端
 * {@link TransferServiceImpl#ensureCreateQuantityAvailable} 逐行比, 详情页
 * {@code isStockShortage(row)} 逐行比 —— 每行 1000 ≤ 1000 都合法, 合计 2000 无人过问。
 * 直到用户点「确认调拨入库」, {@code deductSourceInventory} 第一行扣光批次、第二行 FEFO
 * 查询返回空, 才抛 {@code 原料库存不足: RMT_1786268511123, 需要 1000.0000, 缺少 1000.0000}
 * —— 缺口恰好等于需求量, 即"一个批次都没找到"。此时单据已 APPROVED, 明细不可编辑
 * (只有 DRAFT 可改数量), 用户只能取消重建。
 *
 * <p><b>判据</b>: 禁止重复行后, "逐行可用量" 与 "该物料合计需求" 恒等 —— 三道闸的口径
 * 差异从根上消失, 不需要在三处各写一份聚合逻辑 (写三份 = 三处各自漂移的入口)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransferService — 建单禁止同一物料重复行")
class TransferCreateDuplicateItemRowsTest {

    @Mock private InternalTransferRepository transferRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private static final String FACTORY_ID = "LIUSHANMEN";
    private static final String SOURCE_WAREHOUSE = "fc489fd3-468c-40cd-a229-fd1fccd23359";
    private static final String TARGET_WAREHOUSE = "c865c899-f228-4613-a86c-911475753888";
    private static final String DUPED_MATERIAL = "RMT_1786268511123";
    private static final Long USER_ID = 100L;

    private TransferServiceImpl newService() {
        return new TransferServiceImpl(transferRepository, null, materialBatchRepository,
                null, applicationEventPublisher, null, rawMaterialTypeRepository);
    }

    private CreateTransferRequest.TransferItemDTO rawItem(String materialTypeId, String name, String qty) {
        CreateTransferRequest.TransferItemDTO item = new CreateTransferRequest.TransferItemDTO();
        item.setItemType("RAW_MATERIAL");
        item.setMaterialTypeId(materialTypeId);
        item.setItemName(name);
        item.setQuantity(new BigDecimal(qty));
        item.setUnit("kg");
        item.setUnitPrice(BigDecimal.ONE);
        return item;
    }

    /** 事故原型: 同厂 BRANCH_TO_HQ + 显式仓库路由 (主仓→生产仓). */
    private CreateTransferRequest req(List<CreateTransferRequest.TransferItemDTO> items) {
        CreateTransferRequest r = new CreateTransferRequest();
        r.setTransferType("BRANCH_TO_HQ");
        r.setTargetFactoryId(FACTORY_ID);
        r.setSourceWarehouseId(SOURCE_WAREHOUSE);
        r.setTargetWarehouseId(TARGET_WAREHOUSE);
        r.setTransferDate(LocalDate.of(2026, 8, 9));
        r.setItems(items);
        return r;
    }

    private void stubMaterialLookup() {
        RawMaterialType material = new RawMaterialType();
        material.setFactoryId(FACTORY_ID);
        material.setUnit("kg");
        lenient().when(rawMaterialTypeRepository.findById(any())).thenReturn(Optional.of(material));
    }

    @Test
    @DisplayName("事故复现 — 同一原料两行各 1000kg, 建单即 400 拒绝, 不落库")
    void createTransfer_rejectsDuplicateMaterialRows() {
        TransferServiceImpl service = newService();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTransfer(FACTORY_ID, req(List.of(
                        rawItem(DUPED_MATERIAL, "金蒜牛排调味料 滚揉用", "1000"),
                        rawItem("RMT_1786268298741", "冰水", "1000"),
                        rawItem(DUPED_MATERIAL, "金蒜牛排调味料 滚揉用", "1000"))), USER_ID));

        assertEquals(400, ex.getCode().intValue(),
                "重复行应在建单阶段拒绝, 实际: " + ex.getCode() + " " + ex.getMessage());
        // 报错必须点名"是哪个物料", 且给出合计量 —— 事故里的 409 只吐了裸 id + "请先采购",
        // 把"单子里写重了"说成"库存不够", 指向的修法是错的。
        assertTrue(ex.getMessage().contains("金蒜牛排调味料 滚揉用"),
                "报错应点名重复的物料: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("2000"),
                "报错应给出该物料在本单的合计需求量: " + ex.getMessage());

        verify(transferRepository, never()).save(any(InternalTransfer.class));
        // 更强的判据: 重复行在任何库存查询之前就被拒 —— 不依赖库存快照, 与库存多少无关。
        verifyNoInteractions(materialBatchRepository);
    }

    @Test
    @DisplayName("成品同样按 productTypeId 判重")
    void createTransfer_rejectsDuplicateProductRows() {
        TransferServiceImpl service = newService();

        CreateTransferRequest.TransferItemDTO a = new CreateTransferRequest.TransferItemDTO();
        a.setItemType("FINISHED_GOODS");
        a.setProductTypeId("PT_001");
        a.setItemName("卤牛腱");
        a.setQuantity(new BigDecimal("20"));
        a.setUnit("kg");
        CreateTransferRequest.TransferItemDTO b = new CreateTransferRequest.TransferItemDTO();
        b.setItemType("FINISHED_GOODS");
        b.setProductTypeId("PT_001");
        b.setItemName("卤牛腱");
        b.setQuantity(new BigDecimal("5"));
        b.setUnit("kg");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTransfer(FACTORY_ID, req(List.of(a, b)), USER_ID));

        assertEquals(400, ex.getCode().intValue());
        assertTrue(ex.getMessage().contains("卤牛腱"), ex.getMessage());
        assertTrue(ex.getMessage().contains("25"), "合计 20+5=25: " + ex.getMessage());
        verify(transferRepository, never()).save(any(InternalTransfer.class));
    }

    /**
     * 阴性对照 —— 没有这条, "全都拒绝" 也能让上面两条通过。
     */
    @Test
    @DisplayName("阴性对照 — 不同物料的多行照常建单")
    void createTransfer_allowsDistinctMaterialRows() {
        stubMaterialLookup();
        lenient().when(transferRepository.save(any(InternalTransfer.class)))
                .thenAnswer(inv -> {
                    InternalTransfer t = inv.getArgument(0);
                    if (t.getId() == null) t.setId("T-NEW-1");
                    return t;
                });
        TransferServiceImpl service = newService();

        assertDoesNotThrow(() -> service.createTransfer(FACTORY_ID, req(List.of(
                rawItem(DUPED_MATERIAL, "金蒜牛排调味料 滚揉用", "1000"),
                rawItem("RMT_1786268298741", "冰水", "1000"))), USER_ID));

        verify(transferRepository, org.mockito.Mockito.atLeastOnce()).save(any(InternalTransfer.class));
    }
}
