package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.supplier.SupplierMaterialPurchaseSpecDTO;
import com.cretas.aims.dto.supplier.SupplierMaterialPurchaseSpecRequest;
import java.util.List;

public interface SupplierMaterialPurchaseSpecService {
    List<SupplierMaterialPurchaseSpecDTO> list(String factoryId, String supplierId, String relationId);
    SupplierMaterialPurchaseSpecDTO create(String factoryId, String supplierId, String relationId, SupplierMaterialPurchaseSpecRequest request);
    SupplierMaterialPurchaseSpecDTO update(String factoryId, String supplierId, String relationId, String specId, SupplierMaterialPurchaseSpecRequest request);
    SupplierMaterialPurchaseSpecDTO deactivate(String factoryId, String supplierId, String relationId, String specId, Long version);
}
