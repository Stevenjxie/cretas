package com.cretas.aims.dto.capability;

/** Explicit no-match response; a missing query match is never replaced with a fabricated pack. */
public record FactoryCapabilityPackMatchResponse(
        boolean matched,
        FactoryCapabilityPackSummary pack) {

    public static FactoryCapabilityPackMatchResponse matched(
            FactoryCapabilityPackSummary pack) {
        return new FactoryCapabilityPackMatchResponse(true, pack);
    }

    public static FactoryCapabilityPackMatchResponse noMatch() {
        return new FactoryCapabilityPackMatchResponse(false, null);
    }
}
