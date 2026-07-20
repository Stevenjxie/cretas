package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.supplier.SupplierMaterialDTO;
import com.cretas.aims.dto.supplier.SupplierMaterialRequest;

import java.util.List;

public interface SupplierMaterialService {
    List<SupplierMaterialDTO> listBySupplier(String factoryId, String supplierId);
    List<SupplierMaterialDTO> listByMaterial(String factoryId, String materialTypeId);
    SupplierMaterialDTO create(String factoryId, String supplierId, SupplierMaterialRequest request);
    SupplierMaterialDTO update(String factoryId, String supplierId, String relationId, SupplierMaterialRequest request);
    SupplierMaterialDTO deactivate(String factoryId, String supplierId, String relationId, Long version);
}
