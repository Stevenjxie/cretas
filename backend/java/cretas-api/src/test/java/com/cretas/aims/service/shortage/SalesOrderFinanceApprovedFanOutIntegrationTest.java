package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-01 incident — 销售订单财务审核通过事件 fan-out 丢写.
 *
 * <p><b>报告的症状</b>: prod 日志显示
 * <pre>
 *   [ShortageReport] analyze failed (orchestrator 仍正常工作): SO=...
 *   自动创建生产计划: PP=PP-AUTO-...
 * </pre>
 * 但 {@code sales_order_shortage_report} 建表以来 0 行 (连 FAILED 占位行都没有)，
 * {@code production_plans} 里对应 PP-AUTO 行也查不到。
 *
 * <p><b>实测根因 — 两个独立 bug, 别用一个解释两个现象</b>(非猜测, 从 prod
 * {@code /www/wwwroot/cretas/logs/cretas-backend.log} 2026-06~08 全量日志追出, 77 次
 * fan-out 调用 77 次全部失败):
 * <ol>
 *   <li><b>bug 1 (罕见, 实测 2/77 — 本 PR 已修)</b> — doomed-tx 传染: 产品无已激活 BOM 时
 *       {@code BomExpansionService.expandBOM} 抛 {@code BusinessException}, 该方法自身
 *       {@code @Transactional}(默认 REQUIRED) JOIN 调用方(orchestrator / shortage listener)
 *       的 {@code @Transactional(REQUIRES_NEW)} 联动事务, Spring tx 拦截器在 expandBOM 自己的
 *       AOP 边界上就把"共享事务"标记 rollback-only —— 即使外层 try/catch 吞掉异常、继续写
 *       PP / FAILED 占位行, commit 时仍静默回滚整个联动事务。与
 *       {@code onMaterialReceived}/{@code onBatchCompleted} 2026-06-12 "doomed-tx 第4次
 *       复发"同一套机制。修法: {@code ShortageAnalysisServiceImpl.analyzeForSalesOrder} 改
 *       {@code REQUIRES_NEW}; {@code SupplyChainOrchestrator} 新增
 *       {@code expandBomAndCheckMaterialIsolated} 用 {@code TransactionTemplate} 把
 *       expandBOM/checkMaterialAvailability 包进独立子事务。</li>
 *   <li><b>bug 2 (主因, 实测 76/77 — 本 PR <u>未修</u>, 见下方 "已知限制")</b> —
 *       {@code com.cretas.aims.dto.orchestration.LineItemMatch.isFullySatisfied()} 是纯计算
 *       getter(由 {@code shortfallQuantity} 现算, 没有对应字段/setter)。Jackson 序列化时会
 *       按 JavaBean 约定把它当成一个属性写进 JSON({@code "fullySatisfied":true/false}), 但
 *       hypersistence-utils 的 {@code ObjectMapperWrapper.clone()}(Hibernate 每次
 *       {@code save()} 前 dirty-check 用的 deepCopy)要把这段 JSON 反序列化回
 *       {@code LineItemMatch} 时, Jackson 找不到 {@code setFullySatisfied(...)} 抛
 *       {@code UnrecognizedPropertyException}, 在 SQL 发出<b>之前</b>就失败 —— prod 日志
 *       {@code Caused by: com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException:
 *       Unrecognized field "fullySatisfied" (class com.cretas.aims.dto.orchestration
 *       .LineItemMatch)...} 逐字匹配。触发条件 = {@code SalesOrderShortageReport.available}
 *       (JSONB 列)非空即触发, 即任何正常走到 {@code persistReport}(COMPLETED 路径)且订单至少
 *       一个行项目的报告 —— 覆盖绝大多数订单, 这才是"0 行"真正的主因。</li>
 * </ol>
 *
 * <p><b>已知限制(未修, 需要 follow-up PR)</b>: bug 2 的修复点在
 * {@code com.cretas.aims.dto.orchestration.LineItemMatch}(需要在 {@code isFullySatisfied()}
 * 加 {@code @JsonIgnore}, 或改造成有 backing field 的普通属性)—— 该文件不在本卡允许改动范围
 * ({@code SupplyChainOrchestrator.java} + {@code service/shortage/**}), 且它是被多处代码共用
 * 的 orchestration 层 DTO, 改动前应评估更大范围的影响面。<b>不修复 bug 2, 本 PR 单独合并后
 * {@code sales_order_shortage_report} 的整体 0 行症状不会消失</b> —— 只有本来就会撞见 bug 1
 * 的那一小撮订单(产品无 BOM)才会看到报告行开始落库。详见 PR 描述 "根因结论" 部分。
 *
 * <p>本测试直接发布真实 {@link SalesOrderFinanceApprovedEvent}（不 mock 任何协作者），
 * 让 {@code SupplyChainOrchestrator.onSalesOrderFinanceApproved} 与
 * {@code SalesOrderShortageReportListener.onSalesOrderFinanceApproved} 两个真实
 * {@code @Async} 监听器跑起来，复现 bug 1(库存不足 + 产品无 BOM)并断言两条链路的持久化结果
 * 都能在 BOM 展开失败后存活。bug 2 无法在本测试类里做成红→绿(修复点不在允许改动范围内)，
 * 因此没有对应测试方法 —— 已在上面的 Javadoc 里用 prod 实测堆栈钉死结论, 留给 follow-up PR
 * 配自己的红测。
 *
 * <p>修复前, 本测试会失败, 与 prod bug 1 症状完全一致。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SalesOrderFinanceApprovedFanOutIntegrationTest {

    // 实例字段(非 static) —— JUnit5 默认每个 @Test 方法用新实例(PER_METHOD 生命周期),
    // 保证每个测试方法各自拿到独立的 FACTORY_ID。
    private final String FACTORY_ID = "FANOUT-IT-" + UUID.randomUUID().toString().substring(0, 8);
    private static final long POLL_TIMEOUT_MS = 10_000L;
    private static final long POLL_INTERVAL_MS = 100L;

    @Autowired private FactoryRepository factoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private ProductTypeRepository productTypeRepository;
    @Autowired private FactoryWarehouseRepository factoryWarehouseRepository;
    @Autowired private SalesOrderRepository salesOrderRepository;
    @Autowired private SalesOrderItemRepository salesOrderItemRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String customerId;
    private String productTypeId;
    private String salesOrderId;

    @BeforeEach
    void seedOrderWithNoActiveBomAndNoStock() {
        Factory factory = new Factory();
        factory.setId(FACTORY_ID);
        factory.setName("Fan-out IT Factory " + FACTORY_ID);
        factoryRepository.saveAndFlush(factory);

        User operator = new User();
        operator.setFactoryId(FACTORY_ID);
        operator.setUsername("fanout-it-" + FACTORY_ID);
        operator.setPasswordHash("$2a$10$dummyHashForTesting");
        operator.setFullName("Fan-out IT Operator");
        operator.setIsActive(true);
        Long createdBy = userRepository.saveAndFlush(operator).getId();

        Customer customer = new Customer();
        customer.setFactoryId(FACTORY_ID);
        customer.setCode("CUST-" + FACTORY_ID);
        customer.setCustomerCode("CUST-" + FACTORY_ID);
        customer.setName("Fan-out IT Customer");
        customer.setIsActive(true);
        customer.setCreatedBy(createdBy);
        customerId = customerRepository.saveAndFlush(customer).getId();

        // 故意不建任何 BomRecipe/BomRecipeItem — 复现 "产品尚无已激活的新版 BOM 配方".
        ProductType productType = new ProductType();
        productType.setFactoryId(FACTORY_ID);
        productType.setCode("PT-" + FACTORY_ID);
        productType.setName("无BOM测试品");
        productType.setUnit("kg");
        productType.setIsActive(true);
        productType.setCreatedBy(createdBy);
        productTypeId = productTypeRepository.saveAndFlush(productType).getId();

        // InventoryMatchingService.checkAvailability 需要 WH-LOG seed, 否则在到达
        // 缺料/BOM 逻辑之前就先抛 BusinessException(500) — 与本次要复现的场景无关.
        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setFactoryId(FACTORY_ID);
        warehouse.setCode("WH-LOG");
        warehouse.setName("Fan-out IT 物流仓");
        warehouse.setType(FactoryWarehouse.WarehouseType.LOGISTICS);
        warehouse.setIsActive(true);
        factoryWarehouseRepository.saveAndFlush(warehouse);

        SalesOrder order = new SalesOrder();
        order.setFactoryId(FACTORY_ID);
        order.setOrderNumber("SO-FANOUT-IT-" + UUID.randomUUID());
        order.setCustomerId(customerId);
        order.setOrderDate(LocalDate.now());
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        order.setCreatedBy(createdBy);
        salesOrderId = salesOrderRepository.saveAndFlush(order).getId();

        // 故意不建任何 FinishedGoodsBatch — 可用库存=0, shortfall=130, 触发 BOM 展开路径 (bug 1).
        SalesOrderItem item = new SalesOrderItem();
        item.setSalesOrderId(salesOrderId);
        item.setProductTypeId(productTypeId);
        item.setProductName("无BOM测试品");
        item.setUnit("kg");
        item.setQuantity(new BigDecimal("130.0000"));
        salesOrderItemRepository.saveAndFlush(item);
    }

    @Test
    void bomExpansionFailureDoesNotSilentlyDiscardShortageReportOrProductionPlan() throws InterruptedException {
        eventPublisher.publishEvent(
                new SalesOrderFinanceApprovedEvent(this, FACTORY_ID, salesOrderId, 1L));

        // 两个监听器都是 @Async(默认 SimpleAsyncTaskExecutor) — 轮询等落库, 不用固定 sleep.
        // 断言走原生 JDBC 而非 JPA repository: sales_order_shortage_report/production_plans
        // 都带 JSONB 列, Hibernate 在"全新 session 里重新读取"这类列时会踩 H2 JSONB 兼容性坑
        // (同一坑见 ProcessSheetServiceConcurrencyTest 类注释) —— 与本测试要验证的联动事务
        // 隔离逻辑无关, 原生 JDBC 读一个标量列就能绕开。
        Optional<String> analysisStatus = pollUntilPresent(() -> jdbcTemplate.query(
                "SELECT analysis_status FROM sales_order_shortage_report "
                        + "WHERE factory_id = ? AND sales_order_id = ?",
                (rs, rowNum) -> rs.getString("analysis_status"),
                FACTORY_ID, salesOrderId).stream().findFirst());
        assertThat(analysisStatus)
                .as("sales_order_shortage_report 行必须落库(即使 BOM 展开抛异常) —— "
                        + "回归自 2026-08-01 doomed-tx incident: expandBOM 的嵌套 @Transactional "
                        + "异常曾把整条 REQUIRES_NEW 联动事务标记 rollback-only, 连 catch 块里的 "
                        + "FAILED 占位行都被牵连回滚")
                .isPresent();
        assertThat(analysisStatus.get()).isEqualTo("FAILED");

        boolean productionPlanPersisted = pollUntilTrue(() -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM production_plans WHERE factory_id = ? AND source_order_id = ?",
                    Integer.class, FACTORY_ID, salesOrderId);
            return count != null && count > 0;
        });
        assertThat(productionPlanPersisted)
                .as("createProductionPlanFromSO 里已 save 的 ProductionPlan 必须存活 —— "
                        + "之前 BOM 展开失败会把这次联动事务整体回滚, prod 日志打印 "
                        + "\"自动创建生产计划: PP=...\" 但库里查无此行, 正是这个症状")
                .isTrue();
    }

    private <T> Optional<T> pollUntilPresent(Supplier<Optional<T>> supplier) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        Optional<T> result = Optional.empty();
        while (System.currentTimeMillis() < deadline) {
            result = supplier.get();
            if (result.isPresent()) {
                return result;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return result;
    }

    private boolean pollUntilTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return false;
    }
}
