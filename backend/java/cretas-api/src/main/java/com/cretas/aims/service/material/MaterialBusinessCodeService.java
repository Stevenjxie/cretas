package com.cretas.aims.service.material;

/** Allocates immutable, factory-scoped human-readable material business codes. */
public interface MaterialBusinessCodeService {

    /**
     * Allocate the next code for the most specific configured ancestor of the supplied
     * classification segment. The caller should persist the returned code in the same transaction.
     */
    String allocateBusinessCode(String factoryId, String classificationSegmentCode);
}
