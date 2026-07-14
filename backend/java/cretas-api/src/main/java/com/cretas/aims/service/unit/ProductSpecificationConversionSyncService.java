package com.cretas.aims.service.unit;

import com.cretas.aims.entity.ProductType;

/** Projects SKU specification fields into explicit, versioned unit conversions. */
public interface ProductSpecificationConversionSyncService {

    /**
     * Synchronize the current net-content and packaging specifications.
     *
     * @return true when an explicit conversion row was created, updated or retired
     */
    boolean synchronize(ProductType product);
}
