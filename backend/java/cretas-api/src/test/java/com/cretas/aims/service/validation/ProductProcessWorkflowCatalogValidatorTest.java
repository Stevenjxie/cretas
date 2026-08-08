package com.cretas.aims.service.validation;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class ProductProcessWorkflowCatalogValidatorTest {

    private static final String FACTORY_ID = "F001";
    private static final String PRODUCT_ID = "PT-FINISHED";

    @Mock
    private WorkProcessRepository workProcessRepository;

    @Mock
    private ProductTypeRepository productTypeRepository;

    @Mock
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    @Mock
    private BomRecipeItemRepository bomItemRepository;

    @Mock
    private BomRecipeRepository bomRecipeRepository;

    private ProductProcessWorkflowCatalogValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ProductProcessWorkflowCatalogValidator(
                workProcessRepository, productTypeRepository, rawMaterialTypeRepository,
                bomRecipeRepository, bomItemRepository);
        lenient().when(bomRecipeRepository.existsByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                        anyString(), anyString(), org.mockito.ArgumentMatchers.eq(BomRecipe.Status.ACTIVE)))
                .thenReturn(true);
        lenient().when(bomItemRepository.findCurrentByProduct(anyString(), anyString()))
                .thenReturn(List.of(new com.cretas.aims.entity.bom.BomRecipeItem()));
    }

    @Test
    void rejectsFinishedProcessWhosePrimaryOutputIsSemiFinished() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                        workflowWithPrimaryOutput("WP-PACK", "SEMI_FINISHED", "SFI-1")));

        assertCatalogMismatch(error, "Output Cell");
    }

    @Test
    void acceptsFinishedProcessBoundToCurrentProductSku() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD)));
        when(productTypeRepository.findByIdIn(List.of(PRODUCT_ID)))
                .thenReturn(List.of(product(PRODUCT_ID, FACTORY_ID, ProductCategory.FINISHED_PRODUCT)));

        assertDoesNotThrow(() -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                workflowWithPrimaryOutput("WP-PACK", "FINISHED_GOOD", PRODUCT_ID)));

        verify(workProcessRepository).findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK"));
        verify(productTypeRepository).findByIdIn(List.of(PRODUCT_ID));
    }

    @Test
    void rejectsFinishedOutputWithoutBom() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD)));
        when(productTypeRepository.findByIdIn(List.of(PRODUCT_ID)))
                .thenReturn(List.of(product(PRODUCT_ID, FACTORY_ID, ProductCategory.FINISHED_PRODUCT)));
        when(bomRecipeRepository.existsByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY_ID, PRODUCT_ID, BomRecipe.Status.ACTIVE)).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                        workflowWithPrimaryOutput("WP-PACK", "FINISHED_GOOD", PRODUCT_ID)));

        assertEquals(409, error.getCode());
        assertEquals("PRODUCT_PROCESS_WORKFLOW_BOM_REQUIRED", error.getErrorCode());
    }

    @Test
    void acceptsFinishedProcessBoundToDifferentFinishedProductVersion() {
        String alternateSkuId = "PT-OEM-V2";
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD)));
        when(productTypeRepository.findByIdIn(List.of(alternateSkuId)))
                .thenReturn(List.of(product(alternateSkuId, FACTORY_ID, ProductCategory.CONTRACT_MANUFACTURING)));

        assertDoesNotThrow(() -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                workflowWithPrimaryOutput("WP-PACK", "FINISHED_GOOD", alternateSkuId)));
    }

    @Test
    void rejectsMissingWorkProcess() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-MISSING")))
                .thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                        workflowWithPrimaryOutput("WP-MISSING", "FINISHED_GOOD", PRODUCT_ID)));

        assertCatalogMismatch(error, "Packing Cell");
    }

    @Test
    void rejectsOutputPortMaterialKindDifferentFromMaterialCellKind() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(workProcess("WP-PACK", WorkProcessOutputMaterialKind.SEMI_FINISHED)));
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                "WP-PACK", "SEMI_FINISHED", "SFI-1");
        outputPort(definition).put("materialKind", "FINISHED_GOOD");

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        assertCatalogMismatch(error, "Output Cell");
    }

    @Test
    void rejectsOutputPortWhoseMaterialNodeIdentityDiffersFromConnectedCell() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(workProcess("WP-PACK", WorkProcessOutputMaterialKind.SEMI_FINISHED)));
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                "WP-PACK", "SEMI_FINISHED", "SFI-1");
        outputPort(definition).put("materialNodeId", "material:other");

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        assertCatalogMismatch(error, "Packing Cell");
    }

    @Test
    void rejectsFinishedGoodCellBoundToSemiFinishedProductType() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD)));
        when(productTypeRepository.findByIdIn(List.of("SFI-1")))
                .thenReturn(List.of(product("SFI-1", FACTORY_ID, ProductCategory.SEMI_FINISHED)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                        workflowWithPrimaryOutput("WP-PACK", "FINISHED_GOOD", "SFI-1")));

        assertCatalogMismatch(error, "Output Cell");
    }

    @Test
    void rejectsSemiFinishedCellBoundToFinishedProductType() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-CUT")))
                .thenReturn(List.of(workProcess("WP-CUT", WorkProcessOutputMaterialKind.SEMI_FINISHED)));
        when(productTypeRepository.findByIdIn(List.of("SKU-1")))
                .thenReturn(List.of(product("SKU-1", FACTORY_ID, ProductCategory.FINISHED_PRODUCT)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                        workflowWithPrimaryOutput("WP-CUT", "SEMI_FINISHED", "SKU-1")));

        assertCatalogMismatch(error, "Output Cell");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "UNKNOWN", ProductCategory.RAW_MATERIAL,
            ProductCategory.PACKAGING, ProductCategory.SEASONING, ProductCategory.ADD_ON})
    void rejectsFinishedGoodCellBoundToMissingUnknownOrInputCategory(String category) {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD)));
        when(productTypeRepository.findByIdIn(List.of("SKU-1")))
                .thenReturn(List.of(product("SKU-1", FACTORY_ID, category)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                        workflowWithPrimaryOutput("WP-PACK", "FINISHED_GOOD", "SKU-1")));

        assertCatalogMismatch(error, "Output Cell");
    }

    @Test
    void rejectsOutputSkuMissingFromCatalog() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD)));
        when(productTypeRepository.findByIdIn(List.of("SKU-MISSING"))).thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                        workflowWithPrimaryOutput("WP-PACK", "FINISHED_GOOD", "SKU-MISSING")));

        assertCatalogMismatch(error, "Output Cell");
    }

    @Test
    void rejectsOutputSkuOwnedByDifferentFactory() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD)));
        when(productTypeRepository.findByIdIn(List.of("SKU-FOREIGN")))
                .thenReturn(List.of(product("SKU-FOREIGN", "F002", ProductCategory.FINISHED_PRODUCT)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                        workflowWithPrimaryOutput("WP-PACK", "FINISHED_GOOD", "SKU-FOREIGN")));

        assertCatalogMismatch(error, "Output Cell");
    }

    @Test
    void rejectsWorkflowWithoutAnyProcessCell() {
        ProductProcessWorkflowDTO definition = workflowWithoutProcess();

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        assertCatalogMismatch(error, "Finished Cell");
    }

    @Test
    void rejectsDuplicateOutputPortIds() {
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                "WP-PACK", "SEMI_FINISHED", "SFI-1");
        duplicatePort(definition, "out-1", "material:output", "SEMI_FINISHED", 1);
        stubProcessAndProducts("WP-PACK", WorkProcessOutputMaterialKind.SEMI_FINISHED,
                product("SFI-1", FACTORY_ID, ProductCategory.SEMI_FINISHED));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        assertCatalogMismatch(error, "Packing Cell");
    }

    @Test
    void rejectsTwoOutputPortsSharingOneMaterialCell() {
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                "WP-PACK", "SEMI_FINISHED", "SFI-1");
        addOutput(definition, "out-2", "material:output", "SEMI_FINISHED", "SFI-1", 1, false);
        stubProcessAndProducts("WP-PACK", WorkProcessOutputMaterialKind.SEMI_FINISHED,
                product("SFI-1", FACTORY_ID, ProductCategory.SEMI_FINISHED));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        assertCatalogMismatch(error, "Packing Cell");
    }

    @Test
    void rejectsTwoOutputPortsBackedByOnlyOneEdge() {
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                "WP-PACK", "SEMI_FINISHED", "SFI-1");
        duplicatePort(definition, "out-1", "material:output", "SEMI_FINISHED", 1);
        stubProcessAndProducts("WP-PACK", WorkProcessOutputMaterialKind.SEMI_FINISHED,
                product("SFI-1", FACTORY_ID, ProductCategory.SEMI_FINISHED));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        assertCatalogMismatch(error, "Packing Cell");
    }

    @Test
    void rejectsOneOutputPortBackedByTwoEdges() {
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                "WP-PACK", "SEMI_FINISHED", "SFI-1");
        definition.getEdges().add(new ProductProcessWorkflowDTO.Edge(
                "edge:duplicate", "process:packing", "out-1", "material:output", "input-2"));
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(workProcess("WP-PACK", WorkProcessOutputMaterialKind.SEMI_FINISHED)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        assertCatalogMismatch(error, "Packing Cell");
    }

    @Test
    void rejectsUndeclaredGhostSourceHandle() {
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                "WP-PACK", "SEMI_FINISHED", "SFI-1");
        addMaterial(definition, "material:ghost", "SEMI_FINISHED", "SFI-GHOST");
        definition.getEdges().add(new ProductProcessWorkflowDTO.Edge(
                "edge:ghost", "process:packing", "ghost", "material:ghost", "input"));
        stubProcessAndProducts("WP-PACK", WorkProcessOutputMaterialKind.SEMI_FINISHED,
                product("SFI-1", FACTORY_ID, ProductCategory.SEMI_FINISHED));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        assertCatalogMismatch(error, "Packing Cell");
    }

    @Test
    void rejectsExtraProcessOutgoingEdge() {
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                "WP-PACK", "SEMI_FINISHED", "SFI-1");
        addMaterial(definition, "material:extra", "FINISHED_GOOD", "SKU-EXTRA");
        definition.getEdges().add(new ProductProcessWorkflowDTO.Edge(
                "edge:extra", "process:packing", "not-a-port", "material:extra", "input"));
        stubProcessAndProducts("WP-PACK", WorkProcessOutputMaterialKind.SEMI_FINISHED,
                product("SFI-1", FACTORY_ID, ProductCategory.SEMI_FINISHED));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        assertCatalogMismatch(error, "Packing Cell");
    }

    @Test
    void rejectsDuplicateOutputOrdinals() {
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                "WP-PACK", "SEMI_FINISHED", "SFI-1");
        addOutput(definition, "out-2", "material:second", "SEMI_FINISHED", "SFI-2", 0, true);
        stubProcessAndProducts("WP-PACK", WorkProcessOutputMaterialKind.SEMI_FINISHED,
                product("SFI-1", FACTORY_ID, ProductCategory.SEMI_FINISHED),
                product("SFI-2", FACTORY_ID, ProductCategory.SEMI_FINISHED));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        assertCatalogMismatch(error, "Packing Cell");
    }

    @Test
    void usesUniqueSmallestOrdinalAsPrimaryRegardlessOfPortListOrder() {
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                "WP-PACK", "SEMI_FINISHED", "SFI-1");
        outputPort(definition).put("ordinal", 9);
        addOutput(definition, "out-2", "material:finished", "FINISHED_GOOD", "SKU-FG", 2, true);
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD)));
        when(productTypeRepository.findByIdIn(List.of("SFI-1", "SKU-FG"))).thenReturn(List.of(
                product("SFI-1", FACTORY_ID, ProductCategory.SEMI_FINISHED),
                product("SKU-FG", FACTORY_ID, ProductCategory.FINISHED_PRODUCT)));

        assertDoesNotThrow(() -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));
    }

    @Test
    void rejectsWorkProcessReturnedForDifferentFactory() {
        WorkProcess foreign = workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD);
        foreign.setFactoryId("F002");
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(foreign));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                        workflowWithPrimaryOutput("WP-PACK", "FINISHED_GOOD", PRODUCT_ID)));

        assertCatalogMismatch(error, "Packing Cell");
    }

    @Test
    void rejectsDuplicateWorkProcessRowsWithStableBusinessError() {
        WorkProcess first = workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD);
        WorkProcess duplicate = workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD);
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(first, duplicate));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                        workflowWithPrimaryOutput("WP-PACK", "FINISHED_GOOD", PRODUCT_ID)));

        assertCatalogMismatch(error, "Packing Cell");
    }

    @Test
    void rejectsDuplicateRequestedProductRowsWithStableBusinessError() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD)));
        ProductType product = product(PRODUCT_ID, FACTORY_ID, ProductCategory.FINISHED_PRODUCT);
        ProductType duplicate = product(PRODUCT_ID, FACTORY_ID, ProductCategory.FINISHED_PRODUCT);
        when(productTypeRepository.findByIdIn(List.of(PRODUCT_ID)))
                .thenReturn(List.of(product, duplicate));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                        workflowWithPrimaryOutput("WP-PACK", "FINISHED_GOOD", PRODUCT_ID)));

        assertCatalogMismatch(error, "Output Cell");
    }

    @Test
    void ignoresUnrequestedProductRowsButStillValidatesRequestedSku() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-PACK")))
                .thenReturn(List.of(workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD)));
        when(productTypeRepository.findByIdIn(List.of(PRODUCT_ID))).thenReturn(List.of(
                product(PRODUCT_ID, FACTORY_ID, ProductCategory.FINISHED_PRODUCT),
                product("UNREQUESTED", "F002", ProductCategory.SEMI_FINISHED)));

        assertDoesNotThrow(() -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                workflowWithPrimaryOutput("WP-PACK", "FINISHED_GOOD", PRODUCT_ID)));
    }

    @Test
    void rejectsConnectedFinishedCellWithoutProcessProducer() {
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                "WP-CUT", "SEMI_FINISHED", "SFI-1");
        addMaterial(definition, "material:unproduced-finished", "FINISHED_GOOD", "SKU-FOREIGN");
        definition.getEdges().add(new ProductProcessWorkflowDTO.Edge(
                "edge:material-direct", "material:output", "output",
                "material:unproduced-finished", "input"));
        stubProcessAndProducts("WP-CUT", WorkProcessOutputMaterialKind.SEMI_FINISHED,
                product("SFI-1", FACTORY_ID, ProductCategory.SEMI_FINISHED));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        assertCatalogMismatch(error, "material:unproduced-finished");
    }

    @Test
    void rejectsTwoProcessesSharingOneOutputMaterialCell() {
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                "WP-CUT", "SEMI_FINISHED", "SFI-1");
        definition.getNodes().add(processNode(
                "process:second", "WP-MIX", "in-second", "out-second",
                "material:output", "SEMI_FINISHED"));
        definition.getEdges().add(new ProductProcessWorkflowDTO.Edge(
                "edge:second-producer", "process:second", "out-second", "material:output", "input"));
        when(workProcessRepository.findByFactoryIdAndIdIn(
                FACTORY_ID, List.of("WP-CUT", "WP-MIX"))).thenReturn(List.of(
                workProcess("WP-CUT", WorkProcessOutputMaterialKind.SEMI_FINISHED),
                workProcess("WP-MIX", WorkProcessOutputMaterialKind.SEMI_FINISHED)));
        lenient().when(productTypeRepository.findByIdIn(List.of("SFI-1")))
                .thenReturn(List.of(product("SFI-1", FACTORY_ID, ProductCategory.SEMI_FINISHED)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        assertCatalogMismatch(error, "Output Cell");
    }

    @Test
    void rejectsProducedMaterialWithAdditionalIncomingMaterialEdge() {
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                "WP-CUT", "SEMI_FINISHED", "SFI-1");
        addMaterial(definition, "material:raw-extra", "RAW_MATERIAL", "RM-EXTRA");
        definition.getEdges().add(new ProductProcessWorkflowDTO.Edge(
                "edge:extra-incoming", "material:raw-extra", "output", "material:output", "input-extra"));
        stubProcessAndProducts("WP-CUT", WorkProcessOutputMaterialKind.SEMI_FINISHED,
                product("SFI-1", FACTORY_ID, ProductCategory.SEMI_FINISHED));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        assertCatalogMismatch(error, "Output Cell");
    }

    @Test
    void rejectsProducerEdgeUsingNonInputMaterialTargetHandle() {
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                "WP-CUT", "SEMI_FINISHED", "SFI-1");
        definition.getEdges().getFirst().setTargetHandle("side-input");
        stubProcessAndProducts("WP-CUT", WorkProcessOutputMaterialKind.SEMI_FINISHED,
                product("SFI-1", FACTORY_ID, ProductCategory.SEMI_FINISHED));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        assertCatalogMismatch(error, "Output Cell");
    }

    @Test
    void acceptsValidRawToProcessToSemiToProcessToFinishedChain() {
        ProductProcessWorkflowDTO definition = validTwoProcessChain();
        when(workProcessRepository.findByFactoryIdAndIdIn(
                FACTORY_ID, List.of("WP-CUT", "WP-PACK"))).thenReturn(List.of(
                workProcess("WP-CUT", WorkProcessOutputMaterialKind.SEMI_FINISHED),
                workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD)));
        when(productTypeRepository.findByIdIn(List.of("SFI-CHAIN", "FG-CHAIN"))).thenReturn(List.of(
                product("SFI-CHAIN", FACTORY_ID, ProductCategory.SEMI_FINISHED),
                product("FG-CHAIN", FACTORY_ID, ProductCategory.FINISHED_PRODUCT)));

        new ProductProcessWorkflowValidator().validateForPublish(definition);
        assertDoesNotThrow(() -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));

        verify(productTypeRepository).findByIdIn(List.of("SFI-CHAIN", "FG-CHAIN"));
    }

    @Test
    void acceptsMultipleTerminalFinishedGoodsRegardlessOfLegacyAnchorCategory() {
        ProductProcessWorkflowDTO definition = validTwoProcessChain();
        definition.getNodes().add(processNodeWithInput(
                "process:quality", "WP-QUALITY", "in-quality",
                "material:semi-chain", "SEMI_FINISHED", "out-quality",
                "material:finished-quality", "FINISHED_GOOD"));
        addMaterial(definition, "material:finished-quality", "FINISHED_GOOD", "FG-QUALITY");
        definition.getEdges().add(new ProductProcessWorkflowDTO.Edge(
                "edge:semi-quality", "material:semi-chain", "output",
                "process:quality", "in-quality"));
        definition.getEdges().add(new ProductProcessWorkflowDTO.Edge(
                "edge:quality-finished", "process:quality", "out-quality",
                "material:finished-quality", "input"));
        when(workProcessRepository.findByFactoryIdAndIdIn(
                FACTORY_ID, List.of("WP-CUT", "WP-PACK", "WP-QUALITY"))).thenReturn(List.of(
                workProcess("WP-CUT", WorkProcessOutputMaterialKind.SEMI_FINISHED),
                workProcess("WP-PACK", WorkProcessOutputMaterialKind.FINISHED_GOOD),
                workProcess("WP-QUALITY", WorkProcessOutputMaterialKind.FINISHED_GOOD)));
        when(productTypeRepository.findByIdIn(List.of("SFI-CHAIN", "FG-CHAIN", "FG-QUALITY")))
                .thenReturn(List.of(
                        product("SFI-CHAIN", FACTORY_ID, ProductCategory.SEMI_FINISHED),
                        product("FG-CHAIN", FACTORY_ID, ProductCategory.FINISHED_PRODUCT),
                        product("FG-QUALITY", FACTORY_ID, ProductCategory.FINISHED_PRODUCT)));

        new ProductProcessWorkflowValidator().validateForPublish(definition);
        assertDoesNotThrow(() -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID, definition));
    }

    /**
     * 🔴 2026-08-08 真机: 副产整条不可用。
     *
     * <p>画布把副产建成「普通产出节点 + isByproduct 标记」(刻意不设 kind:'BYPRODUCT',
     * 「副产只看物料上的 isByproduct 标记, 与材质正交」), 选料下拉据此从 raw_material_types 筛;
     * 而本校验原先对**所有**产出一律查 product_types ⇒ 任何从界面选出来的副产必报
     * 「产出 SKU 不存在」。真机实证: RMT_1785513705730「验收-副产-肥油」在 raw_material_types
     * 存在、在 product_types count=0。
     *
     * <p>权威在物料侧(副产库存落 material_batches, 该表有 byproduct_unit_price 列)。
     */
    @Test
    void acceptsByproductOutputBoundToMaterialArchive() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-CUT")))
                .thenReturn(List.of(workProcess("WP-CUT", WorkProcessOutputMaterialKind.SEMI_FINISHED)));
        when(rawMaterialTypeRepository.findByIdIn(List.of("RMT-FAT")))
                .thenReturn(List.of(byproductMaterial("RMT-FAT", FACTORY_ID, true)));

        assertDoesNotThrow(() -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                byproductWorkflow("WP-CUT", "RMT-FAT")));

        // ⛔ 副产不许再去查产品目录 —— 查了就等于旧行为还在。
        verify(productTypeRepository, org.mockito.Mockito.never()).findByIdIn(List.of("RMT-FAT"));
    }

    /** 放宽不等于放弃保护: 没标副产的普通原料被塞进副产 Cell, 必须拦住。 */
    @Test
    void rejectsByproductCellBoundToMaterialNotMarkedAsByproduct() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-CUT")))
                .thenReturn(List.of(workProcess("WP-CUT", WorkProcessOutputMaterialKind.SEMI_FINISHED)));
        when(rawMaterialTypeRepository.findByIdIn(List.of("RMT-PLAIN")))
                .thenReturn(List.of(byproductMaterial("RMT-PLAIN", FACTORY_ID, false)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                        byproductWorkflow("WP-CUT", "RMT-PLAIN")));

        assertTrue(error.getMessage().contains("未标记为副产"), error.getMessage());
    }

    /** 跨租户拦截同样要保留。 */
    @Test
    void rejectsByproductFromAnotherFactory() {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("WP-CUT")))
                .thenReturn(List.of(workProcess("WP-CUT", WorkProcessOutputMaterialKind.SEMI_FINISHED)));
        when(rawMaterialTypeRepository.findByIdIn(List.of("RMT-OTHER")))
                .thenReturn(List.of(byproductMaterial("RMT-OTHER", "F999", true)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validateForPublish(FACTORY_ID, PRODUCT_ID,
                        byproductWorkflow("WP-CUT", "RMT-OTHER")));

        assertTrue(error.getMessage().contains("不属于当前工厂"), error.getMessage());
    }

    private RawMaterialType byproductMaterial(String id, String factoryId, boolean marked) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId(factoryId);
        material.setName("验收-副产-肥油");
        material.setIsByproduct(marked);
        return material;
    }

    /** 一道工序 + 一个带 isByproduct 标记的半成品产出 Cell —— 与画布「+ 副产」建出来的形状一致。 */
    private ProductProcessWorkflowDTO byproductWorkflow(String workProcessId, String skuId) {
        ProductProcessWorkflowDTO definition = workflowWithPrimaryOutput(
                workProcessId, "SEMI_FINISHED", skuId);
        definition.getNodes().stream()
                .filter(node -> "material:output".equals(node.getId()))
                .forEach(node -> node.getData().put("isByproduct", Boolean.TRUE));
        return definition;
    }

    private ProductProcessWorkflowDTO workflowWithPrimaryOutput(
            String workProcessId,
            String materialKind,
            String skuId) {
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("workProcessId", workProcessId);
        processData.put("processName", "Packing Cell");
        processData.put("ports", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of(
                "id", "out-1",
                "direction", "OUTPUT",
                "materialNodeId", "material:output",
                "materialKind", materialKind,
                "unit", "kg",
                "ordinal", 0)))));

        Map<String, Object> materialData = new LinkedHashMap<>();
        materialData.put("name", "Output Cell");
        materialData.put("skuId", skuId);

        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setNodes(new ArrayList<>(List.of(
                new ProductProcessWorkflowDTO.Node(
                        "process:packing", "PROCESS",
                        new ProductProcessWorkflowDTO.Position(0D, 0D), processData),
                new ProductProcessWorkflowDTO.Node(
                        "material:output", materialKind,
                        new ProductProcessWorkflowDTO.Position(200D, 0D), materialData))));
        definition.setEdges(new ArrayList<>(List.of(new ProductProcessWorkflowDTO.Edge(
                "edge:output", "process:packing", "out-1", "material:output", "input"))));
        return definition;
    }

    private ProductProcessWorkflowDTO workflowWithoutProcess() {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setNodes(new ArrayList<>(List.of(
                materialNode("material:raw", "RAW_MATERIAL", "Raw Cell", "RM-1"),
                materialNode("material:finished", "FINISHED_GOOD", "Finished Cell", PRODUCT_ID))));
        definition.setEdges(new ArrayList<>(List.of(new ProductProcessWorkflowDTO.Edge(
                "edge:direct", "material:raw", "output", "material:finished", "input"))));
        return definition;
    }

    private ProductProcessWorkflowDTO validTwoProcessChain() {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setNodes(new ArrayList<>(List.of(
                materialNode("material:raw-chain", "RAW_MATERIAL", "Raw Chain", "RM-CHAIN"),
                processNodeWithInput("process:cut", "WP-CUT", "in-cut",
                        "material:raw-chain", "RAW_MATERIAL", "out-cut",
                        "material:semi-chain", "SEMI_FINISHED"),
                materialNode("material:semi-chain", "SEMI_FINISHED", "Semi Chain", "SFI-CHAIN"),
                processNodeWithInput("process:pack", "WP-PACK", "in-pack",
                        "material:semi-chain", "SEMI_FINISHED", "out-pack",
                        "material:finished-chain", "FINISHED_GOOD"),
                materialNode("material:finished-chain", "FINISHED_GOOD", "Finished Chain", "FG-CHAIN"))));
        definition.setEdges(new ArrayList<>(List.of(
                new ProductProcessWorkflowDTO.Edge(
                        "edge:raw-cut", "material:raw-chain", "output", "process:cut", "in-cut"),
                new ProductProcessWorkflowDTO.Edge(
                        "edge:cut-semi", "process:cut", "out-cut", "material:semi-chain", "input"),
                new ProductProcessWorkflowDTO.Edge(
                        "edge:semi-pack", "material:semi-chain", "output", "process:pack", "in-pack"),
                new ProductProcessWorkflowDTO.Edge(
                        "edge:pack-finished", "process:pack", "out-pack", "material:finished-chain", "input"))));
        definition.setViewport(new ProductProcessWorkflowDTO.Viewport(0D, 0D, 1D));
        return definition;
    }

    private ProductProcessWorkflowDTO.Node processNode(
            String nodeId,
            String workProcessId,
            String inputPortId,
            String outputPortId,
            String outputMaterialNodeId,
            String outputMaterialKind) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workProcessId", workProcessId);
        data.put("processName", nodeId);
        data.put("ports", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of(
                        "id", inputPortId,
                        "direction", "INPUT",
                        "unit", "kg",
                        "ordinal", 0)),
                new LinkedHashMap<>(Map.of(
                        "id", outputPortId,
                        "direction", "OUTPUT",
                        "materialNodeId", outputMaterialNodeId,
                        "materialKind", outputMaterialKind,
                        "unit", "kg",
                        "ordinal", 0)))));
        return new ProductProcessWorkflowDTO.Node(
                nodeId, "PROCESS", new ProductProcessWorkflowDTO.Position(0D, 0D), data);
    }

    @SuppressWarnings("unchecked")
    private ProductProcessWorkflowDTO.Node processNodeWithInput(
            String nodeId,
            String workProcessId,
            String inputPortId,
            String inputMaterialNodeId,
            String inputMaterialKind,
            String outputPortId,
            String outputMaterialNodeId,
            String outputMaterialKind) {
        ProductProcessWorkflowDTO.Node node = processNode(
                nodeId,
                workProcessId,
                inputPortId,
                outputPortId,
                outputMaterialNodeId,
                outputMaterialKind);
        Map<String, Object> inputPort = ((List<Map<String, Object>>) node.getData().get("ports")).getFirst();
        inputPort.put("materialNodeId", inputMaterialNodeId);
        inputPort.put("materialKind", inputMaterialKind);
        return node;
    }

    @SuppressWarnings("unchecked")
    private void duplicatePort(
            ProductProcessWorkflowDTO definition,
            String portId,
            String materialNodeId,
            String materialKind,
            int ordinal) {
        ((List<Map<String, Object>>) definition.getNodes().getFirst().getData().get("ports"))
                .add(new LinkedHashMap<>(Map.of(
                        "id", portId,
                        "direction", "OUTPUT",
                        "materialNodeId", materialNodeId,
                        "materialKind", materialKind,
                        "unit", "kg",
                        "ordinal", ordinal)));
    }

    private void addOutput(
            ProductProcessWorkflowDTO definition,
            String portId,
            String materialNodeId,
            String materialKind,
            String skuId,
            int ordinal,
            boolean addMaterial) {
        duplicatePort(definition, portId, materialNodeId, materialKind, ordinal);
        if (addMaterial) {
            addMaterial(definition, materialNodeId, materialKind, skuId);
        }
        definition.getEdges().add(new ProductProcessWorkflowDTO.Edge(
                "edge:" + portId, "process:packing", portId, materialNodeId, "input"));
    }

    private void addMaterial(
            ProductProcessWorkflowDTO definition,
            String id,
            String kind,
            String skuId) {
        definition.getNodes().add(materialNode(id, kind, id, skuId));
    }

    private ProductProcessWorkflowDTO.Node materialNode(
            String id,
            String kind,
            String name,
            String skuId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("skuId", skuId);
        return new ProductProcessWorkflowDTO.Node(
                id, kind, new ProductProcessWorkflowDTO.Position(0D, 0D), data);
    }

    private void stubProcessAndProducts(
            String workProcessId,
            WorkProcessOutputMaterialKind kind,
            ProductType... products) {
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of(workProcessId)))
                .thenReturn(List.of(workProcess(workProcessId, kind)));
        List<String> productIds = java.util.Arrays.stream(products).map(ProductType::getId).toList();
        lenient().when(productTypeRepository.findByIdIn(productIds)).thenReturn(List.of(products));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outputPort(ProductProcessWorkflowDTO definition) {
        return (Map<String, Object>) ((List<?>) definition.getNodes().getFirst()
                .getData().get("ports")).getFirst();
    }

    private WorkProcess workProcess(String id, WorkProcessOutputMaterialKind outputKind) {
        WorkProcess process = new WorkProcess();
        process.setId(id);
        process.setFactoryId(FACTORY_ID);
        process.setProcessName("Packing");
        process.setDefaultOutputMaterialKind(outputKind);
        return process;
    }

    private ProductType product(String id, String factoryId, String category) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId(factoryId);
        product.setProductCategory(category);
        return product;
    }

    private void assertCatalogMismatch(BusinessException error, String cellName) {
        assertEquals("PRODUCT_PROCESS_WORKFLOW_CATALOG_MISMATCH", error.getErrorCode());
        assertTrue(error.getMessage().contains(cellName));
        assertTrue(error.getActionHint().contains(cellName));
    }
}
