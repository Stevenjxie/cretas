package com.cretas.aims.service.wip;

import com.cretas.aims.dto.yield.OutputOptionsResponse;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * App 报工屏「本次产出什么」下拉框对 workflow(画布)配置的产品返回空 —— 缺陷复现 + 修复契约。
 *
 * <h2>缺陷</h2>
 *
 * <p>{@code GET .../processing/batches/{batchId}/output-options} 只查
 * {@code WorkProcess.semiFinishedOutputCode}（旧版"工序管理"模型的字段）。2026-08-18 F006 prod
 * 库实测：画布任务的 {@code WorkProcess} 行该字段 100% 为空（211 行里 55 行配了它，但 0 行被任何
 * {@code work_process_task} 引用 —— 全是 E2E/Codex 测试夹具，从未进过真实生产路径），而当天 prod
 * 上活跃的 14 条 {@code work_process_task} 全部是 workflow 任务。净效果：任何走画布配置的产品，
 * 无论画布上声明了几个半成品产出端口，接口恒返回 {@code items: []}。
 *
 * <p>画布任务的产出结构表达在 {@code workflow_task_ports}（{@code direction=OUTPUT,
 * materialKind=SEMI_FINISHED}），不在 {@code WorkProcess.semiFinishedOutputCode}。
 *
 * <h2>本闸挂在哪</h2>
 *
 * <p>本仓的 {@code java-build-test} push 触发选择器只捞 {@code *ContractTest} 等四个后缀
 * （见 {@code .github/workflows/ci.yml}），本类以此后缀命名以确保合并后仍被自动跑到 ——
 * 更详尽的边界情形（多产出/副产排除/缺编码跳过各自的阴阳性对照、变异对照实测记录）
 * 在 {@code WipInventoryServiceImplTest} 的 "SP1 T4 workflow(画布) 分支" 一节。
 */
@DisplayName("output-options: workflow(画布)任务的半成品产出选项")
class OutputOptionsWorkflowContractTest {

    private static final String FACTORY = "F006";
    private static final Long BATCH_ID = 10762L;

    private WorkProcessTaskRepository taskRepo;
    private WorkProcessRepository workProcessRepo;
    private ProductTypeRepository productTypeRepo;
    private ProductionWorkflowInstanceRepository workflowInstanceRepo;
    private WorkflowTaskPortRepository workflowTaskPortRepo;
    private WipInventoryServiceImpl svc;

    @BeforeEach
    void setUp() {
        taskRepo = mock(WorkProcessTaskRepository.class);
        workProcessRepo = mock(WorkProcessRepository.class);
        productTypeRepo = mock(ProductTypeRepository.class);
        workflowInstanceRepo = mock(ProductionWorkflowInstanceRepository.class);
        workflowTaskPortRepo = mock(WorkflowTaskPortRepository.class);

        svc = new WipInventoryServiceImpl(
                mock(SemiFinishedInventoryRepository.class),
                mock(SemiFinishedInventoryTransactionRepository.class),
                mock(ProductionReportRepository.class),
                mock(BatchLineageEdgeRepository.class),
                taskRepo,
                workProcessRepo,
                productTypeRepo,
                workflowInstanceRepo,
                workflowTaskPortRepo,
                mock(ProductFamilyResolver.class),
                mock(ApplicationEventPublisher.class));
    }

    private WorkflowTaskPort port(Long taskId, WorkflowTaskPort.Direction direction,
                                   String materialKind, String materialNodeId, String skuId) {
        WorkflowTaskPort p = new WorkflowTaskPort();
        p.setFactoryId(FACTORY);
        p.setWorkflowInstanceId(84L);
        p.setTaskId(taskId);
        p.setWorkflowPortId("out:" + materialNodeId);
        p.setDirection(direction);
        p.setOrdinal(0);
        p.setMaterialNodeId(materialNodeId);
        p.setMaterialKind(materialKind);
        p.setSkuId(skuId);
        p.setUnit("kg");
        p.setUnitCode("kg");
        p.setRequired(true);
        return p;
    }

    private ProductType semiType(String id, String code, String name) {
        ProductType pt = new ProductType();
        pt.setId(id);
        pt.setFactoryId(FACTORY);
        pt.setCode(code);
        pt.setName(name);
        return pt;
    }

    /**
     * 阳性对照 + 修复契约 —— 复现 2026-08-18 F006 batch 10762 的真实形状: 「拆骨」一道工序
     * 在画布上声明了两个 SEMI_FINISHED 产出端口(猪蹄净肉 + 猪蹄碎肉, 2B.2 多产出), 该工序的
     * WorkProcess.semiFinishedOutputCode 为空(与 prod 实测一致)。修复前 items 恒为 []。
     */
    @Test
    @DisplayName("🔴 修复前恒空, 修复后按画布端口返回可读编码(非 UUID) —— 复现 F006 batch 10762 拆骨双产出")
    void workflowTaskWithTwoSemiOutputPorts_returnsTwoReadableCodes() {
        Long taskId = 1790L;
        WorkProcessTask task = WorkProcessTask.builder()
                .id(taskId).factoryId(FACTORY).productionBatchId(BATCH_ID)
                .workProcessId("e806ee32-c29d-460f-9089-ba308badc9cf")
                .workflowInstanceId(84L).processOrder(1).build();

        WorkProcess wp = WorkProcess.builder()
                .id("e806ee32-c29d-460f-9089-ba308badc9cf").factoryId(FACTORY)
                .processName("拆骨").build();   // 画布任务恒不配 semiFinishedOutputCode

        ProductionWorkflowInstance instance = ProductionWorkflowInstance.create(
                FACTORY, BATCH_ID, "PT-F006-LSM", 171L, 3, "[]", "[]", LocalDateTime.now());
        instance.setId(84L);

        WorkflowTaskPort inPig = port(taskId, WorkflowTaskPort.Direction.INPUT,
                "RAW_MATERIAL", "material:raw:pig", "RMT_1777441647274");
        WorkflowTaskPort outS1 = port(taskId, WorkflowTaskPort.Direction.OUTPUT,
                "SEMI_FINISHED", "material:semi:s1", "53cce73b-32be-4acb-911a-840167d6bc86");
        WorkflowTaskPort outS2 = port(taskId, WorkflowTaskPort.Direction.OUTPUT,
                "SEMI_FINISHED", "material:semi:s2", "2f00956d-3e8e-4c16-8fb2-da0226c238a8");

        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY, BATCH_ID))
                .thenReturn(List.of(task));
        when(workProcessRepo.findByFactoryIdAndId(FACTORY, wp.getId())).thenReturn(Optional.of(wp));
        when(workflowInstanceRepo.findByFactoryIdAndProductionBatchId(FACTORY, BATCH_ID))
                .thenReturn(Optional.of(instance));
        when(workflowTaskPortRepo.findByFactoryIdAndWorkflowInstanceId(FACTORY, 84L))
                .thenReturn(List.of(inPig, outS1, outS2));
        when(workProcessRepo.findByFactoryIdAndIdIn(eq(FACTORY), any())).thenReturn(List.of(wp));
        when(productTypeRepo.findByFactoryIdAndIdIn(eq(FACTORY), any())).thenReturn(List.of(
                semiType("53cce73b-32be-4acb-911a-840167d6bc86", "PTSEMI-F006-2001", "猪蹄净肉(半成品)"),
                semiType("2f00956d-3e8e-4c16-8fb2-da0226c238a8", "PTSEMI-F006-2002", "猪蹄碎肉(半成品)")));

        OutputOptionsResponse resp = svc.getOutputOptions(FACTORY, BATCH_ID);

        assertThat(resp.getItems())
                .as("画布拆骨工序声明了两个半成品产出端口, 修复前这里恒为空列表")
                .hasSize(2);
        assertThat(resp.getItems())
                .extracting(OutputOptionsResponse.OutputOptionItem::getSemiCode)
                .as("semiCode 必须是人类可读的 ProductType.code, 不许把 skuId UUID 甩给操作员")
                .containsExactlyInAnyOrder("PTSEMI-F006-2001", "PTSEMI-F006-2002");
        assertThat(resp.getItems())
                .allSatisfy(item -> assertThat(item.getTaskId()).isEqualTo(taskId));
    }

    @Test
    @DisplayName("画布 data.isByproduct 标记的端口不出现在半成品产出选项里")
    void byproductFlaggedPort_isExcluded() {
        Long taskId = 20L;
        WorkProcessTask task = WorkProcessTask.builder()
                .id(taskId).factoryId(FACTORY).productionBatchId(BATCH_ID)
                .workProcessId("WP-X").workflowInstanceId(84L).processOrder(1).build();
        WorkProcess wp = WorkProcess.builder().id("WP-X").factoryId(FACTORY).processName("分割").build();

        String nodesJson = "[{\"id\":\"material:semi:byp\",\"data\":{\"isByproduct\":true}}]";
        ProductionWorkflowInstance instance = ProductionWorkflowInstance.create(
                FACTORY, BATCH_ID, "PT-X", 171L, 1, nodesJson, "[]", LocalDateTime.now());
        instance.setId(84L);

        WorkflowTaskPort byp = port(taskId, WorkflowTaskPort.Direction.OUTPUT,
                "SEMI_FINISHED", "material:semi:byp", "SKU-BYP");

        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY, BATCH_ID))
                .thenReturn(List.of(task));
        when(workProcessRepo.findByFactoryIdAndId(FACTORY, "WP-X")).thenReturn(Optional.of(wp));
        when(workflowInstanceRepo.findByFactoryIdAndProductionBatchId(FACTORY, BATCH_ID))
                .thenReturn(Optional.of(instance));
        when(workflowTaskPortRepo.findByFactoryIdAndWorkflowInstanceId(FACTORY, 84L))
                .thenReturn(List.of(byp));

        OutputOptionsResponse resp = svc.getOutputOptions(FACTORY, BATCH_ID);

        assertThat(resp.getItems()).isEmpty();
    }

    @Test
    @DisplayName("阴性对照(阳性场景不变): 非 workflow 批次仍走 legacy 字段, 本次修复不影响旧行为")
    void legacyTask_stillUsesWorkProcessSemiFinishedOutputCode() {
        Long taskId = 30L;
        WorkProcessTask task = WorkProcessTask.builder()
                .id(taskId).factoryId(FACTORY).productionBatchId(9999L)
                .workProcessId("WP-LEGACY").processOrder(1).build();   // 无 workflowInstanceId
        WorkProcess wp = WorkProcess.builder()
                .id("WP-LEGACY").factoryId(FACTORY).processName("焯水")
                .semiFinishedOutputCode("WIP-LEGACY-01").build();

        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY, 9999L))
                .thenReturn(List.of(task));
        when(workProcessRepo.findByFactoryIdAndId(FACTORY, "WP-LEGACY")).thenReturn(Optional.of(wp));
        when(workflowInstanceRepo.findByFactoryIdAndProductionBatchId(FACTORY, 9999L))
                .thenReturn(Optional.empty());

        OutputOptionsResponse resp = svc.getOutputOptions(FACTORY, 9999L);

        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getSemiCode()).isEqualTo("WIP-LEGACY-01");
    }
}
