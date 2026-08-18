package com.cretas.aims.service.factory;

import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.factory.impl.FactoryMaterialRequisitionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 闸 —— 领料单要给调料<b>算出</b>需求量，不能留空让拣货员猜。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 prod 实测)</h2>
 *
 * 领料单里 AUXILIARY 那两行 {@code requiredQty} 一直是 {@code null}（源码里写着
 * 「推不出投入 kg，留空不拦单」），于是拣货员只能凭感觉填。
 * 主线实测：操作员填了 <b>1</b>，而按 80kg 投料 × 10 g/kg，正确答案是 <b>0.8 kg</b>。
 *
 * <p>包材那一路<b>是会算的</b>（{@code standard_quantity × 计划量}），
 * 说明计算通道本来就存在，只是调料这条 {@code dosagePerKgG} 的路没接。
 *
 * <h2>口径（必须写清，否则下一个人会以为它是权威值）</h2>
 * <ul>
 *   <li>分母 = 本单<b>已经算好的</b> RAW 明细行汇总（复用，⛔ 不从 BOM 再算一遍 → 形态 D）</li>
 *   <li>只认<b>质量单位</b>；计数单位的原料与 kg 没有通用换算，⛔ 不硬折，不计入分母</li>
 *   <li>是<b>单锅基准 / 下限</b>：计划阶段不知道分几锅，不含 {@code subsequentPotRatio}
 *       那一层放大 —— 报工时按真实锅次重算才是权威值</li>
 *   <li>算不出返回 {@code null}（保持原来的留空），<b>⛔ 不返回 0</b> ——
 *       0 是个看起来很确定的假答案</li>
 * </ul>
 */
class RequisitionComputesSeasoningQtyContractTest {

    private static FactoryMaterialRequisitionServiceImpl service() {
        return mock(FactoryMaterialRequisitionServiceImpl.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
    }

    private static FactoryMaterialRequisitionItem raw(String qty, String unit) {
        FactoryMaterialRequisitionItem i = new FactoryMaterialRequisitionItem();
        i.setMaterialCategory(FactoryMaterialRequisitionItem.MaterialCategory.RAW);
        i.setRequiredQty(qty == null ? null : new BigDecimal(qty));
        i.setUnit(unit);
        return i;
    }

    private static FactoryMaterialRequisitionItem other(
            FactoryMaterialRequisitionItem.MaterialCategory cat, String qty, String unit) {
        FactoryMaterialRequisitionItem i = new FactoryMaterialRequisitionItem();
        i.setMaterialCategory(cat);
        i.setRequiredQty(new BigDecimal(qty));
        i.setUnit(unit);
        return i;
    }

    private static FactoryMaterialRequisition mr(FactoryMaterialRequisitionItem... items) {
        FactoryMaterialRequisition m = new FactoryMaterialRequisition();
        m.setItems(new ArrayList<>(java.util.Arrays.asList(items)));
        return m;
    }

    private static BigDecimal inputKg(FactoryMaterialRequisition m) {
        return (BigDecimal) ReflectionTestUtils.invokeMethod(service(), "plannedRawInputKg", m);
    }

    private static BomSeasoningItem seasoning(String dosagePerKgG, String section, Boolean countIn) {
        BomSeasoningItem s = new BomSeasoningItem();
        s.setDosagePerKgG(dosagePerKgG == null ? null : new BigDecimal(dosagePerKgG));
        s.setSection(section);
        s.setCountInSeasoning(countIn);
        return s;
    }

    private static BigDecimal qty(BomSeasoningItem s, BigDecimal inputKg, String unit) {
        return (BigDecimal) ReflectionTestUtils.invokeMethod(
                service(), "seasoningRequiredQty", s, inputKg, unit);
    }

    @Test
    @DisplayName("阳性对照: 主线那个真实场景 —— 80kg 投料 × 10 g/kg = 0.8 kg (操作员当时填的是 1)")
    void mainlineScenarioComputesZeroPointEight() {
        BigDecimal kg = inputKg(mr(raw("20", "kg"), raw("20", "kg"), raw("20", "kg"), raw("20", "kg")));
        assertNotNull(kg, "投入 kg 算不出来, 后面全是恒真");
        assertEquals(0, kg.compareTo(new BigDecimal("80")), "投入 kg 不对: " + kg);

        BigDecimal need = qty(seasoning("10", null, true), kg, "kg");
        assertNotNull(need, "调料需求量还是留空的");
        assertEquals(0, need.compareTo(new BigDecimal("0.800")), "实际 " + need);
    }

    @Test
    @DisplayName("🔴 计数单位的原料不计入分母 —— 个/只/件 与 kg 没有通用换算, 不许硬折")
    void countUnitRawIsNotFoldedIntoKilograms() {
        BigDecimal kg = inputKg(mr(raw("20", "kg"), raw("500", "个")));
        assertEquals(0, kg.compareTo(new BigDecimal("20")), "把「个」硬折进了 kg: " + kg);
    }

    @Test
    @DisplayName("g / t / 斤 都要正确折成 kg")
    void massUnitsAreConverted() {
        assertEquals(0, inputKg(mr(raw("2000", "g"))).compareTo(new BigDecimal("2.000000")));
        assertEquals(0, inputKg(mr(raw("0.5", "t"))).compareTo(new BigDecimal("500.0")));
        assertEquals(0, inputKg(mr(raw("10", "斤"))).compareTo(new BigDecimal("5.000000")));
    }

    @Test
    @DisplayName("🔴 只有非 RAW 行时算不出投入 —— 返回 null 而不是 0")
    void nonRawRowsDoNotCountAndReturnNullNotZero() {
        BigDecimal kg = inputKg(mr(
                other(FactoryMaterialRequisitionItem.MaterialCategory.PACKAGING, "10", "片"),
                other(FactoryMaterialRequisitionItem.MaterialCategory.AUXILIARY, "1", "kg")));
        assertNull(kg, "把包材/辅料算进了投入原料, 或者返回了 0: " + kg);
    }

    @Test
    @DisplayName("🔴 算不出投入时调料需求量留空 —— ⛔ 不许写 0(那是个看起来很确定的假答案)")
    void unknownInputMeansBlankNotZero() {
        assertNull(qty(seasoning("10", null, true), null, "kg"));
        assertNull(qty(seasoning("10", null, true), BigDecimal.ZERO, "kg"));
    }

    @Test
    @DisplayName("配方没写用量 → 留空, 不猜")
    void missingDosageMeansBlank() {
        assertNull(qty(seasoning(null, null, true), new BigDecimal("80"), "kg"));
        assertNull(qty(seasoning("0", null, true), new BigDecimal("80"), "kg"));
    }

    @Test
    @DisplayName("与报工侧同口径: COOKING 段且 countInSeasoning=false 的不算需求")
    void cookingSectionNotCountedIsSkipped() {
        assertNull(qty(seasoning("10", BomSeasoningItem.SECTION_COOKING, false),
                new BigDecimal("80"), "kg"));
        assertNotNull(qty(seasoning("10", BomSeasoningItem.SECTION_COOKING, true),
                new BigDecimal("80"), "kg"), "计入的那类被误伤了");
    }

    @Test
    @DisplayName("🔴 接线闸: appendSeasoningItems 真的把算出来的数填进了明细行")
    void appendSeasoningItemsActuallyFillsTheComputedQuantity() {
        // 🔴 这条是变异逼出来的。第一版闸只测两个 helper, 变异
        //    `item.setRequiredQty(null)`（就是修复前的行为）**纹丝不动** ——
        //    零件对了、线没接上, 而闸是绿的。本仓形态 B。
        FactoryMaterialRequisitionServiceImpl svc = service();

        BomSeasoningItem s = seasoning("10", null, true);
        s.setMaterialTypeId("RMT_seasoning_1");
        s.setName("香辛料");
        BomSeasoningItemRepository repo = mock(BomSeasoningItemRepository.class);
        when(repo.findByRecipeIdOrderBySeqAsc("recipe-1")).thenReturn(List.of(s));
        ReflectionTestUtils.setField(svc, "bomSeasoningItemRepository", repo);

        BomRecipeItem bomItem = new BomRecipeItem();
        bomItem.setRecipeId("recipe-1");

        // 已经算好的 RAW 行 —— 主线那个场景: 4 × 20kg = 80kg
        FactoryMaterialRequisition m = mr(raw("20", "kg"), raw("20", "kg"),
                raw("20", "kg"), raw("20", "kg"));

        ReflectionTestUtils.invokeMethod(svc, "appendSeasoningItems",
                "F006", List.of(bomItem), m);

        List<FactoryMaterialRequisitionItem> aux = m.getItems().stream()
                .filter(i -> i.getMaterialCategory()
                        == FactoryMaterialRequisitionItem.MaterialCategory.AUXILIARY)
                .toList();
        assertEquals(1, aux.size(), "调料行没被追加进来: " + m.getItems().size());
        assertNotNull(aux.get(0).getRequiredQty(),
                "调料行还是留空的 —— helper 算得出不等于接上了");
        assertEquals(0, aux.get(0).getRequiredQty().compareTo(new BigDecimal("0.800")),
                "接上了但数不对: " + aux.get(0).getRequiredQty());
    }

    @Test
    @DisplayName("库存单位是 g 时按 g 给数; 不是质量单位(袋/瓶)时留空不硬折")
    void targetUnitIsRespectedAndNonMassIsBlank() {
        assertEquals(0, qty(seasoning("10", null, true), new BigDecimal("80"), "g")
                .compareTo(new BigDecimal("800.000")));
        assertNull(qty(seasoning("10", null, true), new BigDecimal("80"), "袋"),
                "把 g 硬折成了「袋」");
    }
}
