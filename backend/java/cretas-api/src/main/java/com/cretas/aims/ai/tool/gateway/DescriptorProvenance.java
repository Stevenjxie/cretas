package com.cretas.aims.ai.tool.gateway;

/**
 * How governance metadata entered the descriptor.
 *
 * <p>Validation does not promote an external descriptor to trusted metadata. Callers must retain
 * and evaluate this provenance when the gateway is implemented.</p>
 */
public enum DescriptorProvenance {
    EXPLICIT,
    LEGACY_INFERRED,
    EXTERNAL_UNTRUSTED
}
