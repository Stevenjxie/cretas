package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProductionStockShortageDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionInputAllocationRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.impl.ProductionStockAllocationServiceImpl;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 闸 —— 短缺成因必须<b>三个构造点都算</b>，不能只有第一处。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 prod 实测)</h2>
 *
 * 短缺明细在 {@code ProductionStockAllocationServiceImpl} 有<b>三个</b>构造点：
 * 自动分摊(plan) / 指定批次投料 / BOM 自动投料。上一轮加成因标注时<b>只改了第一处</b>。
 *
 * <p>于是走 web 逐道录入撞到<b>辅料</b>短缺时，prod 返回的是：
 * <pre>
 * {"materialName":"…黄油鸡-香辛料","sourceType":"SEASONING","required":0.8,"available":0,
 *  "factoryOnHand":null,"cause":null}
 * message: …缺少 1.6kg，请联系仓管补料
 * </pre>
 * 而<b>全厂原料仓那两样各有 20kg</b> —— 又退回那句模糊的话，把人支去找仓管。
 *
 * <p>本仓硬约束 8「改共享结构前先数，改完再数，两个数必须相等」的原形：BEFORE=3，我改了 1。
 *
 * <h2>这道闸钉两层</h2>
 * <ol>
 *   <li><b>行为</b>：{@code withCause} 真的把成因算出来，且算不出在手量时留 null 而不是瞎标</li>
 *   <li><b>结构</b>：全文件只剩<b>一个</b> {@code new ProductionStockShortageDTO.Item(}，
 *       且它在 {@code withCause} 里 —— 防止<b>第四个</b>构造点又绕过去</li>
 * </ol>
 * ⚠️ 第 2 层是结构闸，先剥注释再数（本仓形态 A⁗：grep 会把讲这件事的注释也数进去）。
 */
class ShortageCauseAllSitesTest {

    private static final String F = "F006";
    private static final String MT = "RMT_SEASONING";
    private static final Path SRC = Path.of(
            "src/main/java/com/cretas/aims/service/processentry/impl/ProductionStockAllocationServiceImpl.java");

    private ProductionStockAllocationServiceImpl service;
    private MaterialBatchRepository batchRepo;

    @BeforeEach
    void setUp() {
        batchRepo = mock(MaterialBatchRepository.class);
        ProductionInputAllocationRepository allocRepo = mock(ProductionInputAllocationRepository.class);
        when(allocRepo.sumPendingQuantityByMaterialBatchId(anyString(), anyString())).thenReturn(null);

        UnitContractService contract = mock(UnitContractService.class);
        when(contract.describe(anyString(), anyString())).thenReturn(Optional.empty());
        when(contract.describe(anyString(), eq("kg"))).thenReturn(Optional.of(
                new CanonicalUnit("kg", UnitDimension.MASS, "kg", BigDecimal.ONE, "千克", 3)));

        service = new ProductionStockAllocationServiceImpl(
                batchRepo, allocRepo, mock(ProductionPlanRepository.class),
                mock(WarehouseResolver.class), contract, mock(RawMaterialTypeRepository.class));
    }

    private static MaterialBatch batch(String unit, String receipt) {
        MaterialBatch b = new MaterialBatch();
        b.setId("b-" + unit + receipt);
        b.setBatchNumber("MT-" + receipt);
        b.setFactoryId(F);
        b.setMaterialTypeId(MT);
        b.setQuantityUnit(unit);
        b.setReceiptQuantity(new BigDecimal(receipt));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        return b;
    }

    private ProductionStockShortageDTO.Item invoke(String available, String shortage) {
        return ReflectionTestUtils.invokeMethod(
                service, "withCause", F, MT, "SOP-20260817-01-黄油鸡-香辛料", "SEASONING",
                new BigDecimal("0.8"), new BigDecimal(available), new BigDecimal(shortage),
                "kg", "kg", true);
    }

    @Test
    @DisplayName("阳性对照: 全厂有货时算得出在手量 (否则下面的成因断言全是恒真)")
    void factoryOnHandIsComputed() {
        when(batchRepo.findAvailableBatchesFEFO(F, MT)).thenReturn(List.of(batch("kg", "20")));
        ProductionStockShortageDTO.Item it = invoke("0", "0.8");
        assertNotNull(it);
        assertNotNull(it.getFactoryOnHand(), "在手量没算出来");
        assertEquals(0, it.getFactoryOnHand().compareTo(new BigDecimal("20")),
                "实际 " + it.getFactoryOnHand());
    }

    @Test
    @DisplayName("🔴 辅料这一路也要标成因: 全厂 20kg / 生产仓 0 → 有货没领, 不是真没货")
    void seasoningShortageGetsNotRequisitioned() {
        when(batchRepo.findAvailableBatchesFEFO(F, MT)).thenReturn(List.of(batch("kg", "20")));
        ProductionStockShortageDTO.Item it = invoke("0", "0.8");
        assertEquals(ProductionStockShortageDTO.Cause.NOT_REQUISITIONED, it.getCause(),
                "辅料短缺没标成因 —— 文案会退回那句模糊的「请联系仓管补料」");
    }

    @Test
    @DisplayName("全厂也没有 → 真没货")
    void nothingAnywhereIsTrulyOutOfStock() {
        when(batchRepo.findAvailableBatchesFEFO(F, MT)).thenReturn(List.of());
        ProductionStockShortageDTO.Item it = invoke("0", "0.8");
        assertEquals(ProductionStockShortageDTO.Cause.TRULY_OUT_OF_STOCK, it.getCause());
    }

    @Test
    @DisplayName("🔴 算不出在手量时留 null, 不许瞎标 —— 且短缺本身不能被吞掉")
    void unresolvableOnHandLeavesCauseNull() {
        when(batchRepo.findAvailableBatchesFEFO(F, MT))
                .thenThrow(new IllegalStateException("repo 炸了"));
        ProductionStockShortageDTO.Item it = invoke("0", "0.8");
        assertNotNull(it, "在手量算不出就把整条短缺吞了 —— 那才是用户真正要看到的东西");
        assertNull(it.getCause(), "算不出在手量却标了成因");
        assertEquals(0, it.getShortage().compareTo(new BigDecimal("0.8")));
    }

    @Test
    @DisplayName("🔴 结构闸: 全文件只剩一个构造点, 且在 withCause 里 —— 防第四处绕过去")
    void onlyOneConstructionSiteAndItIsInsideWithCause() throws Exception {
        String raw = Files.readString(SRC);
        // 先剥注释 —— 否则会数到讲这件事的那几段说明 (本仓形态 A⁗)
        String src = raw.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
        int sites = src.split("new ProductionStockShortageDTO\\.Item\\(", -1).length - 1;
        assertEquals(1, sites,
                "短缺明细有 " + sites + " 个构造点。多出来的那个没走 withCause ⇒ 它的 cause 会是 null, "
                        + "文案退回模糊版。请改用 withCause(...)。");
        int helperAt = src.indexOf("private ProductionStockShortageDTO.Item withCause(");
        int siteAt = src.indexOf("new ProductionStockShortageDTO.Item(");
        assertTrue(helperAt > 0, "找不到 withCause, 这道闸在读空气");
        assertTrue(siteAt > helperAt, "唯一的构造点不在 withCause 里");
        // 三个调用点都还在
        int calls = src.split("withCause\\(", -1).length - 1;
        assertTrue(calls >= 4, "withCause 出现 " + calls + " 次 (应 = 1 定义 + 3 调用) —— 有调用点被摘掉了");
    }
}
