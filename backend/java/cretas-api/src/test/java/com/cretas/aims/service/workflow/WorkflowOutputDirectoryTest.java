package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowOutputDirectoryDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.workflow.impl.WorkflowOutputDirectoryServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 配置侧反查(包含语义)。最重要的一条是
 * {@link #returnsEveryWorkflowProducingTheSkuInsteadOfTheSmallestSupersetLayer()} ——
 * 它就是「有没有偷偷复用计划侧 requireResolutionCandidates」的判别式: 计划侧会把超集层丢掉,
 * 配置侧一张都不能丢。
 */
class WorkflowOutputDirectoryTest {

    private ProductProcessWorkflowActivationRepository activations;
    private ProductProcessWorkflowRepository workflows;
    private ProductTypeRepository products;
    private WorkflowOutputDirectoryService service;
    private final List<ProductProcessWorkflowActivation> enabled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        activations = mock(ProductProcessWorkflowActivationRepository.class);
        workflows = mock(ProductProcessWorkflowRepository.class);
        products = mock(ProductTypeRepository.class);
        RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
        // 忠实模拟仓储方法名的语义 —— findByFactoryIdAndEnabledTrue 在 SQL 层就把 enabled=false
        // 滤掉了。若这里原样返回全部行, 用例会去测一个真实世界不存在的输入, 然后拿「服务没再滤一遍」
        // 当缺陷报出来(本用例第一版就是这么假红的)。
        when(activations.findByFactoryIdAndEnabledTrue("F1")).thenAnswer(ignored -> enabled.stream()
                .filter(row -> Boolean.TRUE.equals(row.getEnabled())).toList());
        when(products.findByIdIn(anyList())).thenAnswer(invocation -> invocation
                .<List<String>>getArgument(0).stream().map(this::product).toList());
        when(materials.findByIdIn(anyList())).thenReturn(List.of());
        service = new WorkflowOutputDirectoryServiceImpl(
                activations, workflows, products, materials, new ObjectMapper());
    }

    /**
     * 用户 2026-08-11 真机看到的那张图(wf=158): 顶部研判「原料分流」, 归属对象却写着某个成品。
     * 反查必须能靠**产出**找到它, 而不是靠锚点。
     */
    @Test
    void findsTheWorkflowByItsOutputEvenWhenTheStorageAnchorIsSomethingElse() {
        activate(158L, "ANCHOR-UNRELATED", List.of("RAW-A"), List.of("P1", "P2"), true, 1, 1);

        WorkflowOutputDirectoryDTO result = service.findWorkflowsProducing("F1", "P2");

        assertEquals(List.of(158L), workflowIds(result));
        WorkflowOutputDirectoryDTO.Entry entry = result.getWorkflows().getFirst();
        assertEquals("ANCHOR-UNRELATED", entry.getOwnerProductTypeId());
        assertEquals("RAW_MATERIAL_SPLIT", entry.getWorkflowType());
        assertEquals(List.of("P1", "P2"), entry.getTerminalOutputs().stream()
                .map(WorkflowOutputDirectoryDTO.TerminalOutput::getProductTypeId).toList());
        assertFalse(entry.isAnchorIsTerminalOutput(),
                "锚点不在终端产出里 —— 前端据此把归属对象降级成次要信息");
    }

    /**
     * ⛔ 判别式: 计划侧 requireResolutionCandidates 在有精确候选时会把超集候选**全部丢掉**。
     * 配置侧问的是「谁产出 P1」, 两张都要返回 —— 丢掉任何一张, 用户就在界面上找不到那张图。
     */
    @Test
    void returnsEveryWorkflowProducingTheSkuInsteadOfTheSmallestSupersetLayer() {
        activate(11L, "ANCHOR-EXACT", List.of("RAW-A"), List.of("P1"), true, 1, 1);
        activate(12L, "ANCHOR-JOINT", List.of("RAW-B", "RAW-C"), List.of("P1", "P2"), true, 1, 1);
        activate(13L, "ANCHOR-BIG", List.of("RAW-D"), List.of("P1", "P2", "P3"), true, 1, 1);

        WorkflowOutputDirectoryDTO result = service.findWorkflowsProducing("F1", "P1");

        assertEquals(List.of(11L, 12L, 13L), workflowIds(result),
                "包含语义: 三张图都产出 P1, 一张都不能被优先层收敛丢掉");
        assertEquals(List.of(1, 2, 3), result.getWorkflows().stream()
                        .map(entry -> entry.getTerminalOutputs().size()).toList(),
                "按终端产出数升序 —— 最贴近所查成品的图排最前, 但后面的图仍然全都在");
    }

    @Test
    void returnsEmptyListWhenNoWorkflowProducesTheSku() {
        activate(21L, "ANCHOR-A", List.of("RAW-A"), List.of("P1"), true, 1, 1);

        WorkflowOutputDirectoryDTO result = service.findWorkflowsProducing("F1", "P9");

        assertEquals("P9", result.getFinishedGoodProductTypeId());
        assertTrue(result.getWorkflows().isEmpty(),
                "查不到时返回空列表, 前端给明确空态 —— 不是抛错也不是给一张空白画布");
    }

    /**
     * activation.enabled 由仓储方法名在 SQL 层保证(下面 verify 钉住服务用的确实是那个 finder);
     * 「图还是 PUBLISHED 吗」「版本还对得上吗」这两条是**服务自己**的责任 —— activation 存的是
     * (workflow_id, version) 两个字段, 图后来被改回草稿或发了新版, activation 行不会自己更新。
     */
    @Test
    void excludesUnpublishedGraphsAndStaleVersionsAndOnlyAsksForEnabledActivations() {
        activate(32L, "ANCHOR-DRAFT", List.of("RAW-B"), List.of("P1"), true, 1, 1);
        draftify(32L);
        activate(33L, "ANCHOR-STALE", List.of("RAW-C"), List.of("P1"), true, 2, 1);
        activate(34L, "ANCHOR-LIVE", List.of("RAW-D"), List.of("P1"), true, 1, 1);

        WorkflowOutputDirectoryDTO result = service.findWorkflowsProducing("F1", "P1");

        assertEquals(List.of(34L), workflowIds(result),
                "草稿图 / 版本对不上的 activation 都不算「已启用」");
        verify(activations).findByFactoryIdAndEnabledTrue("F1");
        verifyNoMoreInteractions(activations);
    }

    /** 副产不是终端产出 —— 图产出的是「主成品」, 反查副产不该把这张图算成它的产线。 */
    @Test
    void byproductsAreNotTreatedAsTerminalOutputs() {
        activateWithByproduct(41L, "ANCHOR-BY", "P1", "BY-1");

        assertEquals(List.of(41L), workflowIds(service.findWorkflowsProducing("F1", "P1")));
        assertTrue(service.findWorkflowsProducing("F1", "BY-1").getWorkflows().isEmpty(),
                "副产不计入终端产出, 与 WorkflowTopologyClassifier#isByproduct 同口径");
    }

    @Test
    void anchorThatIsAlsoATerminalOutputIsFlagged() {
        activate(51L, "P1", List.of("RAW-A"), List.of("P1"), true, 1, 1);

        assertTrue(service.findWorkflowsProducing("F1", "P1")
                .getWorkflows().getFirst().isAnchorIsTerminalOutput());
    }

    @Test
    void blankTargetReturnsEmptyWithoutTouchingTheRepositories() {
        activate(61L, "ANCHOR-A", List.of("RAW-A"), List.of("P1"), true, 1, 1);

        assertTrue(service.findWorkflowsProducing("F1", "  ").getWorkflows().isEmpty());
        assertTrue(service.findWorkflowsProducing("F1", null).getWorkflows().isEmpty());
    }

    private List<Long> workflowIds(WorkflowOutputDirectoryDTO result) {
        return result.getWorkflows().stream()
                .map(WorkflowOutputDirectoryDTO.Entry::getWorkflowId).toList();
    }

    private void draftify(Long id) {
        ProductProcessWorkflow workflow = workflows.findByIdAndFactoryId(id, "F1").orElseThrow();
        workflow.setStatus(ProductProcessWorkflow.Status.DRAFT);
    }

    private void activate(
            Long id, String anchor, List<String> roots, List<String> terminals,
            boolean enabledFlag, int activationVersion, int workflowVersion) {
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(id);
        workflow.setFactoryId("F1");
        workflow.setProductTypeId(anchor);
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setDefinitionVersion(workflowVersion);
        workflow.setNodesJson(nodesJson(roots, terminals, null));
        workflow.setEdgesJson(edgesJson(roots.size(), terminals.size()));
        when(workflows.findByIdAndFactoryId(id, "F1")).thenReturn(Optional.of(workflow));

        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId("F1");
        activation.setProductTypeId(anchor);
        activation.setActiveWorkflowId(id);
        activation.setActiveDefinitionVersion(activationVersion);
        activation.setEnabled(enabledFlag);
        enabled.add(activation);
    }

    private void activateWithByproduct(Long id, String anchor, String main, String byproduct) {
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(id);
        workflow.setFactoryId("F1");
        workflow.setProductTypeId(anchor);
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setDefinitionVersion(1);
        workflow.setNodesJson(nodesJson(List.of("RAW-A"), List.of(main, byproduct), byproduct));
        workflow.setEdgesJson(edgesJson(1, 2));
        when(workflows.findByIdAndFactoryId(id, "F1")).thenReturn(Optional.of(workflow));

        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId("F1");
        activation.setProductTypeId(anchor);
        activation.setActiveWorkflowId(id);
        activation.setActiveDefinitionVersion(1);
        activation.setEnabled(true);
        enabled.add(activation);
    }

    private String nodesJson(List<String> roots, List<String> terminals, String byproductSku) {
        List<String> nodes = new ArrayList<>();
        int index = 0;
        for (String root : roots) {
            nodes.add("{\"id\":\"raw-" + index++ + "\",\"kind\":\"RAW_MATERIAL\","
                    + "\"position\":{\"x\":0,\"y\":0},\"data\":{\"skuId\":\"" + root
                    + "\",\"name\":\"" + root + "\",\"baseUnit\":\"kg\"}}");
        }
        index = 0;
        for (String terminal : terminals) {
            String byproductFlag = terminal.equals(byproductSku) ? ",\"isByproduct\":true" : "";
            nodes.add("{\"id\":\"fg-" + index++ + "\",\"kind\":\"FINISHED_GOOD\","
                    + "\"position\":{\"x\":400,\"y\":0},\"data\":{\"skuId\":\"" + terminal
                    + "\",\"name\":\"" + terminal + "\",\"baseUnit\":\"kg\"" + byproductFlag + "}}");
        }
        nodes.add("{\"id\":\"process\",\"kind\":\"PROCESS\",\"position\":{\"x\":200,\"y\":0},"
                + "\"data\":{\"processName\":\"原料处理\",\"inputUnit\":\"kg\",\"outputUnit\":\"kg\"}}");
        return "[" + String.join(",", nodes) + "]";
    }

    private String edgesJson(int rootCount, int terminalCount) {
        List<String> edges = new ArrayList<>();
        for (int index = 0; index < rootCount; index++) {
            edges.add("{\"id\":\"edge-in-" + index + "\",\"source\":\"raw-" + index
                    + "\",\"target\":\"process\"}");
        }
        for (int index = 0; index < terminalCount; index++) {
            edges.add("{\"id\":\"edge-out-" + index + "\",\"source\":\"process\",\"target\":\"fg-"
                    + index + "\"}");
        }
        return "[" + String.join(",", edges) + "]";
    }

    private ProductType product(String id) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId("F1");
        product.setName(id + " 名称");
        product.setUnit("kg");
        product.setProductCategory(ProductCategory.FINISHED_PRODUCT);
        return product;
    }
}
