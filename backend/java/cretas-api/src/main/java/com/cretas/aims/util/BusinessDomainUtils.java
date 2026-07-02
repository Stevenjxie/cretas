package com.cretas.aims.util;

import com.cretas.aims.entity.enums.FactoryType;

/**
 * Shared domain normalization for factory-vs-restaurant feature gates.
 *
 * <p>Keep this as the single code-level mapping from organization type to
 * analysis domain. Intent routing, SmartBI dashboards, and frontend capability
 * hints should all consume the same normalized domain instead of comparing raw
 * {@link FactoryType} values independently.
 */
public final class BusinessDomainUtils {
    public static final String FACTORY = "FACTORY";
    public static final String RESTAURANT = "RESTAURANT";

    private BusinessDomainUtils() {
    }

    public static String resolveDomain(FactoryType factoryType) {
        if (factoryType == FactoryType.RESTAURANT || factoryType == FactoryType.BRANCH) {
            return RESTAURANT;
        }
        return FACTORY;
    }

    public static String resolveDomain(String factoryType) {
        if (factoryType == null || factoryType.isBlank()) {
            return FACTORY;
        }
        try {
            return resolveDomain(FactoryType.valueOf(factoryType.trim().toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            return FACTORY;
        }
    }

    public static boolean isRestaurantDomain(FactoryType factoryType) {
        return RESTAURANT.equals(resolveDomain(factoryType));
    }
}
