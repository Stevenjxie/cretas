package com.cretas.aims.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.workflow.ProductProcessWorkflowActivationDTO;
import com.cretas.aims.dto.workflow.WorkflowBomSyncPreflightResponse;
import com.cretas.aims.dto.workflow.WorkflowPublishAndActivateResponse;
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
import org.mockito.InOrder;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Mock
    private com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository revisionRepository;

    @Mock
    private com.cretas.aims.service.workflow.WorkflowRevisionSnapshotService revisionSnapshotService;

    @Mock
    private com.cretas.aims.service.bom.BomWorkflowRevisionService bomWorkflowRevisionService;

    @Mock
    private com.cretas.aims.service.bom.WorkflowBomSynchronizationService workflowBomSynchronizationService;

    @Mock
    private com.cretas.aims.service.workflow.ProductProcessWorkflowActivationService workflowActivationService;

    private ProductProcessWorkflowValidator validator;
    private ProductProcessWorkflowServiceImpl service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        validator = new ProductProcessWorkflowValidator();
        service = new ProductProcessWorkflowServiceImpl(
                repository, revisionRepository, revisionSnapshotService, bomWorkflowRevisionService,
                workflowBomSynchronizationService, workflowActivationService,
                activationRepository,
                objectMapper, validator, catalogValidator, unitValidator,
                productTypeRepository, rawMaterialTypeRepository);
        com.cretas.aims.entity.ProductProcessWorkflowRevision revision =
                new com.cretas.aims.entity.ProductProcessWorkflowRevision();
        revision.setId(91L);
        revision.setFactoryId(FACTORY_ID);
        revision.setProductTypeId(PRODUCT_ID);
        revision.setWorkflowId(1L);
        revision.setRevisionHash("test-revision");
        revision.setDefinitionVersion(1);
        revision.setStructurallyComplete(true);
        lenient().when(revisionSnapshotService.capture(any())).thenReturn(revision);
        lenient().when(revisionSnapshotService.captureDraft(any())).thenReturn(revision);
        lenient().when(revisionRepository.findByIdAndFactoryId(91L, FACTORY_ID))
                .thenReturn(Optional.of(revision));
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
        request.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind()))
                .findFirst()
                .ifPresent(node -> {
                    node.getData().put("portGroups", List.of(Map.of("id", "legacy")));
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> ports =
                            (List<Map<String, Object>>) node.getData().get("ports");
                    ports.stream()
                            .filter(port -> "OUTPUT".equals(port.get("direction")))
                            .forEach(port -> {
                                port.put("outputRole", "MAIN");
                                port.put("costAllocationRatio", 100);
                            });
                });
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ProductProcessWorkflow.class))).thenAnswer(invocation -> {
            ProductProcessWorkflow saved = invocation.getArgument(0);
            saved.setId(91L);
            saved.setLockVersion(0L);
            return saved;
        });

        ProductProcessWorkflowDTO saved = service.saveDraft(FACTORY_ID, PRODUCT_ID, request);

        ArgumentCaptor<ProductProcessWorkflow> captor = ArgumentCaptor.forClass(ProductProcessWorkflow.class);
        verify(repository, times(2)).saveAndFlush(captor.capture());
        assertTrue(captor.getAllValues().get(0).getNodesJson().contains("红烧熟制"));
        assertEquals(7, saved.getNodes().size());
        assertEquals(6, saved.getEdges().size());
        assertEquals("kg", saved.getNodes().get(2).getData().get("inputUnit"));
        assertEquals(ProductProcessWorkflow.Status.DRAFT.name(), saved.getStatus());
        String savedNodes = captor.getAllValues().get(0).getNodesJson();
        assertTrue(savedNodes.contains("\"reportingSelectionMode\":\"ACTUAL_IO\""));
        assertFalse(savedNodes.contains("\"portGroups\""));
        assertFalse(savedNodes.contains("\"outputRole\""));
        assertFalse(savedNodes.contains("\"costAllocationRatio\""));
        verify(revisionSnapshotService).captureDraft(any(ProductProcessWorkflow.class));
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
        verify(repository, times(3)).saveAndFlush(rows.capture());
        ProductProcessWorkflow snapshot = rows.getAllValues().get(0);
        assertEquals(ProductProcessWorkflow.Status.SNAPSHOT, snapshot.getStatus());
        assertEquals(2, snapshot.getDefinitionVersion());
        assertEquals(draft.getNodesJson(), snapshot.getNodesJson());
        assertEquals(ProductProcessWorkflow.Status.DRAFT.name(), nextDraft.getStatus());
        assertEquals(3, nextDraft.getVersion());
        assertNull(nextDraft.getRevisionId());
        assertNull(nextDraft.getRevisionHash());
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
        when(repository.findMaxDefinitionVersion(FACTORY_ID, PRODUCT_ID)).thenReturn(Optional.of(4));
        when(repository.saveAndFlush(any(ProductProcessWorkflow.class))).thenAnswer(invocation -> {
            ProductProcessWorkflow saved = invocation.getArgument(0);
            saved.setId(93L);
            return saved;
        });

        ProductProcessWorkflowDTO saved = service.saveDraft(FACTORY_ID, PRODUCT_ID, request);

        assertEquals(5, saved.getVersion());
    }

    @Test
    @DisplayName("发布后的迟到同图自动保存返回已发布版本且不创建新草稿")
    void delayedIdenticalAutosaveAfterPublishIsNoOp() {
        ProductProcessWorkflowDTO request = validDefinition();
        request.setLockVersion(3L);
        ProductProcessWorkflow published = entityFrom(request);
        published.setId(91L);
        published.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        published.setDefinitionVersion(1);
        published.setLockVersion(4L);
        pinCurrentRevision(published);
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.empty());
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.PUBLISHED))
                .thenReturn(Optional.of(published));
        when(revisionSnapshotService.hash(any(ProductProcessWorkflow.class))).thenReturn("same-graph");

        ProductProcessWorkflowDTO saved = service.saveDraft(FACTORY_ID, PRODUCT_ID, request);

        assertEquals(ProductProcessWorkflow.Status.PUBLISHED.name(), saved.getStatus());
        assertEquals(1, saved.getVersion());
        assertEquals(91L, saved.getId());
        verify(repository, never()).findMaxDefinitionVersion(FACTORY_ID, PRODUCT_ID);
        verify(repository, never()).saveAndFlush(any(ProductProcessWorkflow.class));
        verify(revisionSnapshotService, never()).captureDraft(any(ProductProcessWorkflow.class));
    }

    @Test
    @DisplayName("发布后真实画布修改仍创建下一版草稿")
    void changedAutosaveAfterPublishCreatesNextDraft() {
        ProductProcessWorkflowDTO request = validDefinition();
        request.setLockVersion(3L);
        request.getNodes().get(0).getPosition().setX(99D);
        ProductProcessWorkflow published = entityFrom(validDefinition());
        published.setId(91L);
        published.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        published.setDefinitionVersion(1);
        published.setLockVersion(4L);
        pinCurrentRevision(published);
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.empty());
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.PUBLISHED))
                .thenReturn(Optional.of(published));
        when(revisionSnapshotService.hash(any(ProductProcessWorkflow.class)))
                .thenAnswer(invocation -> invocation.getArgument(0) == published
                        ? "published-graph" : "changed-graph");
        when(repository.findMaxDefinitionVersion(FACTORY_ID, PRODUCT_ID)).thenReturn(Optional.of(1));
        when(repository.saveAndFlush(any(ProductProcessWorkflow.class))).thenAnswer(invocation -> {
            ProductProcessWorkflow saved = invocation.getArgument(0);
            saved.setId(92L);
            saved.setLockVersion(0L);
            return saved;
        });

        ProductProcessWorkflowDTO saved = service.saveDraft(FACTORY_ID, PRODUCT_ID, request);

        assertEquals(ProductProcessWorkflow.Status.DRAFT.name(), saved.getStatus());
        assertEquals(2, saved.getVersion());
        assertEquals(92L, saved.getId());
        verify(revisionSnapshotService).captureDraft(any(ProductProcessWorkflow.class));
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

        ProductProcessWorkflowDTO published = service.publish(
                FACTORY_ID, PRODUCT_ID, 3L, 77L);

        assertEquals(ProductProcessWorkflow.Status.PUBLISHED.name(), published.getStatus());
        assertFalse(published.getUnitReviewRequired());
        assertFalse(draft.getUnitReviewRequired());
        assertEquals(1, published.getVersion());
        assertEquals(4L, published.getLockVersion());
        assertEquals("legacy:91:test-revision", draft.getLastPublishIdempotencyKey());
        assertEquals(91L, draft.getLastPublishRevisionId());
        assertEquals("test-revision", draft.getLastPublishRevisionHash());
        assertEquals(1, draft.getLastPublishDefinitionVersion());
        verify(catalogValidator).validateForPublish(eq(FACTORY_ID), eq(PRODUCT_ID), any());
        verify(revisionSnapshotService).captureDraft(draft);
        verify(bomWorkflowRevisionService).requireActiveBomPinsRevision(
                eq(FACTORY_ID), eq(PRODUCT_ID), any());
        InOrder order = inOrder(
                repository, workflowBomSynchronizationService, workflowActivationService);
        order.verify(repository).lockByFactoryId(FACTORY_ID);
        order.verify(repository).lockByIdAndFactoryId(draft.getId(), FACTORY_ID);
        order.verify(workflowBomSynchronizationService)
                .synchronizeForPublish(eq(FACTORY_ID), eq(PRODUCT_ID), any(), eq(77L));
        order.verify(repository).saveAndFlush(draft);
        order.verify(workflowActivationService).activate(FACTORY_ID, draft.getId(), 77L);
        verify(repository).saveAndFlush(draft);
    }

    @Test
    @DisplayName("旧发布入口激活失败时异常外抛且不会绕过原子协调顺序")
    void legacyPublishPropagatesActivationFailureAfterBomSyncAndPublish() throws Exception {
        ProductProcessWorkflow draft = persistedDraft(validDefinition(), 3L);
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.of(draft));
        when(repository.saveAndFlush(any(ProductProcessWorkflow.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        BusinessException activationFailure = new BusinessException(409, "activation failed")
                .withCode("WORKFLOW_ACTIVATION_CONFLICT");
        doThrow(activationFailure).when(workflowActivationService)
                .activate(FACTORY_ID, draft.getId(), 77L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.publish(FACTORY_ID, PRODUCT_ID, 3L, 77L));

        assertEquals("WORKFLOW_ACTIVATION_CONFLICT", error.getErrorCode());
        InOrder order = inOrder(
                workflowBomSynchronizationService, repository, workflowActivationService);
        order.verify(workflowBomSynchronizationService)
                .synchronizeForPublish(eq(FACTORY_ID), eq(PRODUCT_ID), any(), eq(77L));
        order.verify(repository).saveAndFlush(draft);
        order.verify(workflowActivationService).activate(FACTORY_ID, draft.getId(), 77L);
    }

    @Test
    @DisplayName("BOM 同步预检先修复当前坏草稿修订，再以新修订计算映射")
    void bomSyncPreflightRepairsCurrentDraftRevisionBeforeMapping() throws Exception {
        ProductProcessWorkflow draft = persistedDraft(validDefinition(), 3L);
        pinCurrentRevision(draft);
        stubDraft(draft);
        var current = currentRevision();
        var repaired = new com.cretas.aims.entity.ProductProcessWorkflowRevision();
        repaired.setId(92L);
        repaired.setFactoryId(FACTORY_ID);
        repaired.setProductTypeId(PRODUCT_ID);
        repaired.setWorkflowId(draft.getId());
        repaired.setRevisionHash("roundtrip-stable");
        repaired.setDefinitionVersion(1);
        repaired.setStructurallyComplete(true);
        WorkflowBomSyncPreflightResponse preflight = automaticPreflight();
        when(bomWorkflowRevisionService.repairCurrentDraftRevisionIfNeeded(
                FACTORY_ID, current)).thenReturn(repaired);
        when(workflowBomSynchronizationService.preflight(
                FACTORY_ID, PRODUCT_ID, repaired)).thenReturn(preflight);

        WorkflowBomSyncPreflightResponse result =
                service.bomSyncPreflight(FACTORY_ID, PRODUCT_ID);

        assertEquals(preflight, result);
        verify(bomWorkflowRevisionService)
                .repairCurrentDraftRevisionIfNeeded(FACTORY_ID, current);
        verify(workflowBomSynchronizationService)
                .preflight(FACTORY_ID, PRODUCT_ID, repaired);
    }

    @Test
    @DisplayName("自动同步、发布、启用按顺序完成并返回完整结果")
    void publishAndActivateCompletesAtomicHappyPath() throws Exception {
        ProductProcessWorkflow draft = persistedDraft(validDefinition(), 3L);
        pinCurrentRevision(draft);
        stubDraft(draft);
        WorkflowBomSyncPreflightResponse preflight = automaticPreflight();
        when(workflowBomSynchronizationService.preflight(
                FACTORY_ID, PRODUCT_ID, currentRevision())).thenReturn(preflight);
        when(repository.saveAndFlush(any(ProductProcessWorkflow.class)))
                .thenAnswer(invocation -> {
                    ProductProcessWorkflow saved = invocation.getArgument(0);
                    saved.setLockVersion(4L);
                    return saved;
                });
        ProductProcessWorkflowActivationDTO activation = activation(draft);
        when(workflowActivationService.activate(FACTORY_ID, draft.getId(), 77L))
                .thenReturn(activation);

        WorkflowPublishAndActivateResponse result = service.publishAndActivate(
                FACTORY_ID, PRODUCT_ID, 3L, "request-1",
                91L, "test-revision", 1, 77L);

        assertNotNull(result.getWorkflow());
        assertEquals(ProductProcessWorkflow.Status.PUBLISHED.name(), result.getWorkflow().getStatus());
        assertEquals(activation, result.getActivation());
        assertEquals(preflight, result.getBomSync());
        assertFalse(result.isReplayed());
        assertEquals("request-1", draft.getLastPublishIdempotencyKey());
        assertEquals(91L, draft.getLastPublishRevisionId());
        assertEquals("test-revision", draft.getLastPublishRevisionHash());
        assertEquals(1, draft.getLastPublishDefinitionVersion());
        InOrder order = inOrder(
                workflowBomSynchronizationService, repository, workflowActivationService);
        order.verify(workflowBomSynchronizationService)
                .synchronizeForPublish(FACTORY_ID, PRODUCT_ID, currentRevision(), 77L);
        order.verify(repository).saveAndFlush(draft);
        order.verify(workflowActivationService).activate(FACTORY_ID, draft.getId(), 77L);
    }

    @Test
    @DisplayName("预检缺少用户输入时发布启用返回 409 且零 mutation")
    void publishAndActivateRejectsUserInputRequiredWithoutMutation() throws Exception {
        assertBlockedPreflightHasNoMutation(
                WorkflowBomSyncPreflightResponse.Classification.USER_INPUT_REQUIRED,
                false);
    }

    @Test
    @DisplayName("预检存在冲突时发布启用返回 409 且零 mutation")
    void publishAndActivateRejectsConflictWithoutMutation() throws Exception {
        assertBlockedPreflightHasNoMutation(
                WorkflowBomSyncPreflightResponse.Classification.CONFLICT,
                true);
    }

    @Test
    @DisplayName("无草稿时仅在 published revision、BOM 与 activation 精确一致时重放")
    void publishAndActivateReplaysOnlyExactPublishedState() throws Exception {
        ProductProcessWorkflow published = persistedDraft(validDefinition(), 4L);
        published.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        pinCurrentRevision(published);
        bindPublishIdentity(published, "request-replay");
        when(repository.findByFactoryIdAndLastPublishIdempotencyKey(
                FACTORY_ID, "request-replay")).thenReturn(Optional.of(published));
        WorkflowBomSyncPreflightResponse preflight = readyPreflight();
        when(workflowBomSynchronizationService.preflight(
                FACTORY_ID, PRODUCT_ID, currentRevision())).thenReturn(preflight);
        ProductProcessWorkflowActivationDTO activation = activation(published);
        when(workflowActivationService.get(FACTORY_ID, PRODUCT_ID)).thenReturn(activation);

        WorkflowPublishAndActivateResponse result = service.publishAndActivate(
                FACTORY_ID, PRODUCT_ID, 3L, "request-replay",
                91L, "test-revision", 1, 77L);

        assertTrue(result.isReplayed());
        assertEquals(published.getId(), result.getWorkflow().getId());
        assertEquals(activation, result.getActivation());
        verify(workflowActivationService, never()).activate(any(), any(), any());
        verify(workflowBomSynchronizationService, never())
                .synchronizeForPublish(any(), any(), any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("持久幂等身份匹配但 activation 不匹配时拒绝伪重放")
    void publishAndActivateRejectsUnprovenReplay() throws Exception {
        ProductProcessWorkflow published = persistedDraft(validDefinition(), 6L);
        published.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        pinCurrentRevision(published);
        bindPublishIdentity(published, "request-not-replay");
        when(repository.findByFactoryIdAndLastPublishIdempotencyKey(
                FACTORY_ID, "request-not-replay")).thenReturn(Optional.of(published));
        when(workflowBomSynchronizationService.preflight(
                FACTORY_ID, PRODUCT_ID, currentRevision())).thenReturn(readyPreflight());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.publishAndActivate(
                        FACTORY_ID, PRODUCT_ID, 3L, "request-not-replay",
                        91L, "test-revision", 1, 77L));

        assertEquals(409, error.getCode());
        assertEquals("WORKFLOW_PUBLISH_REPLAY_CONFLICT", error.getErrorCode());
        verify(workflowActivationService, never()).activate(any(), any(), any());
        verify(workflowActivationService).get(FACTORY_ID, PRODUCT_ID);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("同一幂等键绑定不同 revision 身份时返回 409 且零 mutation")
    void publishAndActivateRejectsSameKeyWithDifferentIdentity() throws Exception {
        ProductProcessWorkflow published = persistedDraft(validDefinition(), 4L);
        published.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        pinCurrentRevision(published);
        bindPublishIdentity(published, "request-bound");
        when(repository.findByFactoryIdAndLastPublishIdempotencyKey(
                FACTORY_ID, "request-bound")).thenReturn(Optional.of(published));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.publishAndActivate(
                        FACTORY_ID, PRODUCT_ID, 3L, "request-bound",
                        90L, "different-revision", 1, 77L));

        assertEquals(409, error.getCode());
        assertEquals("WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_CONFLICT", error.getErrorCode());
        verifyNoInteractions(workflowBomSynchronizationService);
        verifyNoInteractions(workflowActivationService);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("同一已完成 revision 使用不同幂等键时返回 409")
    void publishAndActivateRejectsDifferentKeyForCompletedIdentity() throws Exception {
        ProductProcessWorkflow published = persistedDraft(validDefinition(), 4L);
        published.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        pinCurrentRevision(published);
        bindPublishIdentity(published, "original-request");
        when(repository.findByFactoryIdAndLastPublishIdempotencyKey(
                FACTORY_ID, "new-request")).thenReturn(Optional.empty());
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.empty());
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.PUBLISHED))
                .thenReturn(Optional.of(published));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.publishAndActivate(
                        FACTORY_ID, PRODUCT_ID, 3L, "new-request",
                        91L, "test-revision", 1, 77L));

        assertEquals(409, error.getCode());
        assertEquals("WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_MISMATCH", error.getErrorCode());
        verifyNoInteractions(workflowBomSynchronizationService);
        verifyNoInteractions(workflowActivationService);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("旧 revision 身份即使 lockVersion 相同也不能误判为重放")
    void publishAndActivateRejectsStaleRevisionIdentityBeforeReplay() throws Exception {
        ProductProcessWorkflow published = persistedDraft(validDefinition(), 4L);
        published.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        pinCurrentRevision(published);
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.empty());
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.PUBLISHED))
                .thenReturn(Optional.of(published));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.publishAndActivate(
                        FACTORY_ID, PRODUCT_ID, 3L, "stale-request-key",
                        90L, "old-revision", 1, 77L));

        assertEquals(409, error.getCode());
        assertEquals("WORKFLOW_PUBLISH_REVISION_IDENTITY_CONFLICT", error.getErrorCode());
        verifyNoInteractions(workflowBomSynchronizationService);
        verifyNoInteractions(workflowActivationService);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Workflow 激活失败向外抛出且发生在 BOM 同步和发布保存之后")
    void publishAndActivatePropagatesActivationFailureAfterPublishSteps() throws Exception {
        ProductProcessWorkflow draft = persistedDraft(validDefinition(), 3L);
        pinCurrentRevision(draft);
        stubDraft(draft);
        when(workflowBomSynchronizationService.preflight(
                FACTORY_ID, PRODUCT_ID, currentRevision())).thenReturn(automaticPreflight());
        when(repository.saveAndFlush(any(ProductProcessWorkflow.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        BusinessException activationFailure = new BusinessException(409, "activation failed")
                .withCode("WORKFLOW_ACTIVATION_CONFLICT");
        doThrow(activationFailure).when(workflowActivationService)
                .activate(FACTORY_ID, draft.getId(), 77L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.publishAndActivate(
                        FACTORY_ID, PRODUCT_ID, 3L, "request-fail",
                        91L, "test-revision", 1, 77L));

        assertEquals("WORKFLOW_ACTIVATION_CONFLICT", error.getErrorCode());
        InOrder order = inOrder(
                workflowBomSynchronizationService, repository, workflowActivationService);
        order.verify(workflowBomSynchronizationService)
                .synchronizeForPublish(FACTORY_ID, PRODUCT_ID, currentRevision(), 77L);
        order.verify(repository).saveAndFlush(draft);
        order.verify(workflowActivationService).activate(FACTORY_ID, draft.getId(), 77L);
    }

    @Test
    @DisplayName("已发布且没有新草稿时再次发布明确返回 409，且不产生任何写入")
    void publishWithoutDraftIsRejectedWithoutMutation() {
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.publish(FACTORY_ID, PRODUCT_ID, 3L));

        assertEquals(409, error.getCode());
        assertEquals("PRODUCT_PROCESS_WORKFLOW_DRAFT_MISSING", error.getErrorCode());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(bomWorkflowRevisionService);
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
                        mock(com.cretas.aims.repository.bom.BomRecipeRepository.class),
                        mock(com.cretas.aims.repository.bom.BomRecipeItemRepository.class));
        ProductProcessWorkflowServiceImpl realService = new ProductProcessWorkflowServiceImpl(
                repository, revisionRepository, revisionSnapshotService, bomWorkflowRevisionService,
                workflowBomSynchronizationService, workflowActivationService,
                activationRepository,
                new ObjectMapper(), validator, realCatalogValidator, unitValidator,
                productTypeRepository, mock(com.cretas.aims.repository.RawMaterialTypeRepository.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> realService.publish(FACTORY_ID, PRODUCT_ID, 3L));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", error.getErrorCode());
        assertEquals(ProductProcessWorkflow.Status.DRAFT, draft.getStatus());
        verify(repository, never()).saveAndFlush(any());
    }

    private void assertBlockedPreflightHasNoMutation(
            WorkflowBomSyncPreflightResponse.Classification classification,
            boolean conflict) throws Exception {
        ProductProcessWorkflow draft = persistedDraft(validDefinition(), 3L);
        pinCurrentRevision(draft);
        stubDraft(draft);
        WorkflowBomSyncPreflightResponse.SyncIssue issue =
                WorkflowBomSyncPreflightResponse.SyncIssue.builder()
                        .code(conflict ? "BOM_WORKFLOW_UPGRADE_UNIT_INCOMPATIBLE"
                                : "BOM_WORKFLOW_INPUT_ITEM_MISSING")
                        .field(conflict ? "unit" : "materialTypeId")
                        .message(conflict ? "单位冲突" : "缺少原料")
                        .action(conflict ? "统一计量单位" : "补充原料配置")
                        .build();
        WorkflowBomSyncPreflightResponse preflight =
                WorkflowBomSyncPreflightResponse.builder()
                        .classification(classification)
                        .conflicts(conflict ? List.of(issue) : List.of())
                        .missingItems(conflict ? List.of() : List.of(issue))
                        .canCompleteAutomatically(false)
                        .build();
        when(workflowBomSynchronizationService.preflight(
                FACTORY_ID, PRODUCT_ID, currentRevision())).thenReturn(preflight);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.publishAndActivate(
                        FACTORY_ID, PRODUCT_ID, 3L, "request-blocked",
                        91L, "test-revision", 1, 77L));

        assertEquals(409, error.getCode());
        assertEquals("WORKFLOW_BOM_SYNC_" + classification.name(), error.getErrorCode());
        verify(workflowBomSynchronizationService, never())
                .synchronizeForPublish(any(), any(), any(), any());
        verifyNoInteractions(workflowActivationService);
        verify(repository, never()).saveAndFlush(any());
    }

    private void stubDraft(ProductProcessWorkflow draft) {
        when(repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY_ID, PRODUCT_ID, ProductProcessWorkflow.Status.DRAFT))
                .thenReturn(Optional.of(draft));
    }

    private void pinCurrentRevision(ProductProcessWorkflow workflow) {
        workflow.setCurrentRevisionId(91L);
        workflow.setCurrentRevisionHash("test-revision");
    }

    private void bindPublishIdentity(
            ProductProcessWorkflow workflow,
            String idempotencyKey) {
        workflow.setLastPublishIdempotencyKey(idempotencyKey);
        workflow.setLastPublishRevisionId(91L);
        workflow.setLastPublishRevisionHash("test-revision");
        workflow.setLastPublishDefinitionVersion(1);
    }

    private com.cretas.aims.entity.ProductProcessWorkflowRevision currentRevision() {
        return revisionRepository.findByIdAndFactoryId(91L, FACTORY_ID).orElseThrow();
    }

    private WorkflowBomSyncPreflightResponse automaticPreflight() {
        return WorkflowBomSyncPreflightResponse.builder()
                .classification(WorkflowBomSyncPreflightResponse.Classification.AUTO_MIGRATABLE)
                .canCompleteAutomatically(true)
                .build();
    }

    private WorkflowBomSyncPreflightResponse readyPreflight() {
        return WorkflowBomSyncPreflightResponse.builder()
                .classification(WorkflowBomSyncPreflightResponse.Classification.READY)
                .canCompleteAutomatically(true)
                .build();
    }

    private ProductProcessWorkflowActivationDTO activation(ProductProcessWorkflow workflow) {
        ProductProcessWorkflowActivationDTO dto = new ProductProcessWorkflowActivationDTO();
        dto.setFactoryId(FACTORY_ID);
        dto.setProductTypeId(PRODUCT_ID);
        dto.setActiveWorkflowId(workflow.getId());
        dto.setActiveDefinitionVersion(workflow.getDefinitionVersion());
        dto.setEnabled(true);
        dto.setLockVersion(0L);
        return dto;
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
        data.put("reportingSelectionMode", "ACTUAL_IO");
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
