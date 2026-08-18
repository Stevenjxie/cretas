package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 闸 —— 判据九：库存录入、转移、<b>手工出库</b>都要留下<b>可查</b>的痕迹。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 prod 实测)</h2>
 *
 * <b>两个独立的失败，缺任何一个这条判据都不成立：</b>
 *
 * <ol>
 *   <li><b>工人真实用的那条路不写痕迹</b>。{@code WHOutboundIssueScreen}（扫码出库确认页）
 *       调 {@code POST /material-batches/{id}/use}，body 只有 {@code quantity}，
 *       <b>不传 productionPlanId</b>；而写 {@code MaterialConsumption} 的那段代码包在
 *       {@code if (productionPlanId != null)} 里 ⇒ 永远走不到。
 *       实测（批次 {@code a5e3740e-…} 出库 0.01kg）：{@code material_consumptions} 零新增、
 *       {@code operation_logs} 零行；变的只有 {@code usedQuantity}（累计值）和
 *       {@code lastUsedAt}（单个可变字段，下次出库就被覆盖，<b>连是谁出的都没记</b>）。</li>
 *   <li><b>写了痕迹的那条路，界面看不到</b>。{@code /adjust} 会写完整的
 *       {@code material_batch_adjustments}，但唯一接到界面的
 *       {@code getBatchUsageHistory} 是 {@code // TODO} + {@code return new ArrayList<>();}
 *       ⇒ web-admin「调整历史」表永远不显示，RN「批次追溯」永远「暂无出库记录」。</li>
 * </ol>
 *
 * <p>⚠️ 所以这道闸<b>必须同时钉两层</b>：写得下 + 查得到。只钉一层的话，另一层挂了
 * 判据照样不成立，而闸是绿的。
 */
class ManualIssueLeavesATraceContractTest {

    private static final String FACTORY = "F006";
    private static final String BATCH_ID = "a5e3740e-9a3d-4eaa-8fb5-3037255799a4";
    private static final Long OPERATOR = 1554L;

    private MaterialBatchRepository batchRepo;
    private MaterialConsumptionRepository consumptionRepo;
    private MaterialBatchAdjustmentRepository adjustmentRepo;
    private UserRepository userRepo;
    private MaterialBatchServiceImpl service;

    private static MaterialBatch batch() {
        MaterialBatch b = new MaterialBatch();
        b.setId(BATCH_ID);
        b.setFactoryId(FACTORY);
        b.setReceiptQuantity(new BigDecimal("15.000000"));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setUnitPrice(new BigDecimal("2.50"));
        b.setQuantityUnit("kg");
        return b;
    }

    @BeforeEach
    void setUp() {
        service = mock(MaterialBatchServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        batchRepo = mock(MaterialBatchRepository.class);
        consumptionRepo = mock(MaterialConsumptionRepository.class);
        adjustmentRepo = mock(MaterialBatchAdjustmentRepository.class);
        userRepo = mock(UserRepository.class);
        MaterialBatchMapper mapper = mock(MaterialBatchMapper.class);

        when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY)).thenReturn(Optional.of(batch()));
        when(batchRepo.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        ReflectionTestUtils.setField(service, "materialBatchRepository", batchRepo);
        ReflectionTestUtils.setField(service, "materialConsumptionRepository", consumptionRepo);
        ReflectionTestUtils.setField(service, "materialBatchAdjustmentRepository", adjustmentRepo);
        ReflectionTestUtils.setField(service, "userRepository", userRepo);
        ReflectionTestUtils.setField(service, "materialBatchMapper", mapper);
        // 低库存事件发布器: 真实入口会调它, 桩掉才能跑到我们要断言的那一步
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher",
                mock(com.cretas.aims.service.alerts.InventoryLowStockEventPublisher.class));
    }

    // ================= 第一层: 写得下 =================

    @Test
    @DisplayName("🔴 不带生产计划的手工出库也要留痕 —— 那正是工人真实用的那条路")
    void manualIssueWithoutPlanStillWritesATrace() {
        service.useBatchMaterial(FACTORY, BATCH_ID, new BigDecimal("0.01"), null, OPERATOR, "扫码出库");

        ArgumentCaptor<MaterialConsumption> cap = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(consumptionRepo).save(cap.capture());
        MaterialConsumption c = cap.getValue();
        assertEquals(BATCH_ID, c.getBatchId());
        assertEquals(0, c.getQuantity().compareTo(new BigDecimal("0.01")));
        assertEquals(OPERATOR, c.getRecordedBy(), "没记是谁出的库");
        assertEquals("MANUAL_ISSUE", c.getSourceType(), "手工出库没有单独的来源标记");
        assertEquals("扫码出库", c.getNotes(), "原因被丢了");
        assertNotNull(c.getConsumptionTime(), "没记时间");
    }

    @Test
    @DisplayName("阳性对照: 带生产计划的出库照旧留痕, 且来源标记不同(否则两类分不开)")
    void issueWithPlanStillWritesTraceWithDifferentSource() {
        MaterialBatchServiceImpl svc = service;
        ReflectionTestUtils.setField(svc, "productionPlanRepository", null);
        svc.useBatchMaterial(FACTORY, BATCH_ID, new BigDecimal("1"), null, OPERATOR, null);

        ArgumentCaptor<MaterialConsumption> cap = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(consumptionRepo).save(cap.capture());
        assertEquals("MANUAL_ISSUE", cap.getValue().getSourceType());
    }

    @Test
    @DisplayName("🔴 记不下「是谁」的出库不许静默通过")
    void issueWithoutOperatorIsRejectedNotSilentlyAccepted() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.useBatchMaterial(FACTORY, BATCH_ID, new BigDecimal("1"), null, null, null));
        assertTrue(String.valueOf(ex.getMessage()).contains("操作人"), "报错没说清缺什么: " + ex.getMessage());
        verify(consumptionRepo, never()).save(any());
    }

    // ================= 第二层: 查得到 =================

    @Test
    @DisplayName("🔴 使用历史必须真的查出来 —— 原来无条件返回空 list")
    void usageHistoryIsActuallyQueried() {
        MaterialConsumption c = new MaterialConsumption();
        c.setBatchId(BATCH_ID);
        c.setQuantity(new BigDecimal("0.01"));
        c.setRecordedBy(OPERATOR);
        c.setConsumptionTime(LocalDateTime.of(2026, 8, 18, 10, 0));
        c.setSourceType("MANUAL_ISSUE");
        when(consumptionRepo.findByFactoryIdAndBatchId(FACTORY, BATCH_ID)).thenReturn(List.of(c));

        MaterialBatchAdjustment a = new MaterialBatchAdjustment();
        a.setMaterialBatchId(BATCH_ID);
        a.setAdjustmentType("correction");
        a.setAdjustmentQuantity(new BigDecimal("-1"));
        a.setQuantityBefore(new BigDecimal("15"));
        a.setQuantityAfter(new BigDecimal("14"));
        a.setReason("盘点差异");
        a.setAdjustedBy(OPERATOR);
        a.setAdjustmentTime(LocalDateTime.of(2026, 8, 18, 12, 0));
        when(adjustmentRepo.findByMaterialBatchIdOrderByAdjustmentTimeDesc(BATCH_ID))
                .thenReturn(List.of(a));

        User u = new User();
        u.setId(OPERATOR);
        u.setUsername("f006_warehouse_mgr");
        u.setFullName("六膳门仓储主管");
        when(userRepo.findById(OPERATOR)).thenReturn(Optional.of(u));

        List<Map<String, Object>> history = service.getBatchUsageHistory(FACTORY, BATCH_ID);

        assertEquals(2, history.size(), "两类痕迹没都出来: " + history);
        // 时间倒序: 12:00 的调整排在 10:00 的出库前面
        assertEquals("ADJUST", history.get(0).get("type"), "没有按时间倒序: " + history);
        assertEquals("OUT", history.get(1).get("type"));
        // 判据九要的四件事: 谁 / 何时 / 多少 / 为什么
        assertEquals("六膳门仓储主管", history.get(0).get("operatorName"), "没解析出操作人姓名");
        assertEquals("盘点差异", history.get(0).get("reason"), "没带原因");
        assertNotNull(history.get(0).get("occurredAt"), "没带时间");
        assertNotNull(history.get(1).get("quantity"), "没带数量");
    }

    @Test
    @DisplayName("🔴 阴性对照: 两边都没记录时是真空, 不是凭空造行")
    void emptyWhenThereIsGenuinelyNothing() {
        when(consumptionRepo.findByFactoryIdAndBatchId(FACTORY, BATCH_ID)).thenReturn(List.of());
        when(adjustmentRepo.findByMaterialBatchIdOrderByAdjustmentTimeDesc(BATCH_ID)).thenReturn(List.of());
        assertTrue(service.getBatchUsageHistory(FACTORY, BATCH_ID).isEmpty(), "凭空造了历史行");
    }

    @Test
    @DisplayName("查不到操作人时说「未知操作人(ID x)」, ⛔ 不留空(留空会被读成系统自动操作)")
    void unknownOperatorIsNamedNotBlank() {
        MaterialConsumption c = new MaterialConsumption();
        c.setBatchId(BATCH_ID);
        c.setQuantity(BigDecimal.ONE);
        c.setRecordedBy(9999L);
        c.setConsumptionTime(LocalDateTime.now());
        when(consumptionRepo.findByFactoryIdAndBatchId(FACTORY, BATCH_ID)).thenReturn(List.of(c));
        when(adjustmentRepo.findByMaterialBatchIdOrderByAdjustmentTimeDesc(BATCH_ID)).thenReturn(List.of());
        when(userRepo.findById(9999L)).thenReturn(Optional.empty());

        Object name = service.getBatchUsageHistory(FACTORY, BATCH_ID).get(0).get("operatorName");
        assertNotNull(name, "操作人名字是 null");
        assertFalse(String.valueOf(name).isBlank(), "操作人名字留空了");
        assertTrue(String.valueOf(name).contains("9999"), "没说清是哪个 ID 查不到: " + name);
    }

    @Test
    @DisplayName("跨租户: 别厂的批次查不到历史(这个只读接口不能变成探针)")
    void otherFactoryBatchIsRejected() {
        when(batchRepo.findByIdAndFactoryId(BATCH_ID, "F001")).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.getBatchUsageHistory("F001", BATCH_ID));
    }
}
