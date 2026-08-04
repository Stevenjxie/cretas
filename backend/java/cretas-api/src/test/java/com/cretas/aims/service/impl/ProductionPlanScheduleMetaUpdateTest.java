package com.cretas.aims.service.impl;

import com.cretas.aims.dto.production.UpdatePlanScheduleMetaRequest;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 排产元数据更新 (updatePlanScheduleMeta) 的契约测试。
 *
 * <p>背景 (2026-08-04): 生产计划的「编辑」此前只对 PENDING/PREPARED 开放 —— 后端
 * {@code updateProductionPlan} 对其余状态一律 409。可 prod 上一半的未完成计划是 IN_PROGRESS,
 * 厂长开工后连「预计完成日期/指派主管」都改不了。
 *
 * <p>放开的方式**不是**把那条守卫放宽一档, 而是新开一条**入参更窄**的路径: 它收不到
 * 计划日期/计划数量/产品/来源单, 所以开工后放行也不可能把已发生的报工/批次事实改脱节 ——
 * 批次日期进批次号与追溯, 计划数量是结单与小结的比对基准。本测试的头号断言就是这一条:
 * 走这条路径之后, 硬字段必须逐字不变。
 */
@DisplayName("updatePlanScheduleMeta — 已开工可改软字段, 硬字段与终态一律不可动")
class ProductionPlanScheduleMetaUpdateTest {

    private static final String FACTORY = "F006";
    private static final String OTHER_FACTORY = "F001";
    private static final String PLAN_ID = "PLAN-SCHED-1";
    private static final LocalDate ORIGINAL_PLANNED_DATE = LocalDate.of(2026, 8, 2);
    private static final BigDecimal ORIGINAL_QUANTITY = new BigDecimal("100.00");

    private ProductionPlanServiceImpl newService(ProductionPlanRepository planRepo,
                                                 UserRepository userRepo) throws Exception {
        Constructor<?> ctor = ProductionPlanServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ProductionPlanRepository.class) {
                args[i] = planRepo;
            } else if (types[i] == UserRepository.class) {
                args[i] = userRepo;
            } else {
                args[i] = null;
            }
        }
        return (ProductionPlanServiceImpl) ctor.newInstance(args);
    }

    private ProductionPlan plan(ProductionPlanStatus status) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        plan.setStatus(status);
        plan.setPlannedDate(ORIGINAL_PLANNED_DATE);
        plan.setPlannedQuantity(ORIGINAL_QUANTITY);
        plan.setExpectedCompletionDate(LocalDate.of(2026, 8, 5));
        plan.setEstimatedWorkers(2);
        plan.setNotes("原备注");
        return plan;
    }

    private UpdatePlanScheduleMetaRequest request(Long supervisorId) {
        return new UpdatePlanScheduleMetaRequest(
                LocalDate.of(2026, 8, 9), 5, supervisorId, "改后的备注");
    }

    @Test
    @DisplayName("IN_PROGRESS 可改四个软字段, 计划日期与计划数量逐字不变")
    void inProgressUpdatesSoftFieldsAndLeavesHardFieldsUntouched() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        ProductionPlan existing = plan(ProductionPlanStatus.IN_PROGRESS);
        when(planRepo.findById(PLAN_ID)).thenReturn(Optional.of(existing));
        when(planRepo.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        User supervisor = new User();
        supervisor.setId(77L);
        supervisor.setFactoryId(FACTORY);
        when(userRepo.findById(77L)).thenReturn(Optional.of(supervisor));

        ProductionPlanServiceImpl service = newService(planRepo, userRepo);
        Throwable thrown = catchThrowable(() ->
                service.updatePlanScheduleMeta(FACTORY, PLAN_ID, request(77L)));

        // toDTOWithConversionInfo 依赖未注入的协作者, 这里只关心写入的实体状态 ——
        // 断言落在 save 进去的那个对象上, 而不是返回值。
        verify(planRepo).save(existing);
        assertThat(existing.getExpectedCompletionDate()).isEqualTo(LocalDate.of(2026, 8, 9));
        assertThat(existing.getEstimatedWorkers()).isEqualTo(5);
        assertThat(existing.getAssignedSupervisorId()).isEqualTo(77L);
        assertThat(existing.getNotes()).isEqualTo("改后的备注");
        // 头号契约: 这条路径碰不到硬字段
        assertThat(existing.getPlannedDate()).isEqualTo(ORIGINAL_PLANNED_DATE);
        assertThat(existing.getPlannedQuantity()).isEqualByComparingTo(ORIGINAL_QUANTITY);
        assertThat(existing.getStatus()).isEqualTo(ProductionPlanStatus.IN_PROGRESS);
        // 落到 DTO 装配才失败是可以接受的; 断言不能因为它变成"守卫拒绝"的假象
        if (thrown != null) {
            assertThat(thrown).isNotInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("PAUSED 同样放行 —— 暂停是开工后的中间态, 不是终态")
    void pausedIsAllowed() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionPlan existing = plan(ProductionPlanStatus.PAUSED);
        when(planRepo.findById(PLAN_ID)).thenReturn(Optional.of(existing));
        when(planRepo.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductionPlanServiceImpl service = newService(planRepo, mock(UserRepository.class));
        catchThrowable(() -> service.updatePlanScheduleMeta(FACTORY, PLAN_ID, request(null)));

        verify(planRepo).save(existing);
        assertThat(existing.getNotes()).isEqualTo("改后的备注");
    }

    @Test
    @DisplayName("终态与审批中一律 409, 且不写库")
    void terminalStatusesRejected() throws Exception {
        for (ProductionPlanStatus status : new ProductionPlanStatus[]{
                ProductionPlanStatus.COMPLETED,
                ProductionPlanStatus.CANCELLED,
                ProductionPlanStatus.PENDING_APPROVAL}) {
            ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
            ProductionPlan existing = plan(status);
            when(planRepo.findById(PLAN_ID)).thenReturn(Optional.of(existing));

            ProductionPlanServiceImpl service = newService(planRepo, mock(UserRepository.class));
            Throwable thrown = catchThrowable(() ->
                    service.updatePlanScheduleMeta(FACTORY, PLAN_ID, request(null)));

            assertThat(thrown).as("status=%s", status).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getCode()).as("status=%s", status).isEqualTo(409);
            verify(planRepo, never()).save(any(ProductionPlan.class));
            assertThat(existing.getNotes()).as("status=%s 不应被写入", status).isEqualTo("原备注");
        }
    }

    @Test
    @DisplayName("锁定的计划 409, 与全量编辑口径一致")
    void lockedPlanRejected() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionPlan existing = plan(ProductionPlanStatus.IN_PROGRESS);
        existing.setIsLocked(true);
        when(planRepo.findById(PLAN_ID)).thenReturn(Optional.of(existing));

        ProductionPlanServiceImpl service = newService(planRepo, mock(UserRepository.class));
        Throwable thrown = catchThrowable(() ->
                service.updatePlanScheduleMeta(FACTORY, PLAN_ID, request(null)));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getCode()).isEqualTo(409);
        verify(planRepo, never()).save(any(ProductionPlan.class));
    }

    @Test
    @DisplayName("跨厂计划 403, 不写库")
    void crossFactoryRejected() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionPlan existing = plan(ProductionPlanStatus.IN_PROGRESS);
        when(planRepo.findById(PLAN_ID)).thenReturn(Optional.of(existing));

        ProductionPlanServiceImpl service = newService(planRepo, mock(UserRepository.class));
        Throwable thrown = catchThrowable(() ->
                service.updatePlanScheduleMeta(OTHER_FACTORY, PLAN_ID, request(null)));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getCode()).isEqualTo(403);
        verify(planRepo, never()).save(any(ProductionPlan.class));
    }

    @Test
    @DisplayName("指派别厂用户为主管 403 —— 看板按 id 直接查用户, 不带 factory 过滤")
    void supervisorFromAnotherFactoryRejected() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        ProductionPlan existing = plan(ProductionPlanStatus.IN_PROGRESS);
        when(planRepo.findById(PLAN_ID)).thenReturn(Optional.of(existing));
        User foreign = new User();
        foreign.setId(88L);
        foreign.setFactoryId(OTHER_FACTORY);
        when(userRepo.findById(88L)).thenReturn(Optional.of(foreign));

        ProductionPlanServiceImpl service = newService(planRepo, userRepo);
        Throwable thrown = catchThrowable(() ->
                service.updatePlanScheduleMeta(FACTORY, PLAN_ID, request(88L)));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getCode()).isEqualTo(403);
        verify(planRepo, never()).save(any(ProductionPlan.class));
    }

    @Test
    @DisplayName("不存在的主管 400, 而不是让脏 id 落库")
    void unknownSupervisorRejected() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        ProductionPlan existing = plan(ProductionPlanStatus.IN_PROGRESS);
        when(planRepo.findById(PLAN_ID)).thenReturn(Optional.of(existing));
        when(userRepo.findById(99L)).thenReturn(Optional.empty());

        ProductionPlanServiceImpl service = newService(planRepo, userRepo);
        Throwable thrown = catchThrowable(() ->
                service.updatePlanScheduleMeta(FACTORY, PLAN_ID, request(99L)));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getCode()).isEqualTo(400);
        verify(planRepo, never()).save(any(ProductionPlan.class));
    }

    @Test
    @DisplayName("null 是清空而不是保持原值 —— PUT 语义, 否则清不掉指派主管与备注")
    void nullClearsInsteadOfKeeping() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionPlan existing = plan(ProductionPlanStatus.IN_PROGRESS);
        existing.setAssignedSupervisorId(55L);
        when(planRepo.findById(PLAN_ID)).thenReturn(Optional.of(existing));
        when(planRepo.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductionPlanServiceImpl service = newService(planRepo, mock(UserRepository.class));
        catchThrowable(() -> service.updatePlanScheduleMeta(FACTORY, PLAN_ID,
                new UpdatePlanScheduleMetaRequest(null, null, null, null)));

        verify(planRepo).save(existing);
        assertThat(existing.getAssignedSupervisorId()).isNull();
        assertThat(existing.getNotes()).isNull();
        assertThat(existing.getExpectedCompletionDate()).isNull();
        assertThat(existing.getEstimatedWorkers()).isNull();
        // 清空软字段依然不许碰硬字段
        assertThat(existing.getPlannedDate()).isEqualTo(ORIGINAL_PLANNED_DATE);
        assertThat(existing.getPlannedQuantity()).isEqualByComparingTo(ORIGINAL_QUANTITY);
    }
}
