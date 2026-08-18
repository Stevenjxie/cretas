package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.workflow.impl.WorkflowClerkSheetServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 画布上的「副产」角色必须能到达报工端 —— 产品负责人 2026-08-17 当面报障。
 *
 * <h3>缺陷</h3>
 * F006 / SOP-20260817-01-黄油鸡「原料处理」工序, 画布上产出物料区挂了两个 Cell:
 * 「半成品 Cell」处理后半成品 (SKU PTF0060156) 和「副产 Cell」肥油 (SKU YL119)。
 * 到了逐道报工界面, 肥油**被标成「半成品」而且独立成了一行**。
 *
 * <h3>角色是在哪一层丢的 (prod 实测, 逐层)</h3>
 * <pre>
 * 画布物料节点 data.isByproduct   ✅ prod wf#172 节点 material:output:1786934233525 = true
 * 工序节点的产出 port             ❌ port output:1786934233525 只有 materialKind=SEMI_FINISHED
 * workflow_task_ports 表          ❌ 全库 material_kind 只有 RAW_MATERIAL/SEMI_FINISHED/
 *                                    FINISHED_GOOD 三个值, 没有任何副产列
 * PortDescriptor                  ❌ 没有字段
 * 报工界面标签                     ❌ finished ? '成品' : '半成品' —— 二选一, 没有副产这一态
 * </pre>
 *
 * 所以这是本仓的**形态 B「机制在, 只是没接上」**: 副产角色在成本侧早就是一等公民
 * ({@code BomRecipe.outputRole=BY_PRODUCT} / NRV 扣减), 缺的是画布 → 报工这根线。
 *
 * <h3>为什么不给 workflow_task_ports 加一列</h3>
 * 那会让同一个事实存两份 (形态 D「同一个东西有两份, 它一定会漂」)。nodes_json 是权威源,
 * 而 {@code WorkflowTaskPort.materialNodeId} 正是指回那个节点的 join key —— 读时投影即可。
 *
 * <p>本测试直接打纯函数, 不起 Spring。fixture 用的是 **prod 上真实的 nodes_json 形状**
 * (节点 id / kind / data 逐字取自 cretas_prod_db 的 product_process_workflows#172)。
 */
class WorkflowByproductRoleProjectionContractTest {

    /**
     * prod wf#172 (F006 黄油鸡) 的节点形状。
     *
     * <p>⚠️ 注意副产节点的 {@code "kind"} 是 <b>SEMI_FINISHED</b> —— 画布刻意没有第 5 个
     * kind, 副产是与材质分类**正交**的一个标记。这正是「只看 kind/finished 就把副产
     * 显示成半成品」的根源。
     */
    private static final String BUTTER_CHICKEN_NODES = """
            [
              {"id":"material:raw","kind":"RAW_MATERIAL",
               "data":{"name":"SOP-20260817-01-黄油鸡-原料A","skuId":"RMT_41e1a2d4","bound":true}},
              {"id":"process:e5551abc:1786933016386","kind":"PROCESS",
               "data":{"processName":"SOP-20260817-01-黄油鸡-原料处理","ports":[
                  {"id":"output:1786933016386","direction":"OUTPUT","ordinal":0,
                   "materialKind":"SEMI_FINISHED","materialNodeId":"material:semi:1786933016386"},
                  {"id":"output:1786934233525","direction":"OUTPUT","ordinal":1,
                   "materialKind":"SEMI_FINISHED","materialNodeId":"material:output:1786934233525"}]}},
              {"id":"material:semi:1786933016386","kind":"SEMI_FINISHED",
               "data":{"name":"SOP-20260817-01-黄油鸡-处理后半成品","skuCode":"PTF0060156","bound":true}},
              {"id":"material:finished:1786933141612","kind":"FINISHED_GOOD",
               "data":{"name":"SOP-20260817-01-黄油鸡-成品800g","bound":true}},
              {"id":"material:output:1786934233525","kind":"SEMI_FINISHED",
               "data":{"name":"SOP-20260817-01-黄油鸡-肥油","skuCode":"YL119","bound":true,
                       "isByproduct":true}}
            ]
            """;

    /** prod workflow_task_ports 上这两条 OUTPUT 行的 material_node_id (实测值)。 */
    private static final String MAIN_OUTPUT_NODE_ID = "material:semi:1786933016386";
    private static final String BYPRODUCT_OUTPUT_NODE_ID = "material:output:1786934233525";

    // ------------------------------------------------------------------
    // 副产节点识别 —— 阳性 + 阴性对照成对出现
    // ------------------------------------------------------------------

    @Test
    @DisplayName("🔴 回归: 画布标了副产的物料节点被识别出来 (阳性对照: 肥油在集合里)")
    void byproductNodeIsDetected() {
        Set<String> marked = WorkflowByproductNodes.byproductMaterialNodeIds(BUTTER_CHICKEN_NODES);

        assertThat(marked)
                .as("肥油节点 data.isByproduct=true, 必须被认出来; 认不出来报工端就还是显示「半成品」")
                .contains(BYPRODUCT_OUTPUT_NODE_ID);
    }

    @Test
    @DisplayName("阴性对照: 半成品/成品/原料节点【不】被当成副产 —— 否则整张表全变副产")
    void nonByproductNodesAreNotDetected() {
        Set<String> marked = WorkflowByproductNodes.byproductMaterialNodeIds(BUTTER_CHICKEN_NODES);

        assertThat(marked)
                .as("没有 isByproduct 标记的节点一个都不许进来, 否则这个判据是恒真的")
                .doesNotContain(
                        MAIN_OUTPUT_NODE_ID,
                        "material:finished:1786933141612",
                        "material:raw",
                        "process:e5551abc:1786933016386");
        assertThat(marked)
                .as("这张图上有且只有一个副产 Cell")
                .hasSize(1);
    }

    @Test
    @DisplayName("join key 对得上: 报工端口的 materialNodeId 就是画布节点 id")
    void portMaterialNodeIdJoinsBackToCanvasNode() {
        Set<String> marked = WorkflowByproductNodes.byproductMaterialNodeIds(BUTTER_CHICKEN_NODES);

        // 这两个常量是 prod workflow_task_ports 里的实测值 —— 证明这根线在真实数据上接得上,
        // 而不是只在我自己造的 fixture 里成立。
        assertThat(marked.contains(BYPRODUCT_OUTPUT_NODE_ID))
                .as("副产端口 (prod id=736) 必须被判为副产")
                .isTrue();
        assertThat(marked.contains(MAIN_OUTPUT_NODE_ID))
                .as("主产出端口 (prod id=732) 必须【不】被判为副产")
                .isFalse();
    }

    @Test
    @DisplayName("truthiness 认字符串 \\\"true\\\" —— jsonb 快照里这个标记出现过字符串形态")
    void stringTrueIsAccepted() {
        String stringFlag = """
                [{"id":"material:output:x","kind":"SEMI_FINISHED","data":{"isByproduct":"true"}}]
                """;
        assertThat(WorkflowByproductNodes.byproductMaterialNodeIds(stringFlag))
                .as("只认布尔会让存量副产静默退化成普通半成品")
                .containsExactly("material:output:x");
    }

    @Test
    @DisplayName("没有标记 / false / 解析失败 一律不猜, 返回空集合 (老工作流零回归)")
    void absentOrFalseOrBrokenYieldsEmpty() {
        assertThat(WorkflowByproductNodes.byproductMaterialNodeIds(
                """
                [{"id":"material:semi:1","kind":"SEMI_FINISHED","data":{"name":"普通半成品"}}]
                """)).isEmpty();
        assertThat(WorkflowByproductNodes.byproductMaterialNodeIds(
                """
                [{"id":"material:semi:1","kind":"SEMI_FINISHED","data":{"isByproduct":false}}]
                """)).isEmpty();
        assertThat(WorkflowByproductNodes.byproductMaterialNodeIds("{not json"))
                .as("解析失败不该让整张报工单打不开 —— 与 resolveAllowMultipleUpstreamSources 同口径")
                .isEmpty();
        assertThat(WorkflowByproductNodes.byproductMaterialNodeIds(null)).isEmpty();
        assertThat(WorkflowByproductNodes.byproductMaterialNodeIds("")).isEmpty();
    }

    @Test
    @DisplayName("多个副产 Cell 时【全部】返回 —— 不许只认第一个")
    void allByproductNodesAreReturned() {
        String twoByproducts = """
                [{"id":"material:output:a","kind":"SEMI_FINISHED","data":{"isByproduct":true}},
                 {"id":"material:semi:main","kind":"SEMI_FINISHED","data":{"name":"主产出"}},
                 {"id":"material:output:b","kind":"SEMI_FINISHED","data":{"isByproduct":true}}]
                """;
        assertThat(WorkflowByproductNodes.byproductMaterialNodeIds(twoByproducts))
                .as("静默丢掉用户在画布上配的第二个副产是本仓明令禁止的形状")
                .containsExactlyInAnyOrder("material:output:a", "material:output:b");
    }

    // ------------------------------------------------------------------
    // 单产出向后兼容契约: 顶层 output / plannedUnit 不能被副产顶替
    // ------------------------------------------------------------------

    private static WorkflowClerkSheetConfigDTO.PortDescriptor port(
            String portId, String unit, boolean byproduct) {
        return WorkflowClerkSheetConfigDTO.PortDescriptor.builder()
                .workflowPortId(portId)
                .unit(unit)
                .byproduct(byproduct)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static WorkflowClerkSheetConfigDTO.PortDescriptor primaryOutput(
            List<WorkflowClerkSheetConfigDTO.PortDescriptor> outputs) throws Exception {
        Method m = WorkflowClerkSheetServiceImpl.class
                .getDeclaredMethod("primaryOutput", List.class);
        m.setAccessible(true);
        return (WorkflowClerkSheetConfigDTO.PortDescriptor) m.invoke(null, outputs);
    }

    @Test
    @DisplayName("🔴 副产排在前面时, 顶层单产出契约仍取【主产出】, 不取副产")
    void primaryOutputSkipsByproductEvenWhenItSortsFirst() throws Exception {
        // 副产 ordinal 排前面的图是合法的 —— 端口顺序由用户在画布上决定。
        var outputs = List.of(
                port("output:fat", "kg", true),
                port("output:semi", "箱", false));

        assertThat(primaryOutput(outputs).getWorkflowPortId())
                .as("取到副产会让 plannedUnit / 顶层 productTypeId 变成副产的, 而副产是附带产物")
                .isEqualTo("output:semi");
        assertThat(primaryOutput(outputs).getUnit()).isEqualTo("箱");
    }

    @Test
    @DisplayName("没有副产时行为逐字不变 —— 仍是第一个")
    void primaryOutputUnchangedWithoutByproducts() throws Exception {
        var outputs = List.of(
                port("output:a", "kg", false),
                port("output:b", "箱", false));
        assertThat(primaryOutput(outputs).getWorkflowPortId()).isEqualTo("output:a");
    }

    @Test
    @DisplayName("全是副产时回落第一个, 不抛 —— 配置问题不该让整张报工单打不开")
    void primaryOutputFallsBackWhenEverythingIsByproduct() throws Exception {
        var outputs = List.of(port("output:a", "kg", true), port("output:b", "kg", true));
        assertThat(primaryOutput(outputs).getWorkflowPortId()).isEqualTo("output:a");
        assertThat(primaryOutput(List.of())).isNull();
    }

    // ------------------------------------------------------------------
    // 接线 —— 上面全是零件, 这一节问的是「生产上谁保证它被调用」
    //
    // ⚠️ 这一节是补上去的: 第一版只测了纯函数和 primaryOutput, 而把 buildDescriptor 里
    //    那句 byproductMaterialNodeIds(instanceNodesJson) 换成 Set.of() 的变异
    //    **一条测试都没红** —— 零件全对, 线没接上, 正是这次要修的那个形态本身。
    // ------------------------------------------------------------------

    private static WorkflowTaskPort outputPort(String portId, String materialNodeId, int ordinal) {
        WorkflowTaskPort port = new WorkflowTaskPort();
        port.setWorkflowPortId(portId);
        port.setMaterialNodeId(materialNodeId);
        port.setDirection(WorkflowTaskPort.Direction.OUTPUT);
        port.setOrdinal(ordinal);
        port.setMaterialKind("SEMI_FINISHED");
        port.setSkuId("sku-" + ordinal);
        port.setUnit("kg");
        port.setRequired(Boolean.TRUE);
        return port;
    }

    /**
     * 走真实入口 {@code buildDescriptor}。
     *
     * <p>协作者用 mock 而不是 null: 真实入口会调 workProcess / productWorkProcess / sku 三处查询,
     * null 依赖会 NPE 在业务判断之前 —— 那样测的就不是这条路了。
     * 任务状态取 COMPLETED 使 {@code projectReportingUnits=false}, 从而不牵扯单位解析器 ——
     * 本测试要钉的是**副产角色有没有传下来**, 不是单位怎么算。
     */
    private static WorkflowClerkSheetConfigDTO.ProcessDescriptor buildDescriptorVia(
            List<WorkflowTaskPort> ports, String nodesJson) throws Exception {
        WorkflowClerkSheetServiceImpl service = new WorkflowClerkSheetServiceImpl(
                mock(ProductionBatchRepository.class),
                mock(ProductionPlanRepository.class),
                mock(ProductionWorkflowInstanceRepository.class),
                mock(WorkProcessTaskRepository.class),
                mock(WorkflowTaskPortRepository.class),
                mock(WorkProcessRepository.class),
                mock(ProductWorkProcessRepository.class),
                mock(RawMaterialTypeRepository.class),
                mock(ProductTypeRepository.class),
                mock(BomRecipeRepository.class),
                mock(BomRecipeItemRepository.class),
                mock(BomItemSubstituteService.class),
                mock(WorkflowReportingUnitResolver.class));

        WorkProcessTask task = new WorkProcessTask();
        task.setWorkflowNodeId("process:e5551abc:1786933016386");
        task.setWorkProcessId("e5551abc");
        task.setProcessOrder(1);
        task.setStatus(WorkProcessTask.Status.COMPLETED);
        task.setPlannedUnit("kg");

        Method m = WorkflowClerkSheetServiceImpl.class.getDeclaredMethod(
                "buildDescriptor", String.class, WorkProcessTask.class,
                List.class, Map.class, String.class);
        m.setAccessible(true);
        return (WorkflowClerkSheetConfigDTO.ProcessDescriptor)
                m.invoke(service, "F006", task, ports, Map.of(), nodesJson);
    }

    @Test
    @DisplayName("🔴 接线: 真实入口产出的 PortDescriptor 上, 副产端口 byproduct=true、主产出=false")
    void buildDescriptorProjectsByproductRoleOntoOutputPorts() throws Exception {
        List<WorkflowTaskPort> ports = List.of(
                outputPort("output:1786933016386", MAIN_OUTPUT_NODE_ID, 0),
                outputPort("output:1786934233525", BYPRODUCT_OUTPUT_NODE_ID, 1));

        var descriptor = buildDescriptorVia(ports, BUTTER_CHICKEN_NODES);

        var byPortId = descriptor.getOutputs().stream()
                .collect(java.util.stream.Collectors.toMap(
                        WorkflowClerkSheetConfigDTO.PortDescriptor::getWorkflowPortId,
                        p -> p));

        assertThat(byPortId.get("output:1786934233525").getByproduct())
                .as("肥油端口必须带着副产角色到达报工端 —— 这一条断了, 界面上它就又变回「半成品」")
                .isTrue();
        assertThat(byPortId.get("output:1786933016386").getByproduct())
                .as("阴性对照: 主产出不许被误标成副产, 否则这个判据是恒真的")
                .isFalse();
    }

    @Test
    @DisplayName("接线阴性对照: 图里没有副产时, 所有产出端口 byproduct 都是 false")
    void buildDescriptorMarksNothingWhenGraphHasNoByproduct() throws Exception {
        String noByproduct = """
                [{"id":"material:semi:1786933016386","kind":"SEMI_FINISHED","data":{"name":"普通半成品"}},
                 {"id":"material:output:1786934233525","kind":"SEMI_FINISHED","data":{"name":"另一个半成品"}}]
                """;
        List<WorkflowTaskPort> ports = List.of(
                outputPort("output:1786933016386", MAIN_OUTPUT_NODE_ID, 0),
                outputPort("output:1786934233525", BYPRODUCT_OUTPUT_NODE_ID, 1));

        var descriptor = buildDescriptorVia(ports, noByproduct);

        assertThat(descriptor.getOutputs())
                .as("没标副产就一个都不许标 —— 老工作流零回归")
                .allSatisfy(p -> assertThat(p.getByproduct()).isFalse());
    }

    @Test
    @DisplayName("接线: 顶层单产出契约 output 取主产出, 不取排在前面的副产")
    void buildDescriptorTopLevelOutputSkipsByproduct() throws Exception {
        // 副产 ordinal=0 排在主产出前面。
        List<WorkflowTaskPort> ports = List.of(
                outputPort("output:1786934233525", BYPRODUCT_OUTPUT_NODE_ID, 0),
                outputPort("output:1786933016386", MAIN_OUTPUT_NODE_ID, 1));

        var descriptor = buildDescriptorVia(ports, BUTTER_CHICKEN_NODES);

        assertThat(descriptor.getOutput().getWorkflowPortId())
                .as("顶层 output 是给单产出前端用的向后兼容契约, 取到副产会让顶层品名/单位变成副产的")
                .isEqualTo("output:1786933016386");
    }
}
