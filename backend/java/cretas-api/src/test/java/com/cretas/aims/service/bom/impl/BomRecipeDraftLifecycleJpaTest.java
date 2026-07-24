package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.uom.MaterialUomConverter;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.validation.ProductConfigurationReadinessService;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import(BomRecipeServiceImpl.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("BOM draft lifecycle JPA integration")
class BomRecipeDraftLifecycleJpaTest {

    @Autowired
    private BomRecipeService service;

    @Autowired
    private FactoryRepository factoryRepository;

    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Autowired
    private BomRecipeRepository recipeRepository;

    @Autowired
    private BomRecipeItemRepository itemRepository;

    @Autowired
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ProductWorkflowResolutionService workflowResolutionService;

    @MockBean
    private MaterialUomConverter materialUomConverter;

    @MockBean
    private NestedBomCostService nestedBomCostService;

    @MockBean
    private UnitContractService unitContractService;

    @MockBean
    private ProductConfigurationReadinessService productConfigurationReadinessService;

    @MockBean
    private BomItemSubstituteService bomItemSubstituteService;

    @MockBean
    private BomWorkflowRevisionService bomWorkflowRevisionService;

    @BeforeEach
    void stubUnitContract() {
        when(unitContractService.normalize(anyString(), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            String code = switch (raw) {
                case "盒" -> "box";
                case "袋" -> "bag";
                case "包" -> "pack";
                case "瓶" -> "bottle";
                case "克" -> "g";
                case "千克" -> "kg";
                case "毫升" -> "ml";
                case "升" -> "L";
                default -> raw;
            };
            UnitDimension dimension = switch (code) {
                case "g", "kg" -> UnitDimension.MASS;
                case "ml", "L" -> UnitDimension.VOLUME;
                default -> UnitDimension.PACKAGE;
            };
            return new UnitNormalizationResult(raw, code,
                    new CanonicalUnit(code, dimension, code, BigDecimal.ONE, code, 6));
        });
        when(unitContractService.areEquivalent(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String left = invocation.getArgument(1);
                    String right = invocation.getArgument(2);
                    return canonicalUnit(left).equals(canonicalUnit(right));
                });
        when(materialUomConverter.isWriteUnitCompatible(anyString(), anyString())).thenReturn(true);
        when(bomWorkflowRevisionService.autoBindUniqueDraft(anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> workflowBinding(invocation.getArgument(1)));
        when(bomWorkflowRevisionService.resolvePinnedGraph(anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> workflowBinding(invocation.getArgument(1)).graph());
        when(bomWorkflowRevisionService.resolvePinnedTerminalOutputs(
                anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> workflowBinding(invocation.getArgument(1)).terminalOutputs());
    }

    private BomWorkflowRevisionService.WorkflowBinding workflowBinding(BomRecipe recipe) {
        ProductProcessWorkflowRevision revision = new ProductProcessWorkflowRevision();
        revision.setId(100L);
        revision.setFactoryId(recipe.getFactoryId());
        revision.setProductTypeId(recipe.getProductTypeId());
        revision.setWorkflowId(10L);
        revision.setDefinitionVersion(1);
        revision.setSchemaVersion(1);
        revision.setRevisionHash("test-revision");
        revision.setNodesJson("[]");
        revision.setEdgesJson("[]");
        revision.setStatus(ProductProcessWorkflowRevision.Status.DRAFT);
        revision.setStructurallyComplete(true);
        recipe.setWorkflowRevisionId(revision.getId());
        recipe.setWorkflowId(revision.getWorkflowId());
        recipe.setWorkflowDefinitionVersion(revision.getDefinitionVersion());
        recipe.setWorkflowRevisionHash(revision.getRevisionHash());
        recipe.setWorkflowSchemaVersion(revision.getSchemaVersion());
        recipe.setWorkflowNodesSnapshotJson(revision.getNodesJson());
        recipe.setWorkflowEdgesSnapshotJson(revision.getEdgesJson());
        recipe.setTargetTerminalNodeId("terminal");
        recipe.setOutputRole(BomRecipe.OutputRole.MAIN);
        recipe.setCostAllocationRatio(new BigDecimal("100"));

        String materialTypeId = "RAW-WF-" + recipe.getProductTypeId();
        Map<String, Object> rawData = new LinkedHashMap<>();
        rawData.put("skuId", materialTypeId);
        rawData.put("name", "Workflow input");
        ProductProcessWorkflowDTO.Node raw = new ProductProcessWorkflowDTO.Node(
                "raw-input", "RAW_MATERIAL", new ProductProcessWorkflowDTO.Position(0D, 0D), rawData);
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("workProcessId", "WP-TEST");
        processData.put("ports", List.of(
                Map.of("id", "input", "direction", "INPUT", "materialNodeId", "raw-input",
                        "materialKind", "RAW_MATERIAL", "unit", "kg", "ordinal", 0),
                Map.of("id", "output", "direction", "OUTPUT", "materialNodeId", "terminal",
                        "materialKind", "FINISHED_GOOD", "unit", recipe.getOutputUnit(),
                        "ordinal", 0, "outputRole", "MAIN",
                        "costAllocationRatio", new BigDecimal("100"))));
        ProductProcessWorkflowDTO.Node process = new ProductProcessWorkflowDTO.Node(
                "process", "PROCESS", new ProductProcessWorkflowDTO.Position(100D, 0D), processData);
        Map<String, Object> terminalData = new LinkedHashMap<>();
        terminalData.put("skuId", recipe.getProductTypeId());
        terminalData.put("baseUnit", recipe.getOutputUnit());
        ProductProcessWorkflowDTO.Node terminal = new ProductProcessWorkflowDTO.Node(
                "terminal", "FINISHED_GOOD", new ProductProcessWorkflowDTO.Position(200D, 0D), terminalData);
        List<ProductProcessWorkflowDTO.Node> nodes = List.of(raw, process, terminal);
        List<ProductProcessWorkflowDTO.Edge> edges = List.of(
                new ProductProcessWorkflowDTO.Edge("edge-input", "raw-input", "output", "process", "input"),
                new ProductProcessWorkflowDTO.Edge("edge-output", "process", "output", "terminal", "input"));
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setNodes(nodes);
        definition.setEdges(edges);
        BomWorkflowRevisionService.TerminalOutput target =
                new BomWorkflowRevisionService.TerminalOutput(
                        "terminal", recipe.getProductTypeId(), "process", "output",
                        BomRecipe.OutputRole.MAIN, new BigDecimal("100"), recipe.getOutputUnit());
        PinnedWorkflowGraph graph = new PinnedWorkflowGraph(
                revision.getId(), revision.getWorkflowId(), revision.getDefinitionVersion(),
                revision.getRevisionHash(), recipe.getProductTypeId(), "terminal",
                List.of(materialTypeId),
                List.of(new PinnedWorkflowGraph.ProcessStep("process", "WP-TEST", 1)),
                nodes, edges);
        return new BomWorkflowRevisionService.WorkflowBinding(
                revision, definition, graph, List.of(target), target);
    }

    @Test
    @DisplayName("zero versions creates a v1 DRAFT with an auto-generated Workflow input skeleton")
    void createsEmptyFirstDraft() {
        ProductType product = saveProduct("FIRST", "盒", new BigDecimal("500"));

        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());

        assertThat(draft.getVersion()).isEqualTo(1);
        assertThat(draft.getStatus()).isEqualTo(BomRecipe.Status.DRAFT);
        assertThat(draft.getIsCurrent()).isFalse();
        assertThat(draft.getProductName()).isEqualTo(product.getName());
        assertThat(draft.getOutputUnit()).isEqualTo("box");
        assertThat(draft.getOutputQuantityPerUnit()).isEqualByComparingTo("1");
        assertThat(draft.getNetContentQuantity()).isEqualByComparingTo("500");
        assertThat(draft.getNetContentUnit()).isEqualTo("g");
        assertThat(draft.getItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getWorkflowMaterialNodeId()).isEqualTo("raw-input");
                    assertThat(item.getWorkflowInputPortId()).isEqualTo("input");
                    assertThat(item.getWorkflowEdgeId()).isEqualTo("edge-input");
                    assertThat(item.getCostScope()).isEqualTo("SHARED");
                    assertThat(item.getCostScopeKey()).isEqualTo("terminal");
                });
    }

    @Test
    @DisplayName("volume SKU snapshots one bottle output and 500 ml net content")
    void snapshotsVolumeSkuWithoutTurningNetContentIntoOutputQuantity() {
        ProductType product = saveStructuredProduct(
                "VOLUME", "瓶", new BigDecimal("500"), "ml");

        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());

        assertThat(draft.getOutputQuantityPerUnit()).isEqualByComparingTo("1");
        assertThat(draft.getOutputUnit()).isEqualTo("bottle");
        assertThat(draft.getNetContentQuantity()).isEqualByComparingTo("500");
        assertThat(draft.getNetContentUnit()).isEqualTo("ml");
    }

    @Test
    @DisplayName("editing a generated input changes its main material without losing the stable Workflow slot")
    void editsSkeletonMainMaterialAndKeepsStableIdentity() {
        ProductType product = saveProduct("EDIT-SLOT", "盒", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        BomRecipeItem skeleton =
                itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();
        RawMaterialType replacement = saveRawMaterial(
                product.getFactoryId(), "RAW-REPLACEMENT-" + product.getId(), "替代主料");

        BomRecipeItemDTO request = new BomRecipeItemDTO();
        request.setMaterialTypeId(replacement.getId());
        request.setMaterialCategory("RAW");
        request.setUnit("kg");
        request.setSortOrder(skeleton.getSortOrder());
        request.setIsOptional(false);
        request.setWorkflowMaterialNodeId(skeleton.getWorkflowMaterialNodeId());
        request.setWorkflowInputPortId(skeleton.getWorkflowInputPortId());
        request.setWorkflowEdgeId(skeleton.getWorkflowEdgeId());
        request.setCostScope(skeleton.getCostScope());

        BomRecipeItem updated =
                service.updateItem(product.getFactoryId(), skeleton.getId(), request);

        assertThat(updated.getMaterialTypeId()).isEqualTo(replacement.getId());
        assertThat(updated.getMaterialName()).isEqualTo("替代主料");
        assertThat(updated.getWorkflowMaterialNodeId()).isEqualTo("raw-input");
        assertThat(updated.getWorkflowInputPortId()).isEqualTo("input");
        assertThat(updated.getWorkflowEdgeId()).isEqualTo("edge-input");
        assertThat(updated.getCostScope()).isEqualTo("SHARED");
    }

    @Test
    @DisplayName("explicit Workflow upgrade keeps mapped rows and appends a skeleton for a new stable input")
    void workflowUpgradeAppendsNewInputSkeleton() {
        ProductType product = saveProduct("UPGRADE-SLOT", "盒", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        String newMaterialId = "RAW-NEW-" + product.getId();
        saveRawMaterial(product.getFactoryId(), newMaterialId, "新增工艺投入");

        when(bomWorkflowRevisionService.upgradeToLatestCompatibleDraft(
                anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> {
                    BomRecipe recipe = invocation.getArgument(1);
                    BomWorkflowRevisionService.WorkflowBinding binding = workflowBinding(recipe);
                    recipe.setWorkflowDefinitionVersion(2);
                    return binding;
                });
        when(bomWorkflowRevisionService.resolvePinnedGraph(
                anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> {
                    BomRecipe recipe = invocation.getArgument(1);
                    return Integer.valueOf(2).equals(recipe.getWorkflowDefinitionVersion())
                            ? twoInputGraph(recipe, newMaterialId)
                            : workflowBinding(recipe).graph();
                });

        service.upgradeWorkflowRevision(product.getFactoryId(), draft.getId());

        assertThat(itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()))
                .extracting(BomRecipeItem::getWorkflowEdgeId)
                .containsExactly("edge-input", "edge-extra");
    }

    @Test
    @DisplayName("kg-base SKU still snapshots one base unit instead of its net weight")
    void snapshotsKgBaseSkuAsOneKgUnit() {
        ProductType product = saveStructuredProduct(
                "KG-BASE", "kg", BigDecimal.ONE, "kg");

        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());

        assertThat(draft.getOutputQuantityPerUnit()).isEqualByComparingTo("1");
        assertThat(draft.getOutputUnit()).isEqualTo("kg");
        assertThat(draft.getNetContentQuantity()).isEqualByComparingTo("1");
        assertThat(draft.getNetContentUnit()).isEqualTo("kg");
    }

    @Test
    @DisplayName("repeated ensure returns the same draft without incrementing version")
    void repeatedEnsureReusesDraft() {
        ProductType product = saveProduct("REPEAT", "盒", new BigDecimal("250"));

        BomRecipe first = service.ensureDraft(product.getFactoryId(), product.getId());
        BomRecipe second = service.ensureDraft(product.getFactoryId(), product.getId());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(recipeRepository.countByFactoryIdAndProductTypeId(
                product.getFactoryId(), product.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("BOM-only clone inherits the exact Workflow revision and immutable snapshot")
    void bomOnlyCloneKeepsExactWorkflowSnapshotWithoutRequestingANewDraft() {
        ProductType product = saveProduct("CLONE-PIN", "盒", new BigDecimal("500"));
        BomRecipe original = service.ensureDraft(product.getFactoryId(), product.getId());
        completeWorkflowSkeleton(original);
        BomRecipe active = service.activateRecipe(product.getFactoryId(), original.getId(), 1309L);
        clearInvocations(bomWorkflowRevisionService);

        BomRecipe cloned = service.ensureDraft(product.getFactoryId(), product.getId());

        assertThat(cloned.getId()).isNotEqualTo(active.getId());
        assertThat(cloned.getStatus()).isEqualTo(BomRecipe.Status.DRAFT);
        assertThat(cloned.getWorkflowRevisionId()).isEqualTo(active.getWorkflowRevisionId());
        assertThat(cloned.getWorkflowId()).isEqualTo(active.getWorkflowId());
        assertThat(cloned.getWorkflowDefinitionVersion()).isEqualTo(active.getWorkflowDefinitionVersion());
        assertThat(cloned.getWorkflowRevisionHash()).isEqualTo(active.getWorkflowRevisionHash());
        assertThat(cloned.getWorkflowNodesSnapshotJson()).isEqualTo(active.getWorkflowNodesSnapshotJson());
        assertThat(cloned.getWorkflowEdgesSnapshotJson()).isEqualTo(active.getWorkflowEdgesSnapshotJson());
        assertThat(cloned.getTargetTerminalNodeId()).isEqualTo(active.getTargetTerminalNodeId());
        assertThat(cloned.getBomFamilyId()).isNotEqualTo(active.getBomFamilyId());
        verify(bomWorkflowRevisionService, never())
                .autoBindUniqueDraft(anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class));
        assertThat(recipeRepository.findById(active.getId()))
                .hasValueSatisfying(saved -> assertThat(saved.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE));
    }

    @Test
    @DisplayName("concurrent ensure calls serialize on the SKU and create one draft")
    void concurrentEnsureCreatesOneDraft() throws Exception {
        ProductType product = saveProduct("CONCURRENT", "包", new BigDecimal("180"));
        int callers = 5;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.ensureDraft(product.getFactoryId(), product.getId()).getId();
                }));
            }
            ready.await();
            start.countDown();

            Set<String> ids = new java.util.HashSet<>();
            for (Future<String> future : futures) {
                ids.add(future.get());
            }
            assertThat(ids).hasSize(1);
            assertThat(recipeRepository.countByFactoryIdAndProductTypeId(
                    product.getFactoryId(), product.getId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("factory scoped lookup does not expose another factory SKU")
    void rejectsCrossFactoryProduct() {
        ProductType product = saveProduct("ISOLATION", "袋", new BigDecimal("300"));

        assertThatThrownBy(() -> service.ensureDraft("OTHER-FACTORY", product.getId()))
                .hasMessageContaining("产品不存在");
        assertThat(recipeRepository.countByFactoryIdAndProductTypeId(
                product.getFactoryId(), product.getId())).isZero();
    }

    @Test
    @DisplayName("activation commits without replacing the orphan-removal item collection")
    void activationCommitsWithPersistedRawItems() {
        ProductType product = saveProduct("ACTIVATE", "袋", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());

        BomRecipeItem item = completeWorkflowSkeleton(draft);

        BomRecipe activated = service.activateRecipe(product.getFactoryId(), draft.getId(), 1309L);

        assertThat(activated.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
        assertThat(activated.getIsCurrent()).isTrue();
        assertThat(recipeRepository.findById(draft.getId()))
                .hasValueSatisfying(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
                    assertThat(saved.getIsCurrent()).isTrue();
                });
        assertThat(itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()))
                .extracting(BomRecipeItem::getMaterialTypeId)
                .containsExactly(item.getMaterialTypeId());
    }

    @Test
    @DisplayName("packaging item without a positive fixed quantity cannot activate")
    void activationRejectsPackagingWithoutFixedQuantity() {
        ProductType product = saveProduct("PACKAGING-GATE", "盒", new BigDecimal("800"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());

        RawMaterialType packaging = new RawMaterialType();
        packaging.setId("PACKAGING-BOM-GATE");
        packaging.setFactoryId(product.getFactoryId());
        packaging.setCode("PACKAGING-GATE");
        packaging.setName("成品盒");
        packaging.setCategory("PACKAGING");
        packaging.setUnit("box");
        packaging.setIsActive(true);
        packaging.setIsAbacaPackaging(false);
        packaging.setCreatedBy(1L);
        rawMaterialTypeRepository.saveAndFlush(packaging);

        BomRecipeItem workflowInput =
                itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();
        workflowInput.setStandardQuantity(BigDecimal.ONE);
        workflowInput.setUnitPrice(new BigDecimal("10"));
        workflowInput.setQuantityToPriceFactor(BigDecimal.ONE);
        itemRepository.saveAndFlush(workflowInput);

        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId(draft.getId());
        item.setFactoryId(product.getFactoryId());
        item.setMaterialTypeId(packaging.getId());
        item.setMaterialName(packaging.getName());
        item.setUnit("box");
        item.setMaterialCategory("PACKAGING");
        item.setYieldRate(new BigDecimal("100.00"));
        item.setSortOrder(0);
        item.setIsOptional(false);
        item.setPerPortion(false);
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        itemRepository.saveAndFlush(item);

        assertThatThrownBy(() -> service.activateRecipe(product.getFactoryId(), draft.getId(), 1309L))
                .hasMessageContaining("成品盒")
                .hasMessageContaining("缺少有效数量");
        assertThat(recipeRepository.findById(draft.getId()))
                .hasValueSatisfying(saved -> assertThat(saved.getStatus()).isEqualTo(BomRecipe.Status.DRAFT));
    }

    private ProductType saveProduct(String suffix, String unit, BigDecimal gramsPerUnit) {
        ProductType product = newProduct(suffix, unit);
        product.setGramsPerUnit(gramsPerUnit);
        ProductType saved = productTypeRepository.saveAndFlush(product);
        saveWorkflowInputMaterial(saved);
        return saved;
    }

    private ProductType saveStructuredProduct(
            String suffix,
            String unit,
            BigDecimal netContentQuantity,
            String netContentUnit) {
        ProductType product = newProduct(suffix, unit);
        product.setNetContentQuantity(netContentQuantity);
        product.setNetContentUnit(netContentUnit);
        ProductType saved = productTypeRepository.saveAndFlush(product);
        saveWorkflowInputMaterial(saved);
        return saved;
    }

    private void saveWorkflowInputMaterial(ProductType product) {
        saveRawMaterial(product.getFactoryId(), "RAW-WF-" + product.getId(), "Workflow 固定投入");
    }

    private RawMaterialType saveRawMaterial(String factoryId, String id, String name) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId(factoryId);
        material.setCode(id);
        material.setName(name);
        material.setCategory("RAW");
        material.setUnit("kg");
        material.setIsActive(true);
        material.setIsAbacaPackaging(false);
        material.setCreatedBy(1L);
        return rawMaterialTypeRepository.saveAndFlush(material);
    }

    private PinnedWorkflowGraph twoInputGraph(BomRecipe recipe, String extraMaterialTypeId) {
        BomWorkflowRevisionService.WorkflowBinding base = workflowBinding(recipe);
        List<ProductProcessWorkflowDTO.Node> nodes = new ArrayList<>(base.graph().nodes());
        Map<String, Object> extraData = new LinkedHashMap<>();
        extraData.put("skuId", extraMaterialTypeId);
        extraData.put("name", "新增工艺投入");
        nodes.add(1, new ProductProcessWorkflowDTO.Node(
                "raw-extra", "RAW_MATERIAL",
                new ProductProcessWorkflowDTO.Position(0D, 80D), extraData));

        ProductProcessWorkflowDTO.Node process = nodes.stream()
                .filter(node -> "process".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> ports = new ArrayList<>();
        ports.add(new LinkedHashMap<>(Map.of(
                "id", "input", "direction", "INPUT", "materialNodeId", "raw-input",
                "materialKind", "RAW_MATERIAL", "unit", "kg", "ordinal", 0)));
        ports.add(new LinkedHashMap<>(Map.of(
                "id", "input-extra", "direction", "INPUT", "materialNodeId", "raw-extra",
                "materialKind", "RAW_MATERIAL", "unit", "kg", "ordinal", 1)));
        ports.add(new LinkedHashMap<>(Map.of(
                "id", "output", "direction", "OUTPUT", "materialNodeId", "terminal",
                "materialKind", "FINISHED_GOOD", "unit", recipe.getOutputUnit(),
                "ordinal", 0, "outputRole", "MAIN",
                "costAllocationRatio", new BigDecimal("100"))));
        process.getData().put("ports", ports);

        List<ProductProcessWorkflowDTO.Edge> edges = new ArrayList<>(base.graph().edges());
        edges.add(1, new ProductProcessWorkflowDTO.Edge(
                "edge-extra", "raw-extra", "output", "process", "input-extra"));
        return new PinnedWorkflowGraph(
                recipe.getWorkflowRevisionId(), recipe.getWorkflowId(),
                recipe.getWorkflowDefinitionVersion(), recipe.getWorkflowRevisionHash(),
                recipe.getProductTypeId(), "terminal",
                List.of("RAW-WF-" + recipe.getProductTypeId(), extraMaterialTypeId),
                List.of(new PinnedWorkflowGraph.ProcessStep("process", "WP-TEST", 1)),
                nodes, edges);
    }

    private BomRecipeItem completeWorkflowSkeleton(BomRecipe draft) {
        BomRecipeItem item = itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();
        item.setStandardQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("10"));
        item.setYieldRate(new BigDecimal("100.00"));
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        return itemRepository.saveAndFlush(item);
    }

    private ProductType newProduct(String suffix, String unit) {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        String factoryId = "F-BOM-" + suffix;
        Factory factory = new Factory();
        factory.setId(factoryId);
        factory.setName("BOM lifecycle " + suffix);
        factoryRepository.saveAndFlush(factory);

        ProductType product = new ProductType();
        product.setId("SKU-BOM-" + suffix);
        product.setFactoryId(factoryId);
        product.setCode("SKU-" + suffix);
        product.setName("测试 SKU " + suffix);
        product.setUnit(unit);
        product.setIsActive(true);
        product.setCreatedBy(1L);
        return product;
    }

    private String canonicalUnit(String raw) {
        return switch (raw) {
            case "盒" -> "box";
            case "袋" -> "bag";
            case "包" -> "pack";
            case "瓶" -> "bottle";
            case "克" -> "g";
            case "千克" -> "kg";
            case "毫升" -> "ml";
            case "升" -> "L";
            default -> raw;
        };
    }
}
