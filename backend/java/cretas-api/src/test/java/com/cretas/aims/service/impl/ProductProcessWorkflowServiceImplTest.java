package com.cretas.aims.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowCatalogValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("产品工序 Workflow 服务")
@ExtendWith(MockitoExtension.class)
class ProductProcessWorkflowServiceImplTest {

    private static final String FACTORY_ID = "F001";
    private static final String PRODUCT_ID = "PT-PIG-TROTTER";

    @Mock
    private ProductProcessWorkflowRepository repository;

    @Mock
    private ProductProcessWorkflowCatalogValidator catalogValidator;

    private ProductProcessWorkflowValidator validator;
    private ProductProcessWorkflowServiceImpl service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        validator = new ProductProcessWorkflowValidator();
        service = new ProductProcessWorkflowServiceImpl(repository, objectMapper, validator, catalogValidator);
    }

    @Test
    @DisplayName("多投入、多产出的有向无环图可以发布")
    void validatePublishAcceptsTrueGraphTopology() {
        ProductProcessWorkflowDTO definition = validDefinition();

        validator.validateForPublish(definition);
    }

    @Test
    @DisplayName("发布时拒绝存在回路的流程")
    void validatePublishRejectsCycle() {
        ProductProcessWorkflowDTO definition = validDefinition();
        definition.getEdges().add(edge("cycle", "finished", "out", "raw-a", "in"));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> validator.validateForPublish(definition));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("不能形成回路"));
    }

    @Test
    @DisplayName("草稿保存后 JSON 可按原结构往返")
    void saveDraftSerializesAndReadsDefinition() {
        ProductProcessWorkflowDTO request = validDefinition();
        request.setLockVersion(null);
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.empty());
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.PUBLISHED))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ProductProcessWorkflow.class))).thenAnswer(invocation -> {
            ProductProcessWorkflow saved = invocation.getArgument(0);
            saved.setId(91L);
            saved.setLockVersion(0L);
            return saved;
        });

        ProductProcessWorkflowDTO saved = service.saveDraft(FACTORY_ID, PRODUCT_ID, request);

        ArgumentCaptor<ProductProcessWorkflow> captor = ArgumentCaptor.forClass(ProductProcessWorkflow.class);
        verify(repository).saveAndFlush(captor.capture());
        assertTrue(captor.getValue().getNodesJson().contains("红烧熟制"));
        assertEquals(7, saved.getNodes().size());
        assertEquals(7, saved.getEdges().size());
        assertEquals("kg", saved.getNodes().get(2).getData().get("inputUnit"));
        assertEquals(ProductProcessWorkflow.Status.DRAFT.name(), saved.getStatus());
        verify(catalogValidator, never()).validateForPublish(any(), any(), any());
    }

    @Test
    @DisplayName("旧 lockVersion 保存返回 409，不覆盖其他管理员草稿")
    void saveDraftRejectsStaleVersion() {
        ProductProcessWorkflow existing = entityFrom(validDefinition());
        existing.setLockVersion(5L);
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.of(existing));

        ProductProcessWorkflowDTO request = validDefinition();
        request.setLockVersion(4L);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.saveDraft(FACTORY_ID, PRODUCT_ID, request));

        assertEquals(409, error.getCode());
        assertEquals("PRODUCT_PROCESS_WORKFLOW_CONFLICT", error.getErrorCode());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("读取始终使用 factoryId + productTypeId 隔离")
    void getEditorDefinitionIsFactoryScoped() {
        ProductProcessWorkflow published = entityFrom(validDefinition());
        published.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                "F002", PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.empty());
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                "F002", PRODUCT_ID, ProductProcessWorkflow.Status.PUBLISHED))
                .thenReturn(Optional.of(published));

        Optional<ProductProcessWorkflowDTO> result = service.getEditorDefinition("F002", PRODUCT_ID);

        assertTrue(result.isPresent());
        verify(repository).findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                "F002", PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT);
        verify(repository).findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                "F002", PRODUCT_ID, ProductProcessWorkflow.Status.PUBLISHED);
    }

    @Test
    @DisplayName("发布通过完整校验并把草稿转为不可变版本")
    void publishValidDraft() throws Exception {
        ProductProcessWorkflowDTO definition = validDefinition();
        ProductProcessWorkflow draft = entityFrom(definition);
        draft.setNodesJson(new ObjectMapper().writeValueAsString(definition.getNodes()));
        draft.setEdgesJson(new ObjectMapper().writeValueAsString(definition.getEdges()));
        draft.setViewportJson(new ObjectMapper().writeValueAsString(definition.getViewport()));
        draft.setLockVersion(3L);
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.of(draft));
        when(repository.saveAndFlush(any(ProductProcessWorkflow.class))).thenAnswer(invocation -> {
            ProductProcessWorkflow saved = invocation.getArgument(0);
            saved.setLockVersion(4L);
            return saved;
        });

        ProductProcessWorkflowDTO published = service.publish(FACTORY_ID, PRODUCT_ID, 3L);

        assertEquals(ProductProcessWorkflow.Status.PUBLISHED.name(), published.getStatus());
        assertEquals(1, published.getVersion());
        assertEquals(4L, published.getLockVersion());
        verify(catalogValidator).validateForPublish(eq(FACTORY_ID), eq(PRODUCT_ID), any());
        verify(repository).saveAndFlush(draft);
    }

    private ProductProcessWorkflowDTO validDefinition() {
        List<ProductProcessWorkflowDTO.Node> nodes = List.of(
                material("raw-a", "RAW_MATERIAL", "猪蹄 A 批", "RM-PIG-A"),
                material("raw-b", "RAW_MATERIAL", "猪蹄 B 批", "RM-PIG-B"),
                process("trim", "拆包 / 分切", List.of("in-a", "in-b"), List.of("out-trim")),
                material("trimmed", "SEMI_FINISHED", "修整猪蹄", "SFI-TRIMMED"),
                process("cook", "红烧熟制", List.of("in-cook"), List.of("out-good", "out-loss")),
                material("loss", "SEMI_FINISHED", "不合格品损耗", "SFI-LOSS"),
                material("finished", "FINISHED_GOOD", "红烧猪蹄 400g", "FG-BRAISED-400")
        );
        List<ProductProcessWorkflowDTO.Edge> edges = new ArrayList<>(List.of(
                edge("e1", "raw-a", "out", "trim", "in-a"),
                edge("e2", "raw-b", "out", "trim", "in-b"),
                edge("e3", "trim", "out-trim", "trimmed", "in"),
                edge("e4", "trimmed", "out", "cook", "in-cook"),
                edge("e5", "cook", "out-good", "finished", "in"),
                edge("e6", "cook", "out-loss", "loss", "in"),
                edge("e7", "loss", "out", "finished", "loss-accounting")
        ));
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setSchemaVersion(1);
        dto.setNodes(new ArrayList<>(nodes));
        dto.setEdges(edges);
        dto.setViewport(new ProductProcessWorkflowDTO.Viewport(0D, 0D, 1D));
        return dto;
    }

    private ProductProcessWorkflowDTO.Node material(String id, String kind, String name, String skuId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("skuId", skuId);
        data.put("skuCode", skuId);
        return new ProductProcessWorkflowDTO.Node(
                id, kind, new ProductProcessWorkflowDTO.Position(0D, 0D), data);
    }

    private ProductProcessWorkflowDTO.Node process(
            String id,
            String name,
            List<String> inputHandles,
            List<String> outputHandles) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workProcessId", "WP-" + id);
        data.put("processName", name);
        data.put("inputUnit", "kg");
        data.put("outputUnit", "kg");
        List<Map<String, Object>> ports = new ArrayList<>();
        inputHandles.forEach(handle -> ports.add(port(handle, "INPUT", "kg")));
        outputHandles.forEach(handle -> ports.add(port(handle, "OUTPUT", "kg")));
        data.put("ports", ports);
        data.put("conversionRule", Map.of("mode", "ACTUAL_WEIGHT"));
        return new ProductProcessWorkflowDTO.Node(
                id, "PROCESS", new ProductProcessWorkflowDTO.Position(0D, 0D), data);
    }

    private Map<String, Object> port(String id, String direction, String unit) {
        return Map.of("id", id, "direction", direction, "unit", unit);
    }

    private ProductProcessWorkflowDTO.Edge edge(
            String id, String source, String sourceHandle, String target, String targetHandle) {
        return new ProductProcessWorkflowDTO.Edge(id, source, sourceHandle, target, targetHandle);
    }

    private ProductProcessWorkflow entityFrom(ProductProcessWorkflowDTO dto) {
        ProductProcessWorkflow entity = new ProductProcessWorkflow();
        entity.setId(1L);
        entity.setFactoryId(FACTORY_ID);
        entity.setProductTypeId(PRODUCT_ID);
        entity.setSchemaVersion(1);
        entity.setStatus(ProductProcessWorkflow.Status.DRAFT);
        entity.setDefinitionVersion(1);
        entity.setNodesJson("[]");
        entity.setEdgesJson("[]");
        entity.setViewportJson("{\"x\":0,\"y\":0,\"zoom\":1}");
        return entity;
    }
}
