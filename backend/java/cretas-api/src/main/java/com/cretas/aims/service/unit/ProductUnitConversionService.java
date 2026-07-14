package com.cretas.aims.service.unit;

import com.cretas.aims.dto.unit.ProductUnitConversionDTO;

import java.util.List;

public interface ProductUnitConversionService {

    List<ProductUnitConversionDTO> list(String factoryId, String productTypeId);

    ProductUnitConversionDTO create(String factoryId, String productTypeId, ProductUnitConversionDTO request);

    ProductUnitConversionDTO update(
            String factoryId, String productTypeId, String id, ProductUnitConversionDTO request);

    void delete(String factoryId, String productTypeId, String id, Long version);
}
