package com.cretas.aims.service.production;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B2-fix (2026-07-06) — 生产进度数字打屏看板 (B8) "已完成/进行中/未开始" 桶必须以
 * {@code production_plans.status} (真实计划生命周期) 为权威来源, 不能靠
 * {@code production_reports} 数量比例反推 (该表当天常常 0 行, 之前所有计划恒
 * 误判"未开始 0%")。
 *
 * <p>复现真实 F006 场景: 11 个 IN_PROGRESS + 1 个 COMPLETED, 当天无 production_reports
 * 记录 (与 2026-07-06 psql 核实的 prod 数据形状一致)。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("ProductionProgressDashboardService — B2 真实计划状态驱动看板计数")
class ProductionProgressDashboardServiceTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ProductionPlanRepository planRepository;

    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FactoryRepository factoryRepository;

    private ProductionProgressDashboardService service;

    private static final String FID = "F-B2TEST";
    private Long UID;

    @BeforeEach
    void setUp() {
        service = new ProductionProgressDashboardService();
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        Factory factory = new Factory();
        factory.setId(FID);
        factory.setName("B2 测试工厂");
        factoryRepository.saveAndFlush(factory);

        User user = new User();
        user.setFactoryId(FID);
        user.setUsername("b2-test-user");
        user.setPasswordHash("test-hash");
        user = userRepository.saveAndFlush(user);
        UID = user.getId();

        ProductType pt = new ProductType();
        pt.setId("PT-B2");
        pt.setFactoryId(FID);
        pt.setCode("PT-B2");
        pt.setName("测试产品");
        pt.setUnit("kg");
        pt.setCreatedBy(UID);
        productTypeRepository.saveAndFlush(pt);
    }

    private void plan(String id, BigDecimal plannedQty, ProductionPlanStatus status, LocalDate batchDate) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(FID);
        p.setPlanNumber("PLAN-B2-" + id);
        p.setProductTypeId("PT-B2");
        p.setPlannedQuantity(plannedQty);
        p.setStatus(status);
        p.setBatchDate(batchDate);
        p.setCreatedBy(UID);
        planRepository.saveAndFlush(p);
    }

    @Test
    @DisplayName("11 IN_PROGRESS + 1 COMPLETED, 无 production_reports → 计数必须反映真实状态, 不是 0/0/12")
    void countsReflectRealPlanStatus_notZeroFromEmptyReports() {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 11; i++) {
            plan("PP-IP-" + i, new BigDecimal("10.00"), ProductionPlanStatus.IN_PROGRESS, today);
        }
        plan("PP-DONE-1", new BigDecimal("0.00"), ProductionPlanStatus.COMPLETED, today);

        Map<String, Object> data = service.getDashboard(FID, today);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");

        assertEquals(12, summary.get("totalPlans"));
        assertEquals(1, summary.get("completedWorkOrders"), "COMPLETED 计划必须计入已完成, 不是靠 production_reports 推导");
        assertEquals(11, summary.get("inProgressWorkOrders"), "IN_PROGRESS 计划必须计入进行中, 不是恒'未开始'");
        assertEquals(0, summary.get("pendingWorkOrders"));
        // overallProgressPct 按已完成工单数/总工单数 = 1/12 ≈ 8%, 不再因产量数据缺失恒 0%
        assertEquals(8, summary.get("overallProgressPct"));
    }

    @Test
    @DisplayName("PENDING/PLANNED 未开始桶 + CANCELLED 被排除")
    void pendingAndPlannedBucketed_cancelledExcluded() {
        LocalDate today = LocalDate.now();
        plan("PP-PEND", new BigDecimal("5.00"), ProductionPlanStatus.PENDING, today);
        plan("PP-PLANNED", new BigDecimal("5.00"), ProductionPlanStatus.PLANNED, today);
        plan("PP-CANCELLED", new BigDecimal("5.00"), ProductionPlanStatus.CANCELLED, today);

        Map<String, Object> data = service.getDashboard(FID, today);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");

        assertEquals(2, summary.get("totalPlans"), "CANCELLED 计划不应出现在当天打屏看板");
        assertEquals(0, summary.get("completedWorkOrders"));
        assertEquals(0, summary.get("inProgressWorkOrders"));
        assertEquals(2, summary.get("pendingWorkOrders"));
    }

    @Test
    @DisplayName("单个 COMPLETED 计划: 进度条强制 100%, 状态徽标为 DONE")
    void completedPlan_forcedTo100Pct() {
        LocalDate today = LocalDate.now();
        plan("PP-DONE-SOLO", new BigDecimal("50.00"), ProductionPlanStatus.COMPLETED, today);

        Map<String, Object> data = service.getDashboard(FID, today);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> plans = (java.util.List<Map<String, Object>>) data.get("plans");

        assertEquals(1, plans.size());
        Map<String, Object> plan = plans.get(0);
        assertEquals("DONE", plan.get("status"));
        assertEquals(100, plan.get("progressPct"), "COMPLETED 计划即使 actual_quantity 缺失也应显示 100%, 不是 0%");
    }
}
