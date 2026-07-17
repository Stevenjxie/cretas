package com.cretas.aims.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowCatalogValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
    private com.cretas.aims.repository.ProductProcessWorkflowActivationRepository activationRepository;

    @Mock
    private ProductProcessWorkflowCatalogValidator catalogValidator;

    @Mock
    private ProductTypeRepository productTypeRepository;

    @Mock
    private com.cretas.aims.repository.RawMaterialTypeRepository rawMaterialTypeRepository;

    @Mock
    private ProductProcessWorkflowUnitValidator unitValidator;

    private ProductProcessWorkflowValidator validator;
    private ProductProcessWorkflowServiceImpl service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        validator = new ProductProcessWorkflowValidator();
        service = new ProductProcessWorkflowServiceImpl(
                repository, activationRepository, objectMapper, validator, catalogValidator, unitValidator,
                productTypeRepository, rawMaterialTypeRepository);
        ProductType owner = new ProductType();
        owner.setId(PRODUCT_ID);
        owner.setFactoryId(FACTORY_ID);
        lenient().when(productTypeRepository.findByIdAndFactoryId(PRODUCT_ID, FACTORY_ID))
                .thenReturn(Optional.of(owner));
        lenient().when(unitValidator.validate(any(), any()))
                .thenReturn(new com.cretas.aims.dto.workflow.WorkflowUnitValidationResult(List.of(), List.of()));
        lenient().when(repository.lockByIdAndFactoryId(1L, FACTORY_ID)).thenAnswer(invocation ->
                repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                        FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT));
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
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trimPorts = (List<Map<String, Object>>) definition.getNodes().get(2)
                .getData().get("ports");
        trimPorts.add(port("in-cycle", "INPUT", "finished", "FINISHED_GOOD", 2));
        definition.getEdges().add(edge("cycle", "finished", "out", "trim", "in-cycle"));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> validator.validateForPublish(definition));

        assertEquals(400, error.getCode());
        assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", error.getErrorCode());
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
        assertEquals(6, saved.getEdges().size());
        assertEquals("kg", saved.getNodes().get(2).getData().get("inputUnit"));
        assertEquals(ProductProcessWorkflow.Status.DRAFT.name(), saved.getStatus());
        verify(catalogValidator, never()).validateForPublish(any(), any(), any());
    }

    @Test
    @DisplayName("另存版本冻结当前草稿并递增可编辑草稿版本，不发布也不启用")
    void snapshotFreezesCurrentDraftAndAdvancesEditableDraft() throws Exception {
        ProductProcessWorkflow draft = persistedDraft(validDefinition(), 3L);
        draft.setDefinitionVersion(2);
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.of(draft));
        when(repository.saveAndFlush(any(ProductProcessWorkflow.class))).thenAnswer(invocation -> {
            ProductProcessWorkflow saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(92L);
            if (saved.getLockVersion() == null) saved.setLockVersion(0L);
            return saved;
        });

        ProductProcessWorkflowDTO nextDraft = service.snapshot(FACTORY_ID, PRODUCT_ID, 3L);

        ArgumentCaptor<ProductProcessWorkflow> rows = ArgumentCaptor.forClass(ProductProcessWorkflow.class);
        verify(repository, times(2)).saveAndFlush(rows.capture());
        ProductProcessWorkflow snapshot = rows.getAllValues().get(0);
        assertEquals(ProductProcessWorkflow.Status.SNAPSHOT, snapshot.getStatus());
        assertEquals(2, snapshot.getDefinitionVersion());
        assertEquals(draft.getNodesJson(), snapshot.getNodesJson());
        assertEquals(ProductProcessWorkflow.Status.DRAFT.name(), nextDraft.getStatus());
        assertEquals(3, nextDraft.getVersion());
        verify(catalogValidator, never()).validateForPublish(any(), any(), any());
    }

    @Test
    @DisplayName("草稿重建时从独立版本之后继续编号")
    void recreatedDraftAdvancesPastSnapshotVersion() {
        ProductProcessWorkflowDTO request = validDefinition();
        request.setLockVersion(null);
        ProductProcessWorkflow snapshot = entityFrom(request);
        snapshot.setStatus(ProductProcessWorkflow.Status.SNAPSHOT);
        snapshot.setDefinitionVersion(4);
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.empty());
        when(repository.findByFactoryIdAndProductTypeIdOrderByDefinitionVersionDesc(FACTORY_ID, PRODUCT_ID))
                .thenReturn(List.of(snapshot));
        when(repository.saveAndFlush(any(ProductProcessWorkflow.class))).thenAnswer(invocation -> {
            ProductProcessWorkflow saved = invocation.getArgument(0);
            saved.setId(93L);
            return saved;
        });

        ProductProcessWorkflowDTO saved = service.saveDraft(FACTORY_ID, PRODUCT_ID, request);

        assertEquals(5, saved.getVersion());
    }

    @Test
    @DisplayName("原料 owner 首次保存会创建带创建人的内部锚点")
    void rawOwnerAnchorInheritsCreatedBy() {
        String rawOwnerId = "RMT-RAW-1";
        ProductProcessWorkflowDTO request = validDefinition();
        request.setLockVersion(null);
        RawMaterialType rawOwner = new RawMaterialType();
        rawOwner.setId(rawOwnerId);
        rawOwner.setFactoryId(FACTORY_ID);
        rawOwner.setCode("0010030002000001");
        rawOwner.setUnit("只");
        rawOwner.setCreatedBy(77L);
        when(productTypeRepository.findByIdAndFactoryId(rawOwnerId, FACTORY_ID))
                .thenReturn(Optional.empty());
        when(rawMaterialTypeRepository.findById(rawOwnerId)).thenReturn(Optional.of(rawOwner));
        when(productTypeRepository.saveAndFlush(any(ProductType.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, rawOwnerId, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.empty());
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, rawOwnerId, ProductProcessWorkflow.Status.PUBLISHED))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ProductProcessWorkflow.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.saveDraft(FACTORY_ID, rawOwnerId, request);

        ArgumentCaptor<ProductType> anchor = ArgumentCaptor.forClass(ProductType.class);
        verify(productTypeRepository).saveAndFlush(anchor.capture());
        assertEquals(77L, anchor.getValue().getCreatedBy());
        assertEquals(rawOwnerId, anchor.getValue().getId());
        assertEquals("只", anchor.getValue().getUnit());
    }

    @Test
    @DisplayName("草稿保存通过真实服务入口拒绝所有不闭合的端口与连线语义")
    void saveDraftRejectsInvalidGraphSemanticsBeforePersistence() {
        for (InvalidGraphCase graphCase : invalidGraphCases()) {
            ProductProcessWorkflowDTO request = validDefinition();
            graphCase.mutate().accept(request);

            BusinessException error = assertThrows(
                    BusinessException.class,
                    () -> service.saveDraft(FACTORY_ID, PRODUCT_ID, request),
                    graphCase.name());

            assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", error.getErrorCode(), graphCase.name());
        }
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("草稿保存拒绝 processId/portId 分隔符碰撞造成的缺边")
    void saveDraftRejectsCollidingProcessAndPortIdentifiers() {
        lenient().when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.empty());
        lenient().when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.PUBLISHED))
                .thenReturn(Optional.empty());
        lenient().when(repository.saveAndFlush(any(ProductProcessWorkflow.class))).thenAnswer(invocation -> {
            ProductProcessWorkflow saved = invocation.getArgument(0);
            saved.setId(92L);
            saved.setLockVersion(0L);
            return saved;
        });

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.saveDraft(FACTORY_ID, PRODUCT_ID, collidingPortKeyDefinition()));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", error.getErrorCode());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("保存缺失或跨工厂 owner product 时稳定拒绝且不落库")
    void saveDraftRejectsInvalidOwningProductBeforePersistence() {
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT_ID, FACTORY_ID))
                .thenReturn(Optional.empty());

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.saveDraft(FACTORY_ID, PRODUCT_ID, validDefinition()));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_OWNER_INVALID", error.getErrorCode());
        assertTrue(error.getActionHint() != null && !error.getActionHint().isBlank());
        verify(repository, never()).saveAndFlush(any());
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
        assertTrue(error.getActionHint().contains("复制当前草稿 JSON"));
        assertFalse(error.getActionHint().contains("另存为新草稿"));
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
        assertFalse(published.getUnitReviewRequired());
        assertFalse(draft.getUnitReviewRequired());
        assertEquals(1, published.getVersion());
        assertEquals(4L, published.getLockVersion());
        verify(catalogValidator).validateForPublish(eq(FACTORY_ID), eq(PRODUCT_ID), any());
        verify(repository).saveAndFlush(draft);
    }

    @Test
    @DisplayName("发布通过真实服务入口拒绝所有不闭合语义且不改变草稿状态")
    void publishRejectsInvalidGraphSemanticsWithoutMutation() throws Exception {
        for (InvalidGraphCase graphCase : invalidGraphCases()) {
            ProductProcessWorkflowDTO definition = validDefinition();
            graphCase.mutate().accept(definition);
            ProductProcessWorkflow draft = persistedDraft(definition, 3L);
            when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                    FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                    .thenReturn(Optional.of(draft));

            BusinessException error = assertThrows(
                    BusinessException.class,
                    () -> service.publish(FACTORY_ID, PRODUCT_ID, 3L),
                    graphCase.name());

            assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", error.getErrorCode(), graphCase.name());
            assertEquals(ProductProcessWorkflow.Status.DRAFT, draft.getStatus(), graphCase.name());
        }
        verify(repository, never()).saveAndFlush(any());
        verify(catalogValidator, never()).validateForPublish(any(), any(), any());
    }

    @Test
    @DisplayName("发布拒绝 processId/portId 分隔符碰撞且不改变草稿状态")
    void publishRejectsCollidingProcessAndPortIdentifiersWithoutMutation() throws Exception {
        ProductProcessWorkflow draft = persistedDraft(collidingPortKeyDefinition(), 3L);
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.of(draft));
        lenient().when(repository.saveAndFlush(any(ProductProcessWorkflow.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.publish(FACTORY_ID, PRODUCT_ID, 3L));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", error.getErrorCode());
        assertEquals(ProductProcessWorkflow.Status.DRAFT, draft.getStatus());
        verify(repository, never()).saveAndFlush(any());
        verify(catalogValidator, never()).validateForPublish(any(), any(), any());
    }

    @Test
    @DisplayName("发布缺失或跨工厂 owner product 时稳定拒绝且不改变状态")
    void publishRejectsInvalidOwningProductBeforeDraftLookupOrMutation() {
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT_ID, FACTORY_ID))
                .thenReturn(Optional.empty());

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.publish(FACTORY_ID, PRODUCT_ID, 3L));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_OWNER_INVALID", error.getErrorCode());
        assertTrue(error.getActionHint() != null && !error.getActionHint().isBlank());
        verify(repository, never()).saveAndFlush(any());
        verify(catalogValidator, never()).validateForPublish(any(), any(), any());
    }

    @Test
    @DisplayName("目录校验失败时草稿状态不变且不保存")
    void publishCatalogFailureDoesNotMutateOrSaveDraft() throws Exception {
        ProductProcessWorkflowDTO definition = validDefinition();
        ProductProcessWorkflow draft = persistedDraft(definition, 3L);
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.of(draft));
        BusinessException mismatch = new BusinessException(400, "Cell mismatch")
                .withCode("PRODUCT_PROCESS_WORKFLOW_CATALOG_MISMATCH");
        doThrow(mismatch).when(catalogValidator)
                .validateForPublish(eq(FACTORY_ID), eq(PRODUCT_ID), any());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.publish(FACTORY_ID, PRODUCT_ID, 3L));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_CATALOG_MISMATCH", error.getErrorCode());
        assertEquals(ProductProcessWorkflow.Status.DRAFT, draft.getStatus());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("没有工序的原料直连成品图不能通过真实发布校验")
    void publishRejectsRawToFinishedWorkflowWithoutProcess() throws Exception {
        ProductProcessWorkflowDTO definition = rawToFinishedDefinition();
        ProductProcessWorkflow draft = persistedDraft(definition, 3L);
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.of(draft));
        WorkProcessRepository workProcessRepository = mock(WorkProcessRepository.class);
        ProductTypeRepository productTypeRepository = mock(ProductTypeRepository.class);
        ProductType owner = new ProductType();
        owner.setId(PRODUCT_ID);
        owner.setFactoryId(FACTORY_ID);
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT_ID, FACTORY_ID))
                .thenReturn(Optional.of(owner));
        ProductProcessWorkflowCatalogValidator realCatalogValidator =
                new ProductProcessWorkflowCatalogValidator(
                        workProcessRepository,
                        productTypeRepository,
                        mock(com.cretas.aims.repository.bom.BomItemRepository.class));
        ProductProcessWorkflowServiceImpl realService = new ProductProcessWorkflowServiceImpl(
                repository, activationRepository, new ObjectMapper(), validator, realCatalogValidator, unitValidator,
                productTypeRepository, mock(com.cretas.aims.repository.RawMaterialTypeRepository.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> realService.publish(FACTORY_ID, PRODUCT_ID, 3L));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", error.getErrorCode());
        assertEquals(ProductProcessWorkflow.Status.DRAFT, draft.getStatus());
        verify(repository, never()).saveAndFlush(any());
    }

    private ProductProcessWorkflowDTO validDefinition() {
        List<ProductProcessWorkflowDTO.Node> nodes = List.of(
                material("raw-a", "RAW_MATERIAL", "猪蹄 A 批", "RM-PIG-A"),
                material("raw-b", "RAW_MATERIAL", "猪蹄 B 批", "RM-PIG-B"),
                process("trim", "拆包 / 分切", List.of(
                        port("in-a", "INPUT", "raw-a", "RAW_MATERIAL", 0),
                        port("in-b", "INPUT", "raw-b", "RAW_MATERIAL", 1),
                        port("out-trim", "OUTPUT", "trimmed", "SEMI_FINISHED", 0))),
                material("trimmed", "SEMI_FINISHED", "修整猪蹄", "SFI-TRIMMED"),
                process("cook", "红烧熟制", List.of(
                        port("in-cook", "INPUT", "trimmed", "SEMI_FINISHED", 0),
                        port("out-good", "OUTPUT", "finished", "FINISHED_GOOD", 0),
                        port("out-loss", "OUTPUT", "loss", "SEMI_FINISHED", 1))),
                material("loss", "SEMI_FINISHED", "不合格品损耗", "SFI-LOSS"),
                material("finished", "FINISHED_GOOD", "红烧猪蹄 400g", "FG-BRAISED-400")
        );
        List<ProductProcessWorkflowDTO.Edge> edges = new ArrayList<>(List.of(
                edge("e1", "raw-a", "output", "trim", "in-a"),
                edge("e2", "raw-b", "output", "trim", "in-b"),
                edge("e3", "trim", "out-trim", "trimmed", "input"),
                edge("e4", "trimmed", "output", "cook", "in-cook"),
                edge("e5", "cook", "out-good", "finished", "input"),
                edge("e6", "cook", "out-loss", "loss", "input")
        ));
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setSchemaVersion(1);
        dto.setNodes(new ArrayList<>(nodes));
        dto.setEdges(edges);
        dto.setViewport(new ProductProcessWorkflowDTO.Viewport(0D, 0D, 1D));
        return dto;
    }

    private ProductProcessWorkflowDTO rawToFinishedDefinition() {
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setSchemaVersion(1);
        dto.setNodes(new ArrayList<>(List.of(
                material("raw-direct", "RAW_MATERIAL", "Raw Cell", "RM-1"),
                material("finished-direct", "FINISHED_GOOD", "Finished Cell", PRODUCT_ID))));
        dto.setEdges(new ArrayList<>(List.of(
                edge("direct", "raw-direct", "out", "finished-direct", "in"))));
        dto.setViewport(new ProductProcessWorkflowDTO.Viewport(0D, 0D, 1D));
        return dto;
    }

    private ProductProcessWorkflowDTO collidingPortKeyDefinition() {
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setNodes(new ArrayList<>(List.of(
                material("raw-1", "RAW_MATERIAL", "Raw 1", "RM-1"),
                material("raw-2", "RAW_MATERIAL", "Raw 2", "RM-2"),
                process("p", "Process p", List.of(
                        port("x::y", "INPUT", "raw-1", "RAW_MATERIAL", 0),
                        port("out-p", "OUTPUT", "finished-1", "FINISHED_GOOD", 0))),
                material("finished-1", "FINISHED_GOOD", "Finished 1", "FG-1"),
                process("p::x", "Process px", List.of(
                        port("y", "INPUT", "raw-2", "RAW_MATERIAL", 0),
                        port("other", "INPUT", "raw-1", "RAW_MATERIAL", 1),
                        port("out-px", "OUTPUT", "finished-2", "FINISHED_GOOD", 0))),
                material("finished-2", "FINISHED_GOOD", "Finished 2", "FG-2"))));
        dto.setEdges(new ArrayList<>(List.of(
                edge("p-output", "p", "out-p", "finished-1", "input"),
                edge("px-input", "raw-2", "output", "p::x", "y"),
                edge("px-other-input", "raw-1", "output", "p::x", "other"),
                edge("px-output", "p::x", "out-px", "finished-2", "input"))));
        dto.setViewport(new ProductProcessWorkflowDTO.Viewport(0D, 0D, 1D));
        return dto;
    }

    private ProductProcessWorkflow persistedDraft(
            ProductProcessWorkflowDTO definition,
            Long lockVersion) throws Exception {
        ProductProcessWorkflow draft = entityFrom(definition);
        ObjectMapper mapper = new ObjectMapper();
        draft.setNodesJson(mapper.writeValueAsString(definition.getNodes()));
        draft.setEdgesJson(mapper.writeValueAsString(definition.getEdges()));
        draft.setViewportJson(mapper.writeValueAsString(definition.getViewport()));
        draft.setLockVersion(lockVersion);
        return draft;
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
            List<Map<String, Object>> ports) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workProcessId", "WP-" + id);
        data.put("processName", name);
        data.put("inputUnit", "kg");
        data.put("outputUnit", "kg");
        data.put("ports", new ArrayList<>(ports));
        data.put("conversionRule", Map.of("mode", "ACTUAL_WEIGHT"));
        return new ProductProcessWorkflowDTO.Node(
                id, "PROCESS", new ProductProcessWorkflowDTO.Position(0D, 0D), data);
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

    private List<InvalidGraphCase> invalidGraphCases() {
        return List.of(
                new InvalidGraphCase("mismatched input materialNodeId", definition ->
                        port(definition, "trim", "in-a").put("materialNodeId", "raw-b")),
                new InvalidGraphCase("mismatched output materialNodeId", definition ->
                        port(definition, "trim", "out-trim").put("materialNodeId", "loss")),
                new InvalidGraphCase("duplicate input-handle edge", definition ->
                        definition.getEdges().add(edge(
                                "duplicate-input", "raw-b", "output", "trim", "in-a"))),
                new InvalidGraphCase("missing port id", definition ->
                        port(definition, "trim", "in-a").remove("id")),
                new InvalidGraphCase("duplicate port id", definition ->
                        port(definition, "trim", "in-b").put("id", "in-a")),
                new InvalidGraphCase("missing ordinal", definition ->
                        port(definition, "trim", "in-a").remove("ordinal")),
                new InvalidGraphCase("duplicate input ordinal", definition ->
                        port(definition, "trim", "in-b").put("ordinal", 0)),
                new InvalidGraphCase("duplicate output ordinal", definition ->
                        port(definition, "cook", "out-loss").put("ordinal", 0)),
                new InvalidGraphCase("ghost process edge", definition ->
                        definition.getEdges().add(edge(
                                "ghost", "raw-a", "output", "cook", "ghost-input"))),
                new InvalidGraphCase("wrong material handle", definition ->
                        edge(definition, "e1").setSourceHandle("input")),
                new InvalidGraphCase("wrong output material handle", definition ->
                        edge(definition, "e3").setTargetHandle("output")),
                new InvalidGraphCase("material-to-material edge", definition ->
                        definition.getEdges().add(edge(
                                "material-material", "raw-a", "output", "loss", "input"))),
                new InvalidGraphCase("process-to-process edge", definition ->
                        definition.getEdges().add(edge(
                                "process-process", "trim", "out-trim", "cook", "in-cook"))),
                new InvalidGraphCase("process self-loop", definition ->
                        definition.getEdges().add(edge(
                                "self-loop", "trim", "out-trim", "trim", "in-a"))),
                new InvalidGraphCase("materialKind mismatch", definition ->
                        port(definition, "trim", "in-a").put("materialKind", "FINISHED_GOOD")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> port(
            ProductProcessWorkflowDTO definition,
            String processId,
            String portId) {
        ProductProcessWorkflowDTO.Node process = definition.getNodes().stream()
                .filter(node -> processId.equals(node.getId()))
                .findFirst()
                .orElseThrow();
        return ((List<Map<String, Object>>) process.getData().get("ports")).stream()
                .filter(port -> portId.equals(port.get("id")))
                .findFirst()
                .orElseThrow();
    }

    private ProductProcessWorkflowDTO.Edge edge(
            ProductProcessWorkflowDTO definition,
            String edgeId) {
        return definition.getEdges().stream()
                .filter(edge -> edgeId.equals(edge.getId()))
                .findFirst()
                .orElseThrow();
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

    private record InvalidGraphCase(
            String name,
            Consumer<ProductProcessWorkflowDTO> mutate) {
    }
}
