package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.mockito.InOrder;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「未开工的计划跟上新配方」的开工判据 —— Steve 2026-08-09 拍板:
 * 已生产的坚决不影响; 未开工但已建计划的必须能用上新配方。
 *
 * <h2>🔴 判据不能看计划状态, 也不能看有没有批次</h2>
 *
 * <p>真机样本 PLAN-1786184738975: 状态 {@code IN_PROGRESS}、已生成 1 个生产批次,
 * 却<b>一克料都没扣</b>(报工提交 409 失败, 只留下草稿行)。按状态判或按有没有批次判,
 * 都会把它误判成「已生产」而拒绝更新 —— 而它恰恰是这个功能最典型的服务对象。
 *
 * <p>正确判据是报工行的<b>三个信号全干净</b>: rowStatus / submissionStatus / interimSettledAt。
 * 任一命中即视为动过真库存, 一律不动。
 */
@DisplayName("repinPlanToCurrentAuthority — 开工判据(三信号)")
class ProductionPlanRepinAuthorityTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN-REPIN-1";

    private ProductionPlanServiceImpl newService(
            ProductionPlanRepository planRepo, ProcessSheetRowRepository rowRepo) throws Exception {
        Constructor<?> ctor = ProductionPlanServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = types[i] == ProductionPlanRepository.class ? planRepo : null;
        }
        ProductionPlanServiceImpl service = (ProductionPlanServiceImpl) ctor.newInstance(args);
        // processSheetRowRepository 是 @Autowired 字段(非构造参数), 反射注入。
        Field field = ProductionPlanServiceImpl.class.getDeclaredField("processSheetRowRepository");
        field.setAccessible(true);
        field.set(service, rowRepo);
        return service;
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = ProductionPlanServiceImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private ProductionPlan plan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);   // ⚠️ 故意用 IN_PROGRESS: 状态不是判据
        plan.setProductTypeId("PT-1");
        return plan;
    }

    private ProcessSheetRow row(String rowStatus, String submissionStatus, LocalDateTime settledAt) {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(FACTORY);
        row.setPlanId(PLAN_ID);
        row.setRowStatus(rowStatus);
        row.setSubmissionStatus(submissionStatus);
        row.setInterimSettledAt(settledAt);
        return row;
    }

    private Throwable repinWith(List<ProcessSheetRow> rows) throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProcessSheetRowRepository rowRepo = mock(ProcessSheetRowRepository.class);
        when(planRepo.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan()));
        when(rowRepo.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(rows);
        return catchThrowable(() ->
                newService(planRepo, rowRepo).repinPlanToCurrentAuthority(FACTORY, PLAN_ID, 1L));
    }

    private void assertRejectedAsStarted(Throwable thrown, String why) {
        assertThat(thrown).as(why).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode())
                .as(why).isEqualTo("PRODUCTION_PLAN_ALREADY_STARTED");
    }

    @Test
    @DisplayName("已物化成批次(rowStatus=SUBMITTED) → 拒绝")
    void rejectsWhenAnyRowMaterialized() throws Exception {
        assertRejectedAsStarted(
                repinWith(List.of(row("SUBMITTED", ProcessSheetRow.SUBMISSION_DRAFT, null))),
                "rowStatus=SUBMITTED 表示已物化成生产批次");
    }

    @Test
    @DisplayName("已正式提交(submissionStatus=SUBMITTED) → 拒绝")
    void rejectsWhenAnyRowSubmitted() throws Exception {
        assertRejectedAsStarted(
                repinWith(List.of(row("SAVED", ProcessSheetRow.SUBMISSION_SUBMITTED, null))),
                "submissionStatus=SUBMITTED 表示已正式报工");
    }

    @Test
    @DisplayName("已被生产小结扣过料(interimSettledAt != null) → 拒绝")
    void rejectsWhenAnyRowSettled() throws Exception {
        assertRejectedAsStarted(
                repinWith(List.of(row("SAVED", ProcessSheetRow.SUBMISSION_DRAFT, LocalDateTime.now()))),
                "interimSettledAt 非空表示真扣过料 —— 这条最要命, 前两个都干净也可能已经扣过");
    }

    /**
     * 🔴 这条是本功能的服务对象本身: 状态 IN_PROGRESS + 只有草稿行 = 一克料没动。
     *
     * <p>不能因为「状态是进行中」就拒绝 —— 那样这个功能对真机上最常见的情形直接失效。
     * 这里断言它<b>没有被开工判据拦下</b>(后续因为依赖为 null 而失败属于测试替身的限制,
     * 不是判据行为)。
     */
    @Test
    @DisplayName("🔴 只有草稿行 → 不得被开工判据拦下(哪怕计划状态是 IN_PROGRESS、已有批次)")
    void draftOnlyPlanIsNotTreatedAsStarted() throws Exception {
        Throwable thrown = repinWith(List.of(
                row("SAVED", ProcessSheetRow.SUBMISSION_DRAFT, null),
                row("SAVED_SFI", ProcessSheetRow.SUBMISSION_DRAFT, null)));

        if (thrown instanceof BusinessException business) {
            assertThat(business.getErrorCode())
                    .as("草稿行不算开工, 不该报 ALREADY_STARTED")
                    .isNotEqualTo("PRODUCTION_PLAN_ALREADY_STARTED");
        }
    }

    /**
     * 🔴 菜单灰显与端点拒绝必须是**同一个**判据。
     *
     * <p>第一版就栽在这: 菜单用 {@code hasRealProductionActivity}(它<b>不查</b> rowStatus),
     * 端点用三信号 —— 于是「只有 rowStatus=SUBMITTED」的计划菜单显示可点、点下去 409。
     * 这里直接打菜单用的那个方法, 断言它对同一行也判「已开工」。
     */
    @Test
    @DisplayName("🔴 菜单用的判据对 rowStatus=SUBMITTED 也必须拦(否则菜单说可点、后端 409)")
    void menuPredicateSeesRowStatusToo() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProcessSheetRowRepository rowRepo = mock(ProcessSheetRowRepository.class);
        when(rowRepo.findByFactoryIdAndPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(row("SUBMITTED", ProcessSheetRow.SUBMISSION_DRAFT, null)));

        // hasActivity 恒 false = 模拟 hasRealProductionActivity 看不见这一行的情形
        String reason = newService(planRepo, rowRepo)
                .repinBlockedReason(FACTORY, PLAN_ID, false, () -> false);

        assertThat(reason)
                .as("菜单判据必须自己也查 rowStatus, 不能只依赖 hasRealProductionActivity")
                .isEqualTo(ProductionPlanServiceImpl.REPIN_BLOCKED_STARTED);
    }

    /**
     * 🔴 真机抓到的半成品 —— 现在由 repin 自己搬平, 所以这里断言它<b>不再</b>拦。
     *
     * <p>2026-08-09 实测: 计划从 154/v2/rev264 重钉到 158/v4/rev272 后, 批次 10721 的实例
     * (id=71)仍是 154/v2、nodes_json 冻结着旧图。曾经的做法是「见实例就不给入口」;
     * 现在改成重钉时连批次权威一起搬 + 丢掉陈旧运行时, 于是入口必须重新给出来。
     */
    @Test
    @DisplayName("已物化出运行时实例 → 不再拦(改由重钉自己搬批次权威 + 丢陈旧运行时)")
    void materializedInstanceNoLongerBlocksRepin() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProcessSheetRowRepository rowRepo = mock(ProcessSheetRowRepository.class);
        when(rowRepo.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of());
        ProductionPlanServiceImpl service = newService(planRepo, rowRepo);

        ProductionBatch batch = new ProductionBatch();
        batch.setId(10721L);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(batch));
        ProductionWorkflowInstanceRepository instanceRepo =
                mock(ProductionWorkflowInstanceRepository.class);
        when(instanceRepo.findByFactoryIdAndProductionBatchId(FACTORY, 10721L))
                .thenReturn(Optional.of(mock(ProductionWorkflowInstance.class)));
        inject(service, "productionBatchRepository", batchRepo);
        inject(service, "productionWorkflowInstanceRepository", instanceRepo);

        assertThat(service.repinBlockedReason(FACTORY, PLAN_ID, false, () -> false)).isNull();
    }

    /**
     * 丢弃顺序必须是 端口 → 任务 → 实例。反过来会撞 fk_wtp_task_owner / fk_wpt_workflow_instance_owner
     * (三条外键全是 NO ACTION, 不级联)。
     */
    @Test
    @DisplayName("丢弃陈旧运行时: 端口 → 任务 → 实例, 一个都不能漏")
    void dropsPortsThenTasksThenInstance() throws Exception {
        ProductionPlanServiceImpl service = newService(
                mock(ProductionPlanRepository.class), mock(ProcessSheetRowRepository.class));
        ProductionWorkflowInstance instance = mock(ProductionWorkflowInstance.class);
        when(instance.getId()).thenReturn(71L);
        ProductionWorkflowInstanceRepository instanceRepo =
                mock(ProductionWorkflowInstanceRepository.class);
        when(instanceRepo.findByFactoryIdAndProductionBatchId(FACTORY, 10721L))
                .thenReturn(Optional.of(instance));
        WorkProcessTaskRepository taskRepo = mock(WorkProcessTaskRepository.class);
        WorkflowTaskPortRepository portRepo = mock(WorkflowTaskPortRepository.class);
        inject(service, "productionWorkflowInstanceRepository", instanceRepo);
        inject(service, "repinWorkProcessTaskRepository", taskRepo);
        inject(service, "repinWorkflowTaskPortRepository", portRepo);
        inject(service, "entityManager", entityManagerReturning(0L));

        service.dropCompiledRuntime(FACTORY, 10721L);

        InOrder order = inOrder(portRepo, taskRepo, instanceRepo);
        order.verify(portRepo).deleteAll(any());
        order.verify(taskRepo).deleteAll(any());
        order.verify(instanceRepo).delete(instance);
    }

    /**
     * 🔴 无外键的引用删不着也拦不住 —— 有任何一条就 fail closed。
     *
     * <p>production_reports / semi_finished_inventory / process_checkin_records 用普通列指向任务,
     * 数据库不会替我们挡; 真删下去就是一堆指向不存在任务的孤儿行。
     */
    @Test
    @DisplayName("🔴 任务已被无外键的列引用 → 拒绝丢弃(不留孤儿)")
    void refusesToDropWhenTasksAreSoftReferenced() throws Exception {
        ProductionPlanServiceImpl service = newService(
                mock(ProductionPlanRepository.class), mock(ProcessSheetRowRepository.class));
        ProductionWorkflowInstance instance = mock(ProductionWorkflowInstance.class);
        when(instance.getId()).thenReturn(71L);
        ProductionWorkflowInstanceRepository instanceRepo =
                mock(ProductionWorkflowInstanceRepository.class);
        when(instanceRepo.findByFactoryIdAndProductionBatchId(FACTORY, 10721L))
                .thenReturn(Optional.of(instance));
        WorkProcessTaskRepository taskRepo = mock(WorkProcessTaskRepository.class);
        inject(service, "productionWorkflowInstanceRepository", instanceRepo);
        inject(service, "repinWorkProcessTaskRepository", taskRepo);
        inject(service, "entityManager", entityManagerReturning(1L));

        Throwable thrown = catchThrowable(() -> service.dropCompiledRuntime(FACTORY, 10721L));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode())
                .isEqualTo("PRODUCTION_PLAN_TASKS_REFERENCED");
        verifyNoInteractions(taskRepo);
    }

    private EntityManager entityManagerReturning(long softReferences) {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(any(String.class))).thenReturn(query);
        when(query.setParameter(any(String.class), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(softReferences);
        return em;
    }
    @Test
    @DisplayName("干净计划 → 菜单判据放行(阴性对照: 上一条不是因为恒返回原因才绿)")
    void menuPredicateAllowsCleanPlan() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProcessSheetRowRepository rowRepo = mock(ProcessSheetRowRepository.class);
        when(rowRepo.findByFactoryIdAndPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(row("SAVED", ProcessSheetRow.SUBMISSION_DRAFT, null)));

        assertThat(newService(planRepo, rowRepo)
                .repinBlockedReason(FACTORY, PLAN_ID, false, () -> false)).isNull();
    }

    @Test
    @DisplayName("一条报工行都没有 → 不得被开工判据拦下")
    void planWithoutAnyRowIsNotTreatedAsStarted() throws Exception {
        Throwable thrown = repinWith(List.of());

        if (thrown instanceof BusinessException business) {
            assertThat(business.getErrorCode()).isNotEqualTo("PRODUCTION_PLAN_ALREADY_STARTED");
        }
    }
}
