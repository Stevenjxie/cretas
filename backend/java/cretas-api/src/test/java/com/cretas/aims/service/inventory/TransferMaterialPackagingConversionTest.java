package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.entity.material.MaterialPackagingSpec;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.repository.material.MaterialPackagingSpecRepository;
import com.cretas.aims.service.inventory.impl.TransferServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferService raw material packaging conversion")
class TransferMaterialPackagingConversionTest {

    private static final String FACTORY_ID = "F006";
    private static final String TARGET_FACTORY_ID = "F007";
    private static final String MATERIAL_ID = "RM-BEEF";
    private static final String PACKAGING_SPEC_ID = "SPEC-CASE-10KG";

    @Mock private InternalTransferRepository transferRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private MaterialPackagingSpecRepository materialPackagingSpecRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private TransferServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TransferServiceImpl(
                transferRepository, null, null, null,
                applicationEventPublisher, null, rawMaterialTypeRepository);
        ReflectionTestUtils.setField(
                service, "materialPackagingSpecRepository", materialPackagingSpecRepository);

        when(transferRepository.findRecentDuplicates(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(invocation -> {
            InternalTransfer transfer = invocation.getArgument(0);
            if (transfer.getId() == null) {
                transfer.setId("TR-1");
            }
            return transfer;
        });
    }

    @Test
    @DisplayName("8 cases at 10 kg each persist as 80 kg while retaining package snapshot")
    void convertsSelectedPackageToInventoryBaseUnit() {
        RawMaterialType material = new RawMaterialType();
        material.setId(MATERIAL_ID);
        material.setFactoryId(FACTORY_ID);
        material.setName("Beef");
        material.setUnit("kg");
        material.setIsAbacaPackaging(false);

        MaterialPackagingSpec spec = new MaterialPackagingSpec();
        spec.setId(PACKAGING_SPEC_ID);
        spec.setFactoryId(FACTORY_ID);
        spec.setMaterialTypeId(MATERIAL_ID);
        spec.setName("Case 10 kg");
        spec.setPackageUnit("case");
        spec.setBaseUnit("kg");
        spec.setConversionFactor(new BigDecimal("10"));
        spec.setActive(true);

        when(rawMaterialTypeRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
        when(materialPackagingSpecRepository
                .findByIdAndFactoryIdAndMaterialTypeIdAndActiveTrue(
                        PACKAGING_SPEC_ID, FACTORY_ID, MATERIAL_ID))
                .thenReturn(Optional.of(spec));

        CreateTransferRequest.TransferItemDTO item = new CreateTransferRequest.TransferItemDTO();
        item.setItemType("RAW_MATERIAL");
        item.setMaterialTypeId(MATERIAL_ID);
        item.setMaterialPackagingSpecId(PACKAGING_SPEC_ID);
        item.setItemName("Beef");
        item.setQuantity(new BigDecimal("8"));
        item.setUnit("case");
        item.setUnitPrice(new BigDecimal("120"));

        CreateTransferRequest request = new CreateTransferRequest();
        request.setTransferType("BRANCH_TO_BRANCH");
        request.setTargetFactoryId(TARGET_FACTORY_ID);
        request.setTransferDate(LocalDate.now());
        request.setItems(List.of(item));

        InternalTransfer transfer = service.createTransfer(FACTORY_ID, request, 100L);
        InternalTransferItem saved = transfer.getItems().get(0);

        assertDecimalEquals("80", saved.getQuantity());
        assertEquals("kg", saved.getUnit());
        assertEquals(PACKAGING_SPEC_ID, saved.getMaterialPackagingSpecId());
        assertDecimalEquals("8", saved.getPackageQuantitySnapshot());
        assertEquals("case", saved.getPackageUnitSnapshot());
        assertEquals("kg", saved.getInventoryBaseUnitSnapshot());
        assertDecimalEquals("10", saved.getPackageToBaseFactorSnapshot());
        assertDecimalEquals("12", saved.getUnitPrice());
        assertDecimalEquals("960", transfer.getTotalAmount());
    }

    private static void assertDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
