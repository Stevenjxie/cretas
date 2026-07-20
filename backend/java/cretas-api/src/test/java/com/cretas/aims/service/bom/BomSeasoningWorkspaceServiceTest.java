package com.cretas.aims.service.bom;

import com.cretas.aims.dto.bom.BomSeasoningWorkspaceResponse;
import com.cretas.aims.dto.bom.SeasoningBindingCreateRequest;
import com.cretas.aims.dto.workflow.WorkflowRevisionCandidateDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.bom.impl.BomSeasoningWorkspaceServiceImpl;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import com.cretas.aims.service.workflow.WorkflowProcessPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BomSeasoningWorkspaceServiceTest {
    private static final String FACTORY = "F006";
    private static final String RECIPE = "recipe-1";

    @Mock BomRecipeRepository recipeRepository;
    @Mock BomSeasoningItemRepository seasoningItemRepository;
    @Mock ProductWorkProcessRepository productWorkProcessRepository;
    @Mock WorkProcessRepository workProcessRepository;
    @Mock RawMaterialTypeRepository materialTypeRepository;
    @Mock ProductWorkflowResolutionService workflowResolutionService;
    @Mock BomWorkflowRevisionService bomWorkflowRevisionService;
    @InjectMocks BomSeasoningWorkspaceServiceImpl service;

    @Test
    void workspaceKeepsEmptyProcessesAndDeduplicatesMaterialWithoutSummingDosage() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 3L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p1", "p2", "p3");
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY), anyList())).thenReturn(List.of(
                workProcess("p1", "滚揉"), workProcess("p2", "焯水"), workProcess("p3", "熟制")));
        BomSeasoningItem first = binding(1L, "node-p1", "p1", "salt", "5.0000");
        BomSeasoningItem second = binding(2L, "node-p2", "p2", "salt", "1.5000");
        when(seasoningItemRepository.findByRecipeIdOrderBySeqAsc(RECIPE)).thenReturn(List.of(first, second));
        when(materialTypeRepository.findAllById(any())).thenReturn(List.of(material("salt")));

        BomSeasoningWorkspaceResponse response = service.getWorkspace(FACTORY, RECIPE);

        assertTrue(response.isEditable());
        assertEquals(3L, response.getSeasoningRevision());
        assertEquals(101L, response.getWorkflowRevisionId());
        assertEquals(41L, response.getWorkflowId());
        assertEquals(1, response.getWorkflowDefinitionVersion());
        assertEquals("hash-101", response.getWorkflowRevisionHash());
        assertEquals("DRAFT", response.getWorkflowRevisionStatus());
        assertEquals(LocalDateTime.of(2026, 7, 20, 12, 0), response.getWorkflowRevisionSavedAt());
        assertEquals(1, response.getWorkflowRootCount());
        assertEquals(3, response.getWorkflowProcessCount());
        assertEquals(1, response.getWorkflowTargetCount());
        assertEquals("product-1", response.getWorkflowTargetProductTypeId());
        assertEquals(3, response.getProcesses().size());
        assertTrue(response.getProcesses().get(2).getBindings().isEmpty());
        assertEquals(1, response.getMaterialSummaries().size());
        assertEquals(2, response.getMaterialSummaries().get(0).getProcessUsages().size());
        assertEquals(new BigDecimal("5.0000"),
                response.getMaterialSummaries().get(0).getProcessUsages().get(0).getDosagePerKgG());
        assertEquals(new BigDecimal("1.5000"),
                response.getMaterialSummaries().get(0).getProcessUsages().get(1).getDosagePerKgG());
    }

    @Test
    void workspaceUsesPinnedDraftRevisionBeforeRuntimeAndLegacyPaths() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 3L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p1", "p3");
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY), anyList())).thenReturn(List.of(
                workProcess("p1", "前处理"), workProcess("p3", "熟制")));
        when(seasoningItemRepository.findByRecipeIdOrderBySeqAsc(RECIPE)).thenReturn(List.of());
        when(materialTypeRepository.findAllById(any())).thenReturn(List.of());

        BomSeasoningWorkspaceResponse response = service.getWorkspace(FACTORY, RECIPE);

        assertEquals(List.of("p1", "p3"), response.getProcesses().stream()
                .map(BomSeasoningWorkspaceResponse.ProcessView::getWorkProcessId).toList());
        verify(productWorkProcessRepository, never())
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(anyString(), anyString());
        verifyNoInteractions(workflowResolutionService);
    }

    @Test
    void createAllowsSameMaterialInAnotherProcessAndUsesAuthoritativeSnapshot() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 4L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p1", "p2");
        RawMaterialType material = material("salt");
        when(materialTypeRepository.findById("salt")).thenReturn(Optional.of(material));
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                RECIPE, "node-p2", "salt")).thenReturn(Optional.empty());
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdOrderBySeqAsc(RECIPE, "node-p2"))
                .thenReturn(List.of());
        when(recipeRepository.claimSeasoningRevision(RECIPE, FACTORY, 4L)).thenReturn(1);
        when(seasoningItemRepository.save(any())).thenAnswer(inv -> {
            BomSeasoningItem saved = inv.getArgument(0);
            saved.setId(22L);
            return saved;
        });
        SeasoningBindingCreateRequest request = createRequest(4L, "node-p2");

        var response = service.createBinding(FACTORY, RECIPE, "p2", request);

        assertEquals(5L, response.getSeasoningRevision());
        ArgumentCaptor<BomSeasoningItem> captor = ArgumentCaptor.forClass(BomSeasoningItem.class);
        verify(seasoningItemRepository).save(captor.capture());
        assertEquals("档案食盐", captor.getValue().getName());
        assertEquals(new BigDecimal("2.3000"), captor.getValue().getPriceSource1());
        assertNull(captor.getValue().getPriceSource2());
        assertEquals("p2", captor.getValue().getWorkProcessId());
        assertEquals("node-p2", captor.getValue().getWorkflowProcessNodeId());
    }

    @Test
    void duplicateInSameProcessReturnsConflictWithExistingBinding() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 4L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p1");
        when(materialTypeRepository.findById("salt")).thenReturn(Optional.of(material("salt")));
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                RECIPE, "node-p1", "salt")).thenReturn(Optional.of(
                        binding(9L, "node-p1", "p1", "salt", "5")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createBinding(FACTORY, RECIPE, "p1", createRequest(4L, "node-p1")));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("bindingId=9"));
        verify(recipeRepository, never()).claimSeasoningRevision(anyString(), anyString(), anyLong());
    }

    @Test
    void staleRevisionReturnsReloadConflictAndDoesNotWrite() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 4L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p1");
        when(materialTypeRepository.findById("salt")).thenReturn(Optional.of(material("salt")));
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                RECIPE, "node-p1", "salt")).thenReturn(Optional.empty());
        when(recipeRepository.claimSeasoningRevision(RECIPE, FACTORY, 3L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createBinding(FACTORY, RECIPE, "p1", createRequest(3L, "node-p1")));

        assertEquals(409, ex.getCode());
        assertEquals("SEASONING_REVISION_CONFLICT", ex.getErrorCode());
        verify(seasoningItemRepository, never()).save(any());
    }

    private BomRecipe recipe(BomRecipe.Status status, long revision) {
        return BomRecipe.builder().id(RECIPE).factoryId(FACTORY).recipeCode("BOM-1")
                .productTypeId("product-1").productName("产品").outputQuantityPerUnit(BigDecimal.ONE)
                .status(status).seasoningRevision(revision)
                .workflowRevisionId(101L).workflowRevisionHash("hash-101").build();
    }

    private ProductWorkProcess process(String id, int order) {
        return ProductWorkProcess.builder().factoryId(FACTORY).productTypeId("product-1")
                .workProcessId(id).processOrder(order).build();
    }

    private WorkProcess workProcess(String id, String name) {
        WorkProcess process = new WorkProcess();
        process.setId(id);
        process.setFactoryId(FACTORY);
        process.setProcessName(name);
        return process;
    }

    private RawMaterialType material(String id) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId(FACTORY);
        material.setCode("003001");
        material.setName("档案食盐");
        material.setCategory("辅料");
        material.setUnit("kg");
        material.setIsActive(true);
        material.setMovingAvgPrice(new BigDecimal("2.3000"));
        return material;
    }

    private BomSeasoningItem binding(Long id, String node, String process, String material, String dosage) {
        BomSeasoningItem binding = new BomSeasoningItem();
        binding.setId(id);
        binding.setRecipeId(RECIPE);
        binding.setFactoryId(FACTORY);
        binding.setWorkProcessId(process);
        binding.setWorkflowProcessNodeId(node);
        binding.setMaterialTypeId(material);
        binding.setSection("COOKING");
        binding.setSeq(0);
        binding.setName("档案食盐");
        binding.setDosagePerKgG(new BigDecimal(dosage));
        binding.setPriceSource1(new BigDecimal("2.3000"));
        binding.setCountInSeasoning(true);
        return binding;
    }

    private SeasoningBindingCreateRequest createRequest(long revision, String processNodeId) {
        SeasoningBindingCreateRequest request = new SeasoningBindingCreateRequest();
        request.setExpectedRevision(revision);
        request.setMaterialTypeId("salt");
        request.setWorkflowProcessNodeId(processNodeId);
        request.setDosagePerKgG(new BigDecimal("5.0000"));
        request.setSubsequentPotRatio(new BigDecimal("0.5000"));
        return request;
    }

    private void pin(BomRecipe recipe, String... processIds) {
        List<PinnedWorkflowGraph.ProcessStep> steps = new java.util.ArrayList<>();
        for (int i = 0; i < processIds.length; i++) {
            steps.add(new PinnedWorkflowGraph.ProcessStep("node-" + processIds[i], processIds[i], i + 1));
        }
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(
                new PinnedWorkflowGraph(101L, 41L, 1, "hash-101", "product-1", "finished",
                        List.of("raw-1"), steps, List.of(), List.of()));
        lenient().when(bomWorkflowRevisionService.listCompatible(FACTORY, RECIPE)).thenReturn(List.of(
                WorkflowRevisionCandidateDTO.builder()
                        .revisionId(101L)
                        .workflowId(41L)
                        .definitionVersion(1)
                        .revisionHash("hash-101")
                        .status("DRAFT")
                        .savedAt(LocalDateTime.of(2026, 7, 20, 12, 0))
                        .processCount(processIds.length)
                        .compatible(true)
                        .build()));
    }
}
