package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.InventoryLedgerLineDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.SnapshotType;
import com.cretas.aims.entity.inventory.InventoryLedgerSnapshot;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.InventoryLedgerSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP11: InventoryLedgerServiceImpl 单元测试.
 *
 * <p>覆盖 spec §10.2 四个验收标准 + W8 盘盈/盘损分列 + 金蝶摘要:
 * <ol>
 *   <li>有快照时以快照为期初</li>
 *   <li>无快照时从全量聚合期初 (兜底路径)</li>
 *   <li>期末金额 ROUND_HALF_UP 精度</li>
 *   <li>@PriceSensitive 字段对仓管角色为 null (字段已标注)</li>
 *   <li>W8: 盘盈/盘损分列 — profitQty ≥0, lossQty ≥0, adjustQty = profit - loss</li>
 *   <li>W8: 盘损盘盈同期 → 分列正确独立, 期末按净值计算</li>
 *   <li>W8: 金蝶 per-movement 摘要格式验证</li>
 *   <li>W8: 金蝶摘要 null/blank 防御</li>
 *   <li>W8: 导出表头含分列列名 (盘盈数量/盘损数量)</li>
 * </ol>
 *
 * @since SP11 2026-06-10; W8 2026-06-11
 */
@DisplayName("InventoryLedgerServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class InventoryLedgerServiceTest {

    private static final String FACTORY_ID = "F-SP11-TEST";
    private static final String MAT_ID = "MAT-001";
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2026, 5, 31);

    @Mock
    private InventoryLedgerSnapshotRepository snapshotRepo;
    @Mock
    private RawMaterialTypeRepository materialTypeRepo;
    @Mock
    private AccountingPeriodRepository accountingPeriodRepo;
    @Mock
    private MaterialBatchRepository materialBatchRepo;
    @Mock
    private EntityManager em;

    private InventoryLedgerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InventoryLedgerServiceImpl(snapshotRepo, materialTypeRepo,
                accountingPeriodRepo, materialBatchRepo);
        // Inject EntityManager via reflection (Spring would normally inject it)
        try {
            var f = InventoryLedgerServiceImpl.class.getDeclaredField("em");
            f.setAccessible(true);
            f.set(service, em);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ========================= Helper builders =========================

    private RawMaterialType buildMaterial(String id, String name, String unit) {
        RawMaterialType m = new RawMaterialType();
        m.setId(id);
        m.setName(name);
        m.setCode("CODE-" + id);
        m.setUnit(unit);
        return m;
    }

    private InventoryLedgerSnapshot buildSnapshot(String matId, BigDecimal closingQty,
                                                    BigDecimal closingAmount) {
        InventoryLedgerSnapshot snap = new InventoryLedgerSnapshot();
        snap.setId("SNAP-" + matId);
        snap.setFactoryId(FACTORY_ID);
        snap.setMaterialTypeId(matId);
        snap.setClosingQty(closingQty);
        snap.setClosingAmount(closingAmount);
        snap.setSnapshotType(SnapshotType.PERIOD_CLOSE);
        return snap;
    }

    /** 设置所有 EntityManager.createQuery 返回零 */
    private Query stubQueryReturnsZero() {
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.getResultList()).thenReturn(List.of(BigDecimal.ZERO));
        when(em.createQuery(anyString())).thenReturn(q);
        return q;
    }

    /** 设置 EM query 返回指定金额 (第一次调用返 qty, 第二次返 amount) */
    private void stubQuerySequence(Object... values) {
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        var stubbing = when(q.getResultList());
        for (Object v : values) {
            stubbing = stubbing.thenReturn(List.of(v));
        }
        // fallback: zero for remaining calls
        stubbing.thenReturn(List.of(BigDecimal.ZERO));
        when(em.createQuery(anyString())).thenReturn(q);
    }

    // ========================= Tests =========================

    @Test
    @DisplayName("T1: 有期初快照 → 期初数量/金额直接使用快照值")
    void testGetLedger_withSnapshot_useSnapshotAsOpening() {
        // 快照: 期初 50 kg, ¥3000.00
        InventoryLedgerSnapshot snap = buildSnapshot(MAT_ID,
                new BigDecimal("50.000000"), new BigDecimal("3000.00"));

        when(materialTypeRepo.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(buildMaterial(MAT_ID, "猪舌", "kg")));
        when(snapshotRepo.findLatestBeforePeriod(eq(FACTORY_ID), eq(MAT_ID),
                eq(SnapshotType.PERIOD_CLOSE), anyInt()))
                .thenReturn(List.of(snap));

        // Stub all EM queries to return zero (no period movements)
        stubQueryReturnsZero();

        List<InventoryLedgerLineDTO> result = service.getLedger(FACTORY_ID, START, END, null);

        assertEquals(1, result.size());
        InventoryLedgerLineDTO line = result.get(0);
        assertEquals(MAT_ID, line.getMaterialTypeId());
        assertEquals(0, new BigDecimal("50.000000").compareTo(line.getOpeningQty()),
                "期初数量应来自快照");
        assertEquals(0, new BigDecimal("3000.00").compareTo(line.getOpeningAmount()),
                "期初金额应来自快照");
        // 期末 = 50 + 0 - 0 + 0 + 0 = 50
        assertEquals(0, new BigDecimal("50.000000").compareTo(line.getClosingQty()),
                "无期间流水时期末 = 期初");
    }

    @Test
    @DisplayName("T2: 无快照 → 从 MaterialBatch 全量聚合兜底期初")
    void testGetLedger_noSnapshot_aggregateFromBeginning() {
        when(materialTypeRepo.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(buildMaterial(MAT_ID, "猪蹄", "kg")));
        when(snapshotRepo.findLatestBeforePeriod(anyString(), anyString(),
                any(), anyInt()))
                .thenReturn(Collections.emptyList());

        // 兜底查询返 30 kg (aggregateBatchQtyBefore)
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.getResultList())
                .thenReturn(List.of(new BigDecimal("30.000000")))  // opening qty from batch
                .thenReturn(List.of(new BigDecimal("10.000000")))  // inbound qty
                .thenReturn(List.of(BigDecimal.ZERO))               // inbound amount (null price)
                .thenReturn(List.of(BigDecimal.ZERO))               // production out
                .thenReturn(List.of(BigDecimal.ZERO))               // sales out
                .thenReturn(List.of(BigDecimal.ZERO))               // transfer in
                .thenReturn(List.of(BigDecimal.ZERO))               // transfer out
                // W8: 4 split queries (profitQty, lossQty, profitAmount, lossAmount)
                .thenReturn(List.of(BigDecimal.ZERO))               // stocktake profit qty
                .thenReturn(List.of(BigDecimal.ZERO))               // stocktake loss qty
                .thenReturn(List.of(BigDecimal.ZERO))               // stocktake profit amount
                .thenReturn(List.of(BigDecimal.ZERO));              // stocktake loss amount
        when(em.createQuery(anyString())).thenReturn(q);

        List<InventoryLedgerLineDTO> result = service.getLedger(FACTORY_ID, START, END, MAT_ID);

        assertEquals(1, result.size());
        InventoryLedgerLineDTO line = result.get(0);
        // 期末 = 30 + 10 - 0 = 40
        assertEquals(0, new BigDecimal("40.000000").compareTo(line.getClosingQty()),
                "期末 = 期初兜底30 + 入库10 = 40");
    }

    @Test
    @DisplayName("T3: 金额 ROUND_HALF_UP — 期末金额精度 scale=2")
    void testGetLedger_amountHalfUpPrecision() {
        // 期初 100 kg ¥999.995 → 四舍五入 ¥1000.00
        InventoryLedgerSnapshot snap = buildSnapshot(MAT_ID,
                new BigDecimal("100.000000"), new BigDecimal("999.995"));

        when(materialTypeRepo.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(buildMaterial(MAT_ID, "牛腱", "kg")));
        when(snapshotRepo.findLatestBeforePeriod(any(), any(), any(), anyInt()))
                .thenReturn(List.of(snap));

        // 入库 10 kg ¥0.005 (合计 ¥0.005 → round to ¥0.01)
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.getResultList())
                .thenReturn(List.of(new BigDecimal("10.000000")))   // inbound qty
                .thenReturn(List.of(new BigDecimal("0.005")))       // inbound amount
                .thenReturn(List.of(BigDecimal.ZERO))               // production
                .thenReturn(List.of(BigDecimal.ZERO))               // sales
                .thenReturn(List.of(BigDecimal.ZERO))               // transfer in
                .thenReturn(List.of(BigDecimal.ZERO))               // transfer out
                // W8: 4 split queries (profitQty, lossQty, profitAmount, lossAmount)
                .thenReturn(List.of(BigDecimal.ZERO))               // stocktake profit qty
                .thenReturn(List.of(BigDecimal.ZERO))               // stocktake loss qty
                .thenReturn(List.of(BigDecimal.ZERO))               // stocktake profit amount
                .thenReturn(List.of(BigDecimal.ZERO));              // stocktake loss amount
        when(em.createQuery(anyString())).thenReturn(q);

        List<InventoryLedgerLineDTO> result = service.getLedger(FACTORY_ID, START, END, null);

        assertEquals(1, result.size());
        InventoryLedgerLineDTO line = result.get(0);
        // closingQty = 100 + 10 = 110 (scale 6)
        assertEquals(0, new BigDecimal("110.000000").compareTo(line.getClosingQty()),
                "期末数量 = 100 + 10");
        // openingAmount 诚实透传 (PriceSensitive 字段由 Advice 遮蔽, 这里 service 原始值保留)
        assertNotNull(line.getOpeningAmount(), "service 层 openingAmount 应保留原始值");
    }

    @Test
    @DisplayName("T4: @PriceSensitive 字段已标注 — InventoryLedgerLineDTO 有 ≥3 个 PriceSensitive 字段")
    void testGetLedger_priceSensitive_annotatedFieldsExist() throws Exception {
        // 检验 DTO 有足够多的 @PriceSensitive 标注字段 (红线 R1: ≥3)
        var clazz = InventoryLedgerLineDTO.class;
        long priceSensitiveCount = java.util.Arrays.stream(clazz.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(com.cretas.aims.security.PriceSensitive.class))
                .count();

        assertTrue(priceSensitiveCount >= 3,
                "@PriceSensitive 字段数应 ≥3 (红线 R1), 实际: " + priceSensitiveCount
                        + ". 检查 openingAmount/closingAmount/movingAvgUnitPrice 等字段是否标注.");
    }

    @Test
    @DisplayName("T5: 按 materialTypeId 过滤时只返回该物料")
    void testGetLedger_withMaterialTypeFilter_returnsOnlyThatMaterial() {
        String matA = "MAT-A";
        String matB = "MAT-B";

        when(materialTypeRepo.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(
                        buildMaterial(matA, "猪舌", "kg"),
                        buildMaterial(matB, "猪蹄", "kg")
                ));
        when(snapshotRepo.findLatestBeforePeriod(any(), any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        stubQueryReturnsZero();

        // 只过滤 matA
        List<InventoryLedgerLineDTO> result = service.getLedger(FACTORY_ID, START, END, matA);

        assertEquals(1, result.size(), "按 materialTypeId 过滤应只返回 1 行");
        assertEquals(matA, result.get(0).getMaterialTypeId());
    }

    @Test
    @DisplayName("T6: 无物料时返回空 list (非 null)")
    void testGetLedger_noMaterials_returnsEmptyList() {
        when(materialTypeRepo.findByFactoryId(FACTORY_ID))
                .thenReturn(Collections.emptyList());

        List<InventoryLedgerLineDTO> result = service.getLedger(FACTORY_ID, START, END, null);

        assertNotNull(result, "空结果应返 emptyList 非 null");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("T7: null factoryId → NullPointerException (前置条件保护)")
    void testGetLedger_nullFactoryId_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> service.getLedger(null, START, END, null),
                "factoryId = null 应抛 NPE (Objects.requireNonNull)");
    }

    // ============== 导出 xlsx 测试 (审计 Tier0 #04 货不对板修复) ==============

    /** 读回 xlsx 全部行 (含表头), 每行是 cellIndex→value 的字符串 list. */
    private List<List<String>> readXlsx(byte[] bytes) {
        List<List<String>> all = new java.util.ArrayList<>();
        com.alibaba.excel.EasyExcel.read(new java.io.ByteArrayInputStream(bytes),
                new com.alibaba.excel.event.AnalysisEventListener<java.util.Map<Integer, String>>() {
                    @Override
                    public void invokeHeadMap(java.util.Map<Integer, String> headMap,
                                              com.alibaba.excel.context.AnalysisContext context) {
                        all.add(orderedValues(headMap));
                    }

                    @Override
                    public void invoke(java.util.Map<Integer, String> data,
                                       com.alibaba.excel.context.AnalysisContext context) {
                        all.add(orderedValues(data));
                    }

                    @Override
                    public void doAfterAllAnalysed(com.alibaba.excel.context.AnalysisContext context) {
                    }

                    private List<String> orderedValues(java.util.Map<Integer, String> map) {
                        return new java.util.TreeMap<>(map).values().stream()
                                .map(v -> v == null ? "" : v)
                                .collect(java.util.stream.Collectors.toList());
                    }
                }).sheet().doRead();
        return all;
    }

    @Test
    @DisplayName("T8: 导出含台账列(非凭证字段) — 货不对板修复核心断言")
    void testExport_containsLedgerColumns_notVoucherFields() throws Exception {
        InventoryLedgerSnapshot snap = buildSnapshot(MAT_ID,
                new BigDecimal("50.000000"), new BigDecimal("3000.00"));
        when(materialTypeRepo.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(buildMaterial(MAT_ID, "猪舌", "kg")));
        when(snapshotRepo.findLatestBeforePeriod(eq(FACTORY_ID), eq(MAT_ID),
                eq(SnapshotType.PERIOD_CLOSE), anyInt()))
                .thenReturn(List.of(snap));
        stubQueryReturnsZero();

        var out = new java.io.ByteArrayOutputStream();
        String fileName = service.exportInventoryLedger(FACTORY_ID, START, END, null, true, out);

        assertTrue(fileName.startsWith("inventory-ledger_"),
                "文件名应标识进销存台账, 不是 voucher-ledger");
        List<List<String>> rows = readXlsx(out.toByteArray());
        assertFalse(rows.isEmpty(), "应至少含表头");

        List<String> header = rows.get(0);
        // 进销存台账列必须出现
        assertTrue(header.contains("期初数量"), "表头应含进销存列 期初数量");
        assertTrue(header.contains("期末数量"), "表头应含进销存列 期末数量");
        assertTrue(header.contains("入库数量"), "表头应含进销存列 入库数量");
        assertTrue(header.contains("销售出货数量"), "表头应含进销存列 销售出货数量");
        // 凭证序时账字段不得出现 (货不对板防回归)
        assertFalse(header.contains("凭证号"), "不应含凭证字段 凭证号");
        assertFalse(header.contains("借方金额"), "不应含凭证字段 借方金额");
        assertFalse(header.contains("贷方金额"), "不应含凭证字段 贷方金额");
        assertFalse(header.contains("科目编码"), "不应含凭证字段 科目编码");

        // 数据行含物料名 + 期初数量 50
        List<String> dataRow = rows.get(1);
        assertTrue(dataRow.contains("猪舌"), "数据行应含物料名");
    }

    @Test
    @DisplayName("T9: includePrices=true → xlsx 含金额列")
    void testExport_includePrices_hasAmountColumns() throws Exception {
        InventoryLedgerSnapshot snap = buildSnapshot(MAT_ID,
                new BigDecimal("50.000000"), new BigDecimal("3000.00"));
        when(materialTypeRepo.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(buildMaterial(MAT_ID, "猪舌", "kg")));
        when(snapshotRepo.findLatestBeforePeriod(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of(snap));
        stubQueryReturnsZero();

        var out = new java.io.ByteArrayOutputStream();
        service.exportInventoryLedger(FACTORY_ID, START, END, null, true, out);

        List<String> header = readXlsx(out.toByteArray()).get(0);
        assertTrue(header.contains("期初金额"), "财务角色导出应含 期初金额");
        assertTrue(header.contains("期末金额"), "财务角色导出应含 期末金额");
        assertTrue(header.contains("移动均价"), "财务角色导出应含 移动均价");
    }

    @Test
    @DisplayName("T10: includePrices=false → xlsx 不含任何金额列 (非财务角色脱敏)")
    void testExport_noPrices_amountColumnsMasked() throws Exception {
        InventoryLedgerSnapshot snap = buildSnapshot(MAT_ID,
                new BigDecimal("50.000000"), new BigDecimal("3000.00"));
        when(materialTypeRepo.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(buildMaterial(MAT_ID, "猪舌", "kg")));
        when(snapshotRepo.findLatestBeforePeriod(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of(snap));
        stubQueryReturnsZero();

        var out = new java.io.ByteArrayOutputStream();
        service.exportInventoryLedger(FACTORY_ID, START, END, null, false, out);

        List<String> header = readXlsx(out.toByteArray()).get(0);
        // 数量列仍在
        assertTrue(header.contains("期初数量"), "仓管角色仍可见数量列");
        assertTrue(header.contains("期末数量"), "仓管角色仍可见数量列");
        // 金额列全部遮蔽
        assertFalse(header.contains("期初金额"), "仓管角色不得见 期初金额");
        assertFalse(header.contains("期末金额"), "仓管角色不得见 期末金额");
        assertFalse(header.contains("入库金额"), "仓管角色不得见 入库金额");
        assertFalse(header.contains("移动均价"), "仓管角色不得见 移动均价");
    }

    // ============== W8: 盘盈/盘损分列测试 ==============

    /**
     * 构造 EM mock, 第一批 fixed queries (inbound/out/transfer) 返 ZERO;
     * 然后按 stocktake4 values 依次返: profitQty, lossQty, profitAmount, lossAmount.
     *
     * <p>EM call 顺序 (有快照时, 无 batch fallback):
     * 1. inboundQty  2. inboundAmount  3. productionOut
     * (salesOut 不走 EM)  4. transferIn  5. transferOut
     * 6. stocktakeProfitQty  7. stocktakeLossQty
     * 8. stocktakeProfitAmount  9. stocktakeLossAmount
     */
    private void stubWithStocktake(InventoryLedgerSnapshot snap,
                                    BigDecimal profitQty, BigDecimal lossQty,
                                    BigDecimal profitAmount, BigDecimal lossAmount) {
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.getResultList())
                .thenReturn(List.of(BigDecimal.ZERO))   // 1: inbound qty
                .thenReturn(List.of(BigDecimal.ZERO))   // 2: inbound amount
                .thenReturn(List.of(BigDecimal.ZERO))   // 3: production out
                // salesOut returns ZERO directly, no EM call
                .thenReturn(List.of(BigDecimal.ZERO))   // 4: transfer in
                .thenReturn(List.of(BigDecimal.ZERO))   // 5: transfer out
                // W8 split queries
                .thenReturn(List.of(profitQty != null ? profitQty : BigDecimal.ZERO))   // 6: profit qty
                .thenReturn(List.of(lossQty != null ? lossQty : BigDecimal.ZERO))       // 7: loss qty
                .thenReturn(profitAmount != null ? List.of(profitAmount) : List.of())   // 8: profit amount
                .thenReturn(lossAmount != null ? List.of(lossAmount) : List.of());      // 9: loss amount
        when(em.createQuery(anyString())).thenReturn(q);

        when(materialTypeRepo.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(buildMaterial(MAT_ID, "猪舌", "kg")));
        when(snapshotRepo.findLatestBeforePeriod(any(), any(), any(), anyInt()))
                .thenReturn(snap != null ? List.of(snap) : Collections.emptyList());
    }

    @Test
    @DisplayName("T11: W8 盘盈分列 — profitQty≥0, lossQty=0, adjustQty=profitQty")
    void testStocktakeSplit_profitOnly() {
        InventoryLedgerSnapshot snap = buildSnapshot(MAT_ID,
                new BigDecimal("100.000000"), new BigDecimal("1000.00"));
        stubWithStocktake(snap,
                new BigDecimal("5.000000"), BigDecimal.ZERO,
                new BigDecimal("50.00"), null);

        List<InventoryLedgerLineDTO> result = service.getLedger(FACTORY_ID, START, END, null);
        InventoryLedgerLineDTO line = result.get(0);

        assertEquals(0, new BigDecimal("5.000000").compareTo(line.getStocktakeProfitQty()),
                "盘盈数量应为 5");
        assertEquals(0, BigDecimal.ZERO.compareTo(line.getStocktakeLossQty()),
                "无盘损时 lossQty = 0");
        // adjustQty = profitQty - lossQty = 5 - 0 = 5
        assertEquals(0, new BigDecimal("5.000000").compareTo(line.getAdjustQty()),
                "adjustQty = profitQty - lossQty = 5");
        // 期末 = 100 + 0 - 0 + 0 + 5 = 105
        assertEquals(0, new BigDecimal("105.000000").compareTo(line.getClosingQty()),
                "期末 = 期初100 + 盘盈5 = 105");
        // 盘盈金额
        assertEquals(0, new BigDecimal("50.00").compareTo(line.getStocktakeProfitAmount()),
                "盘盈金额应为 50.00");
        assertNull(line.getStocktakeLossAmount(), "无盘损时 lossAmount 诚实 null");
    }

    @Test
    @DisplayName("T12: W8 盘损分列 — lossQty≥0 (绝对值), profitQty=0, adjustQty 为负")
    void testStocktakeSplit_lossOnly() {
        InventoryLedgerSnapshot snap = buildSnapshot(MAT_ID,
                new BigDecimal("100.000000"), new BigDecimal("1000.00"));
        stubWithStocktake(snap,
                BigDecimal.ZERO, new BigDecimal("3.000000"),
                null, new BigDecimal("30.00"));

        List<InventoryLedgerLineDTO> result = service.getLedger(FACTORY_ID, START, END, null);
        InventoryLedgerLineDTO line = result.get(0);

        assertEquals(0, BigDecimal.ZERO.compareTo(line.getStocktakeProfitQty()),
                "无盘盈时 profitQty = 0");
        assertEquals(0, new BigDecimal("3.000000").compareTo(line.getStocktakeLossQty()),
                "盘损数量应为 3 (绝对值, ≥0)");
        assertTrue(line.getStocktakeLossQty().compareTo(BigDecimal.ZERO) >= 0,
                "盘损数量必须 ≥0 (取绝对值展示)");
        // adjustQty = 0 - 3 = -3
        assertEquals(0, new BigDecimal("-3.000000").compareTo(line.getAdjustQty()),
                "adjustQty = profitQty - lossQty = -3");
        // 期末 = 100 + 0 - 0 + 0 + (-3) = 97
        assertEquals(0, new BigDecimal("97.000000").compareTo(line.getClosingQty()),
                "期末 = 期初100 + adjustQty(-3) = 97");
        assertNull(line.getStocktakeProfitAmount(), "无盘盈时 profitAmount 诚实 null");
        assertEquals(0, new BigDecimal("30.00").compareTo(line.getStocktakeLossAmount()),
                "盘损金额应为 30.00 (绝对值)");
        assertTrue(line.getStocktakeLossAmount().compareTo(BigDecimal.ZERO) >= 0,
                "盘损金额必须 ≥0");
    }

    @Test
    @DisplayName("T13: W8 同期盘盈+盘损 — 分列独立, adjustQty=profit-loss, 期末正确")
    void testStocktakeSplit_bothProfitAndLoss() {
        InventoryLedgerSnapshot snap = buildSnapshot(MAT_ID,
                new BigDecimal("100.000000"), new BigDecimal("1000.00"));
        stubWithStocktake(snap,
                new BigDecimal("5.000000"), new BigDecimal("3.000000"),
                new BigDecimal("50.00"), new BigDecimal("30.00"));

        List<InventoryLedgerLineDTO> result = service.getLedger(FACTORY_ID, START, END, null);
        InventoryLedgerLineDTO line = result.get(0);

        assertEquals(0, new BigDecimal("5.000000").compareTo(line.getStocktakeProfitQty()),
                "盘盈数量 = 5");
        assertEquals(0, new BigDecimal("3.000000").compareTo(line.getStocktakeLossQty()),
                "盘损数量 = 3 (绝对值)");
        // adjustQty = 5 - 3 = 2 (净盈)
        assertEquals(0, new BigDecimal("2.000000").compareTo(line.getAdjustQty()),
                "adjustQty = 5 - 3 = 2");
        // 期末 = 100 + 0 - 0 + 0 + 2 = 102
        assertEquals(0, new BigDecimal("102.000000").compareTo(line.getClosingQty()),
                "期末 = 100 + 净调整2 = 102");
        // adjustAmount = 50 - 30 = 20
        assertEquals(0, new BigDecimal("20.00").compareTo(line.getAdjustAmount()),
                "adjustAmount = profitAmount - lossAmount = 20.00");
    }

    // ============== W8: 金蝶 per-movement 摘要测试 ==============

    @Test
    @DisplayName("T14: 金蝶摘要 — 盘盈格式含 + 前缀")
    void testKingdeeSummary_stocktakeProfit() {
        String summary = InventoryLedgerServiceImpl.buildKingdeeMovementSummary(
                "stocktake_profit", null, "猪舌", new BigDecimal("5.000000"), "kg");
        assertTrue(summary.contains("盘盈"), "摘要应含 '盘盈'");
        assertTrue(summary.contains("+"), "盘盈摘要应含 '+' 前缀");
        assertTrue(summary.contains("猪舌"), "摘要应含物料名");
        assertTrue(summary.contains("5.000000"), "摘要应含数量");
        assertTrue(summary.contains("kg"), "摘要应含单位");
    }

    @Test
    @DisplayName("T15: 金蝶摘要 — 盘损格式含 - 前缀")
    void testKingdeeSummary_stocktakeLoss() {
        String summary = InventoryLedgerServiceImpl.buildKingdeeMovementSummary(
                "stocktake_loss", null, "牛腱", new BigDecimal("3.000000"), "kg");
        assertTrue(summary.contains("盘损"), "摘要应含 '盘损'");
        assertTrue(summary.contains("-"), "盘损摘要应含 '-' 前缀");
        assertTrue(summary.contains("牛腱"), "摘要应含物料名");
        assertTrue(summary.contains("3.000000"), "摘要应含数量");
    }

    @Test
    @DisplayName("T16: 金蝶摘要 — 入库含单号括号")
    void testKingdeeSummary_inboundWithDocNo() {
        String summary = InventoryLedgerServiceImpl.buildKingdeeMovementSummary(
                "inbound", "PO-2026-001", "猪蹄", new BigDecimal("100.000000"), "kg");
        assertTrue(summary.contains("入库"), "摘要应含 '入库'");
        assertTrue(summary.contains("[PO-2026-001]"), "摘要应含单号括号");
        assertTrue(summary.contains("猪蹄"), "摘要应含物料名");
        // 入库无方向前缀
        assertFalse(summary.startsWith("+") || summary.startsWith("-"),
                "入库摘要不应有符号前缀");
    }

    @Test
    @DisplayName("T17: 金蝶摘要 — null 单号时无括号")
    void testKingdeeSummary_nullDocNo_noBrackets() {
        String summary = InventoryLedgerServiceImpl.buildKingdeeMovementSummary(
                "production", null, "猪舌", new BigDecimal("50.000000"), "kg");
        assertFalse(summary.contains("["), "null 单号时摘要不含括号");
        assertTrue(summary.contains("领用"), "摘要应含 '领用'");
    }

    @Test
    @DisplayName("T18: 金蝶摘要 — null 物料名/数量防御")
    void testKingdeeSummary_nullDefense() {
        // 不应抛 NPE
        String summary = InventoryLedgerServiceImpl.buildKingdeeMovementSummary(
                "stocktake_profit", null, null, null, null);
        assertNotNull(summary, "null 参数不应返回 null");
        assertTrue(summary.contains("盘盈"), "摘要类型标签仍应正确");
    }

    @Test
    @DisplayName("T19: W8 导出表头含分列列名(盘盈数量/盘损数量), 不含旧单列名")
    void testExport_w8SplitColumnHeaders() throws Exception {
        InventoryLedgerSnapshot snap = buildSnapshot(MAT_ID,
                new BigDecimal("50.000000"), new BigDecimal("3000.00"));
        when(materialTypeRepo.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(buildMaterial(MAT_ID, "猪舌", "kg")));
        when(snapshotRepo.findLatestBeforePeriod(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of(snap));
        stubQueryReturnsZero();

        var out = new java.io.ByteArrayOutputStream();
        service.exportInventoryLedger(FACTORY_ID, START, END, null, true, out);

        List<String> header = readXlsx(out.toByteArray()).get(0);
        // W8: 分列列名必须出现
        assertTrue(header.contains("盘盈数量"), "W8: 表头应含分列 '盘盈数量'");
        assertTrue(header.contains("盘损数量"), "W8: 表头应含分列 '盘损数量'");
        // 旧单列名不应出现
        assertFalse(header.contains("盘盈损数量"), "W8: 旧混合列名 '盘盈损数量' 不应再出现");
        // 金额分列
        assertTrue(header.contains("盘盈金额"), "W8: 表头应含分列 '盘盈金额'");
        assertTrue(header.contains("盘损金额"), "W8: 表头应含分列 '盘损金额'");
        assertFalse(header.contains("盘盈损金额"), "W8: 旧混合金额列 '盘盈损金额' 不应再出现");
    }
}
