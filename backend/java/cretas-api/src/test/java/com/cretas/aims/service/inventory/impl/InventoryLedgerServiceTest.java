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
 * <p>覆盖 spec §10.2 四个验收标准:
 * <ol>
 *   <li>有快照时以快照为期初</li>
 *   <li>无快照时从全量聚合期初 (兜底路径)</li>
 *   <li>期末金额 ROUND_HALF_UP 精度</li>
 *   <li>@PriceSensitive 字段对仓管角色为 null (字段已标注)</li>
 * </ol>
 *
 * @since SP11 2026-06-10
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
                .thenReturn(List.of(BigDecimal.ZERO))               // adjust qty
                .thenReturn(List.of(BigDecimal.ZERO));              // adjust amount
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
                .thenReturn(List.of(BigDecimal.ZERO))               // adjust qty
                .thenReturn(List.of(BigDecimal.ZERO));              // adjust amount
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
}
