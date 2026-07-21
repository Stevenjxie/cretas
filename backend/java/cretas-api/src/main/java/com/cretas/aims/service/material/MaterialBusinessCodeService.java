package com.cretas.aims.service.material;

/** Allocates immutable, factory-scoped human-readable material business codes. */
public interface MaterialBusinessCodeService {

    /**
     * Resolve the same prefix and next candidate used by allocation without reserving a number or
     * writing configuration. A missing explicit prefix is resolved from the immutable L3 numeric
     * identity, never from a mutable display label.
     */
    BusinessCodePreview previewBusinessCode(String factoryId, String classificationSegmentCode);

    /**
     * Allocate the next code for the most specific configured ancestor of the supplied
     * classification segment. The caller should persist the returned code in the same transaction.
     */
    String allocateBusinessCode(String factoryId, String classificationSegmentCode);

    record BusinessCodePreview(String code,
                               String codePrefix,
                               String prefixSource,
                               String sourceSegmentCode) {
    }
}
