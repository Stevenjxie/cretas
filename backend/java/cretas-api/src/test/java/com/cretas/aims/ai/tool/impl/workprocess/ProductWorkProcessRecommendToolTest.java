package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ProductWorkProcessRecommendTool published workflow gate")
class ProductWorkProcessRecommendToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String TARGET_ID = "target-product";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProductTypeRepository productTypeRepository;
    private ProductProcessWorkflowRepository workflowRepository;
    private WorkProcessRepository workProcessRepository;
    private ProductWorkProcessRecommendTool tool;

    @BeforeEach
    void setUp() {
        productTypeRepository = mock(ProductTypeRepository.class);
        workflowRepository = mock(ProductProcessWorkflowRepository.class);
        workProcessRepository = mock(WorkProcessRepository.class);
        tool = new ProductWorkProcessRecommendTool(
                productTypeRepository,
                workflowRepository,
                workProcessRepository,
                new ProductProcessWorkflowValidator(),
                objectMapper);
    }

    @Test
    @DisplayName("same-category complete published source returns provenance and topological process order")
    void recommendsOneCompletePublishedWorkflowWithProvenance() throws Exception {
        ProductType target = product(TARGET_ID, "干式熟成脆皮鸡 400g", "FINISHED_PRODUCT");
        ProductType source = product("source-a", "干式熟成脆皮鸡 350g", "FINISHED_PRODUCT");
        givenProducts(target, source);
        when(workflowRepository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, source.getId(), ProductProcessWorkflow.Status.PUBLISHED))
                .thenReturn(Optional.of(workflow(31L, source.getId(), 4, "CUT", "PACK")));
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("CUT", "PACK")))
                .thenReturn(List.of(process("CUT", "分切"), process("PACK", "包装")));

        ProductWorkProcessRecommendTool.RecommendationResult result =
                tool.recommend(FACTORY_ID, TARGET_ID, 1);

        assertEquals("PUBLISHED_WORKFLOW", result.source());
        assertEquals("PRODUCT_OWNED", result.sourceScope());
        assertEquals("COMPLETE_PUBLISHED_WORKFLOW", result.reasonCode());
        assertEquals("source-a", result.sourceProductTypeId());
        assertEquals("干式熟成脆皮鸡 350g", result.sourceProductName());
        assertEquals(31L, result.sourceWorkflowId());
        assertEquals(4, result.sourceWorkflowVersion());
        assertEquals(List.of("CUT", "PACK"), result.recommendations().stream()
                .map(ProductWorkProcessRecommendTool.RecommendedProcess::workProcessId)
                .toList());
        assertTrue(result.recommendations().stream()
                .allMatch(item -> "COPIED_FROM_SINGLE_PUBLISHED_WORKFLOW".equals(item.reason())));
    }

    @Test
    @DisplayName("two products are never aggregated; newest eligible workflow remains the single source")
    void doesNotMixProcessesAcrossProductsOrWorkflows() throws Exception {
        ProductType target = product(TARGET_ID, "干式熟成脆皮鸡 400g", "FINISHED_PRODUCT");
        ProductType sourceA = product("source-a", "干式熟成脆皮鸡 350g", "FINISHED_PRODUCT");
        ProductType sourceB = product("source-b", "干式熟成脆皮鸡 500g", "FINISHED_PRODUCT");
        givenProducts(target, sourceA, sourceB);
        when(workflowRepository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, sourceA.getId(), ProductProcessWorkflow.Status.PUBLISHED))
                .thenReturn(Optional.of(workflow(10L, sourceA.getId(), 3, "CUT", "COOK")));
        when(workflowRepository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, sourceB.getId(), ProductProcessWorkflow.Status.PUBLISHED))
                .thenReturn(Optional.of(workflow(20L, sourceB.getId(), 1, "PACK")));
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("PACK")))
                .thenReturn(List.of(process("PACK", "包装")));

        ProductWorkProcessRecommendTool.RecommendationResult result =
                tool.recommend(FACTORY_ID, TARGET_ID, 5);

        assertEquals("source-b", result.sourceProductTypeId());
        assertEquals(List.of("PACK"), result.recommendations().stream()
                .map(ProductWorkProcessRecommendTool.RecommendedProcess::workProcessId)
                .toList());
    }

    @Test
    @DisplayName("invalid or incomplete published workflow fails closed and legacy rows are not considered")
    void noCompleteSourceReturnsNoRecommendation() throws Exception {
        ProductType target = product(TARGET_ID, "干式熟成脆皮鸡 400g", "FINISHED_PRODUCT");
        ProductType source = product("source-a", "干式熟成脆皮鸡 350g", "FINISHED_PRODUCT");
        givenProducts(target, source);
        ProductProcessWorkflow invalid = workflow(11L, source.getId(), 1, "CUT");
        invalid.setEdgesJson("[]");
        when(workflowRepository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, source.getId(), ProductProcessWorkflow.Status.PUBLISHED))
                .thenReturn(Optional.of(invalid));

        ProductWorkProcessRecommendTool.RecommendationResult result =
                tool.recommend(FACTORY_ID, TARGET_ID, 5);

        assertEquals("NONE", result.source());
        assertEquals("PRODUCT_OWNED", result.sourceScope());
        assertEquals("NO_RELATED_COMPLETE_PUBLISHED_WORKFLOW", result.reasonCode());
        assertTrue(result.recommendations().isEmpty());
        assertNull(result.sourceWorkflowId());
        verify(workProcessRepository, never()).findByFactoryIdAndIdIn(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("cross-category products are not queried as workflow sources")
    void rejectsCrossCategorySource() {
        ProductType target = product(TARGET_ID, "新品", "FINISHED_PRODUCT");
        ProductType raw = product("raw-a", "原料", "RAW_MATERIAL");
        givenProducts(target, raw);

        ProductWorkProcessRecommendTool.RecommendationResult result =
                tool.recommend(FACTORY_ID, TARGET_ID, 5);

        assertTrue(result.recommendations().isEmpty());
        verify(workflowRepository, never())
                .findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                        FACTORY_ID, raw.getId(), ProductProcessWorkflow.Status.PUBLISHED);
    }

    @Test
    @DisplayName("same broad category but unrelated product family is never recommended")
    void rejectsUnrelatedProductInSameBroadCategory() {
        ProductType target = product(TARGET_ID, "干式熟成脆皮鸡 400g", "FINISHED_PRODUCT");
        ProductType unrelated = product("source-lamb", "SHH0713香辣孜然羊排", "FINISHED_PRODUCT");
        givenProducts(target, unrelated);

        ProductWorkProcessRecommendTool.RecommendationResult result =
                tool.recommend(FACTORY_ID, TARGET_ID, 5);

        assertEquals("NONE", result.source());
        assertEquals("NO_RELATED_COMPLETE_PUBLISHED_WORKFLOW", result.reasonCode());
        assertTrue(result.recommendations().isEmpty());
        verify(workflowRepository, never())
                .findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                        FACTORY_ID, unrelated.getId(), ProductProcessWorkflow.Status.PUBLISHED);
    }

    @Test
    @DisplayName("LLM cold-start dependency is removed")
    void doesNotDependOnLlmClient() {
        boolean hasLlmDependency = java.util.Arrays.stream(ProductWorkProcessRecommendTool.class.getDeclaredFields())
                .anyMatch(field -> PythonLLMClient.class.equals(field.getType()));
        assertFalse(hasLlmDependency);
    }

    private void givenProducts(ProductType target, ProductType... historical) {
        when(productTypeRepository.findByIdAndFactoryId(TARGET_ID, FACTORY_ID))
                .thenReturn(Optional.of(target));
        List<ProductType> products = new ArrayList<>();
        products.add(target);
        products.addAll(List.of(historical));
        when(productTypeRepository.findByFactoryId(FACTORY_ID)).thenReturn(products);
    }

    private ProductProcessWorkflow workflow(
            Long id,
            String productTypeId,
            int version,
            String... processIds) throws Exception {
        ProductProcessWorkflowDTO definition = workflowDefinition(productTypeId, processIds);
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(id);
        workflow.setFactoryId(FACTORY_ID);
        workflow.setProductTypeId(productTypeId);
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setSchemaVersion(1);
        workflow.setDefinitionVersion(version);
        workflow.setNodesJson(objectMapper.writeValueAsString(definition.getNodes()));
        workflow.setEdgesJson(objectMapper.writeValueAsString(definition.getEdges()));
        workflow.setViewportJson(objectMapper.writeValueAsString(definition.getViewport()));
        return workflow;
    }

    private ProductProcessWorkflowDTO workflowDefinition(String productTypeId, String... processIds) {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        List<ProductProcessWorkflowDTO.Node> nodes = new ArrayList<>();
        List<ProductProcessWorkflowDTO.Edge> edges = new ArrayList<>();
        nodes.add(material("raw", "RAW_MATERIAL", "RM-1"));
        String previousMaterial = "raw";
        for (int index = 0; index < processIds.length; index++) {
            String processNodeId = "process-" + index;
            String outputMaterial = index == processIds.length - 1 ? "finished" : "semi-" + index;
            String outputKind = index == processIds.length - 1 ? "FINISHED_GOOD" : "SEMI_FINISHED";
            String inputPort = "in-" + index;
            String outputPort = "out-" + index;
            nodes.add(process(
                    processNodeId,
                    processIds[index],
                    inputPort,
                    previousMaterial,
                    index == 0 ? "RAW_MATERIAL" : "SEMI_FINISHED",
                    outputPort,
                    outputMaterial,
                    outputKind));
            nodes.add(material(
                    outputMaterial,
                    outputKind,
                    index == processIds.length - 1 ? productTypeId : "SFI-" + index));
            edges.add(edge(
                    "edge-in-" + index,
                    previousMaterial,
                    "output",
                    processNodeId,
                    inputPort));
            edges.add(edge(
                    "edge-out-" + index,
                    processNodeId,
                    outputPort,
                    outputMaterial,
                    "input"));
            previousMaterial = outputMaterial;
        }
        definition.setNodes(nodes);
        definition.setEdges(edges);
        definition.setViewport(new ProductProcessWorkflowDTO.Viewport(0D, 0D, 1D));
        return definition;
    }

    private ProductProcessWorkflowDTO.Node material(String id, String kind, String skuId) {
        return new ProductProcessWorkflowDTO.Node(
                id,
                kind,
                new ProductProcessWorkflowDTO.Position(0D, 0D),
                new LinkedHashMap<>(Map.of("name", id, "skuId", skuId)));
    }

    private ProductProcessWorkflowDTO.Node process(
            String id,
            String workProcessId,
            String inputPort,
            String inputMaterial,
            String inputKind,
            String outputPort,
            String outputMaterial,
            String outputKind) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workProcessId", workProcessId);
        data.put("processName", workProcessId);
        data.put("ports", List.of(
                port(inputPort, "INPUT", inputMaterial, inputKind, 0),
                port(outputPort, "OUTPUT", outputMaterial, outputKind, 0)));
        return new ProductProcessWorkflowDTO.Node(
                id,
                "PROCESS",
                new ProductProcessWorkflowDTO.Position(0D, 0D),
                data);
    }

    private Map<String, Object> port(
            String id,
            String direction,
            String materialNodeId,
            String materialKind,
            int ordinal) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("id", id);
        port.put("direction", direction);
        port.put("materialNodeId", materialNodeId);
        port.put("materialKind", materialKind);
        port.put("unit", "kg");
        port.put("ordinal", ordinal);
        return port;
    }

    private ProductProcessWorkflowDTO.Edge edge(
            String id,
            String source,
            String sourceHandle,
            String target,
            String targetHandle) {
        return new ProductProcessWorkflowDTO.Edge(id, source, sourceHandle, target, targetHandle);
    }

    private ProductType product(String id, String name, String category) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setName(name);
        product.setProductCategory(category);
        product.setIsActive(true);
        return product;
    }

    private WorkProcess process(String id, String name) {
        WorkProcess process = new WorkProcess();
        process.setId(id);
        process.setProcessName(name);
        process.setProcessCategory("生产");
        process.setUnit("kg");
        process.setEstimatedMinutes(10);
        process.setIsActive(true);
        return process;
    }
}
