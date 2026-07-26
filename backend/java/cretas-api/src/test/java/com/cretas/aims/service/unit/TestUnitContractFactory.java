package com.cretas.aims.service.unit;

import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.UnitConversionService;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;

import static org.mockito.Mockito.mock;

/** Test fixture that uses the same canonical conversion engine as production. */
public final class TestUnitContractFactory {

    private TestUnitContractFactory() {
    }

    public static UnitContractService contract() {
        return new UnitContractServiceImpl(
                mock(UnitOfMeasurementRepository.class),
                mock(ProductUnitConversionRepository.class),
                mock(MaterialPackagingHierarchyRepository.class));
    }

    public static UnitConversionService legacyFacade() {
        return new UnitConversionService(contract());
    }
}
