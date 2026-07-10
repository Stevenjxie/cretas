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
