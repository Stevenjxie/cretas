package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.InboundType;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("F4 inbound guard: no source document, no inventory creation")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialBatchServiceImplInboundSourceGuardTest {

    private static final String FACTORY = "F006";
    private static final long USER_ID = 9001L;
    private static final String MATERIAL_ID = "mat-pork";
    private static final String WAREHOUSE_ID = "wh-raw";

    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchMapper materialBatchMapper;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private FuturePlanMatchingService futurePlanMatchingService;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private PurchaseReceiveRecordRepository purchaseReceiveRecordRepository;
    @Mock private FactoryMaterialRequisitionRepository factoryMaterialRequisitionRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;

    private MaterialBatchServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new MaterialBatchServiceImpl(
                materialBatchRepository,
                materialBatchAdjustmentRepository,
                materialTypeRepository,
                materialBatchMapper,
                materialConsumptionRepository,
                productionPlanBatchUsageRepository,
                excelUtil,
                futurePlanMatchingService);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(service, "purchaseReceiveRecordRepository", purchaseReceiveRecordRepository);
        ReflectionTestUtils.setField(service, "factoryMaterialRequisitionRepository", factoryMaterialRequisitionRepository);
        injectSalesOrderRepositoryIfPresent();
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);

        RawMaterialType materialType = new RawMaterialType();
        materialType.setId(MATERIAL_ID);
        materialType.setFactoryId(FACTORY);
        materialType.setName("pork");
        materialType.setUnit("kg");
        when(materialTypeRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(materialType));

        when(materialBatchMapper.toEntity(any(CreateMaterialBatchRequest.class), eq(FACTORY), eq(USER_ID)))
                .thenAnswer(inv -> {
                    CreateMaterialBatchRequest req = inv.getArgument(0);
                    MaterialBatch batch = new MaterialBatch();
                    batch.setFactoryId(FACTORY);
                    batch.setMaterialTypeId(req.getMaterialTypeId());
                    batch.setReceiptQuantity(req.getReceiptQuantity());
                    batch.setQuantityUnit(req.getQuantityUnit());
                    batch.setReceiptDate(req.getReceiptDate());
                    batch.setUnitPrice(BigDecimal.TEN);
                    batch.setWarehouseId(req.getWarehouseId());
                    batch.setSourceDocType(req.getSourceDocType());
                    batch.setSourceDocId(req.getSourceDocId());
                    batch.setCreatedBy(USER_ID);
                    return batch;
                });
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        user = new User();
        user.setId(USER_ID);
        user.setFactoryId(FACTORY);
        user.setRoleCode("warehouse_manager");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("CUSTOMER_MATERIAL_RECEIPT linked to same-factory sales order -> allowed")
    void createBatch_customerMaterialReceiptWithSalesOrder_allowed() {
        SalesOrder order = new SalesOrder();
        order.setId("SO-1");
        order.setFactoryId(FACTORY);
        when(salesOrderRepository.findById("SO-1")).thenReturn(Optional.of(order));
        CreateMaterialBatchRequest request = baseRequest();
        request.setSourceDocType("CUSTOMER_MATERIAL_RECEIPT");
        request.setSourceDocId("SO-1");
        request.setSupplierId("CUSTOMER");
        request.setNotes("customer supplied material for SO-1");

        assertThatCode(() -> service.createMaterialBatch(FACTORY, request, USER_ID))
                .doesNotThrowAnyException();

        ArgumentCaptor<MaterialBatch> captor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).save(captor.capture());
        assertThat(captor.getValue().getSourceDocType()).isEqualTo("CUSTOMER_MATERIAL_RECEIPT");
        assertThat(captor.getValue().getSourceDocId()).isEqualTo("SO-1");
    }

    @Test
    @DisplayName("CUSTOMER_MATERIAL_RECEIPT linked to another factory sales order -> rejected")
    void createBatch_customerMaterialReceiptOtherFactory_rejected() {
        SalesOrder order = new SalesOrder();
        order.setId("SO-OTHER");
        order.setFactoryId("F999");
        when(salesOrderRepository.findById("SO-OTHER")).thenReturn(Optional.of(order));
        CreateMaterialBatchRequest request = baseRequest();
        request.setSourceDocType("CUSTOMER_MATERIAL_RECEIPT");
        request.setSourceDocId("SO-OTHER");
        request.setSupplierId("CUSTOMER");
        request.setNotes("customer supplied material for SO-OTHER");

        assertThatThrownBy(() -> service.createMaterialBatch(FACTORY, request, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(404);
                    assertThat(be.getMessage()).contains("SO-OTHER");
                });
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("sourceDocType missing -> fail closed before inventory batch is saved")
    void createBatch_missingSourceDocType_rejected() {
        CreateMaterialBatchRequest request = baseRequest();

        assertThatThrownBy(() -> service.createMaterialBatch(FACTORY, request, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(400);
                    assertThat(be.getMessage())
                            .contains("创建批次必须指定来源单据(采购入库/退货/盘点),仓库无单不可建库存");
                    assertThat(be.getActionHint())
                            .contains("请从采购入库、退货入库或盘点单发起");
                });
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("PURCHASE_RECEIVE with sourceDocId -> still allowed")
    void createBatch_purchaseReceive_allowed() {
        when(purchaseReceiveRecordRepository.existsById("PRR-1")).thenReturn(true);
        CreateMaterialBatchRequest request = baseRequest();
        request.setSourceDocType("PURCHASE_RECEIVE");
        request.setSourceDocId("PRR-1");

        assertThatCode(() -> service.createMaterialBatch(FACTORY, request, USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("MATERIAL_REQUISITION_RETURN with sourceDocId -> still allowed")
    void createBatch_returnSource_allowed() {
        when(factoryMaterialRequisitionRepository.existsById("RET-1")).thenReturn(true);
        CreateMaterialBatchRequest request = baseRequest();
        request.setSourceDocType("MATERIAL_REQUISITION_RETURN");
        request.setSourceDocId("RET-1");

        assertThatCode(() -> service.createMaterialBatch(FACTORY, request, USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("LEGACY_IMPORT without inventory:legacy_import -> 403")
    void createBatch_legacyImportWithoutPermission_rejected() {
        when(permissionService.hasPermission(user, "inventory:legacy_import")).thenReturn(false);
        CreateMaterialBatchRequest request = baseRequest();
        request.setSourceDocType("LEGACY_IMPORT");
        request.setNotes("migration batch import #20260614");

        assertThatThrownBy(() -> service.createMaterialBatch(FACTORY, request, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(403);
                    assertThat(be.getMessage()).contains("inventory:legacy_import");
                    assertThat(be.getActionHint()).contains("授权");
                });
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("LEGACY_IMPORT with inventory:legacy_import -> allowed and marked as LEGACY_IMPORT")
    void createBatch_legacyImportWithPermission_allowed() {
        when(permissionService.hasPermission(user, "inventory:legacy_import")).thenReturn(true);
        CreateMaterialBatchRequest request = baseRequest();
        request.setSourceDocType("LEGACY_IMPORT");
        request.setNotes("migration batch import #20260614");

        assertThatCode(() -> service.createMaterialBatch(FACTORY, request, USER_ID))
                .doesNotThrowAnyException();

        ArgumentCaptor<MaterialBatch> captor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).save(captor.capture());
        assertThat(captor.getValue().getInboundType()).isEqualTo(InboundType.LEGACY_IMPORT);
    }

    private CreateMaterialBatchRequest baseRequest() {
        CreateMaterialBatchRequest request = new CreateMaterialBatchRequest();
        request.setMaterialTypeId(MATERIAL_ID);
        request.setSupplierId("SUP-1");
        request.setWarehouseId(WAREHOUSE_ID);
        request.setReceiptDate(LocalDate.now());
        request.setReceiptQuantity(BigDecimal.valueOf(100));
        request.setQuantityUnit("kg");
        request.setTotalWeight(BigDecimal.valueOf(100));
        request.setTotalValue(BigDecimal.valueOf(1000));
        return request;
    }

    private void injectSalesOrderRepositoryIfPresent() {
        try {
            ReflectionTestUtils.setField(service, "salesOrderRepository", salesOrderRepository);
        } catch (IllegalArgumentException ignored) {
            // RED phase: production code does not know this dependency yet.
        }
    }
}
