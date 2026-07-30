package com.cretas.aims.service.impl;

import com.cretas.aims.dto.supplier.CreateSupplierRequest;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.dto.supplier.UpdateSupplierRequest;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.SupplierMapper;
import com.cretas.aims.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 供应商简称与名称、税号一样，在同一工厂内保存前给出可行动的 409。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierShortNameUniquenessTest {

    private static final String FACTORY = "F006";

    @Mock private SupplierRepository supplierRepository;
    @Mock private SupplierMapper supplierMapper;
    @InjectMocks private SupplierServiceImpl service;

    private Supplier existing;

    @BeforeEach
    void setUp() {
        existing = new Supplier();
        existing.setId("SUP-EXISTING");
        existing.setFactoryId(FACTORY);
        existing.setName("青岛远洋水产有限公司");
        existing.setShortName("Ocean");
        existing.setTaxNumber("91370200EXISTING");
        existing.setContactPerson("王经理");
        existing.setPhone("13800138000");
        existing.setAddress("山东省青岛市市南区香港中路 1 号");

        when(supplierRepository.findByFactoryId(FACTORY)).thenReturn(List.of(existing));
        when(supplierRepository.findByIdAndFactoryId(existing.getId(), FACTORY))
                .thenReturn(Optional.of(existing));
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(supplierMapper.toEntity(any(), any(), any())).thenAnswer(invocation -> {
            CreateSupplierRequest request = invocation.getArgument(0);
            Supplier supplier = new Supplier();
            supplier.setId("SUP-NEW");
            supplier.setFactoryId(FACTORY);
            supplier.setName(request.getName());
            supplier.setShortName(request.getShortName());
            return supplier;
        });
        when(supplierMapper.toDTO(any(Supplier.class))).thenAnswer(invocation -> {
            Supplier supplier = invocation.getArgument(0);
            SupplierDTO dto = new SupplierDTO();
            dto.setId(supplier.getId());
            dto.setName(supplier.getName());
            dto.setShortName(supplier.getShortName());
            return dto;
        });
    }

    @Test
    void duplicateShortNameBlocksBeforeSaveWithActionable409() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createSupplier(FACTORY, request("大连北纬水产", "Ocean", null), 1L));

        assertEquals(409, error.getCode());
        assertEquals("供应商简称已存在", error.getMessage());
        assertEquals("shortName", error.getHintTarget());
        assertTrue(error.getActionHint().contains("请修改简称后再保存"));
        assertTrue(error.getActionHint().contains(existing.getName()));
        verify(supplierRepository, never()).save(any());
    }

    @Test
    void shortNameCollisionIsCaseInsensitiveAndTrimsRequestBoundary() {
        assertThrows(BusinessException.class,
                () -> service.createSupplier(FACTORY, request("大连北纬水产", "  OCEAN  ", null), 1L));
        verify(supplierRepository, never()).save(any());
    }

    @Test
    void uniqueShortNameStillSaves() {
        SupplierDTO created = assertDoesNotThrow(
                () -> service.createSupplier(FACTORY, request("大连北纬水产", "North", null), 1L));
        assertEquals("North", created.getShortName());
        verify(supplierRepository).save(any(Supplier.class));
    }

    @Test
    void updatingSupplierMayKeepItsOwnShortName() {
        UpdateSupplierRequest request = new UpdateSupplierRequest();
        request.setShortName("OCEAN");

        assertDoesNotThrow(() -> service.updateSupplier(FACTORY, existing.getId(), request));
        verify(supplierRepository).save(existing);
    }

    @Test
    void duplicateNameAndTaxNumberRemainHard409s() {
        BusinessException nameError = assertThrows(BusinessException.class,
                () -> service.createSupplier(
                        FACTORY, request(existing.getName(), "Different", null), 1L));
        assertEquals(409, nameError.getCode());
        assertEquals("name", nameError.getHintTarget());

        BusinessException taxError = assertThrows(BusinessException.class,
                () -> service.createSupplier(
                        FACTORY, request("另一家供应商", "Different", existing.getTaxNumber()), 1L));
        assertEquals(409, taxError.getCode());
        assertEquals("taxNumber", taxError.getHintTarget());
    }

    private CreateSupplierRequest request(String name, String shortName, String taxNumber) {
        CreateSupplierRequest request = new CreateSupplierRequest();
        request.setName(name);
        request.setShortName(shortName);
        request.setTaxNumber(taxNumber);
        request.setContactPerson("李经理");
        request.setPhone("13900139000");
        request.setAddress("辽宁省大连市中山区人民路 1 号");
        return request;
    }
}
