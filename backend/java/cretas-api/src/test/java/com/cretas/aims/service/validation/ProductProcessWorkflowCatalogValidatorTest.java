package com.cretas.aims.service.validation;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
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

@ExtendWith(MockitoExtension.class)
class ProductProcessWorkflowCatalogValidatorTest {

    private static final String FACTORY_ID = "F001";
    private static final String PRODUCT_ID = "PT-FINISHED";

    @Mock
    private WorkProcessRepository workProcessRepository;

    @Mock
    private ProductTypeRepository productTypeRepository;

    private ProductProcessWorkflowCatalogValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ProductProcessWorkflowCatalogValidator(workProcessRepository, productTypeRepository);
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
