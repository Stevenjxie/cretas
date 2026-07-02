package com.cretas.aims.util;

import com.cretas.aims.entity.enums.FactoryType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDomainUtilsTest {
    @Test
    void mapsRestaurantAndBranchToRestaurantDomain() {
        assertThat(BusinessDomainUtils.resolveDomain(FactoryType.RESTAURANT)).isEqualTo("RESTAURANT");
        assertThat(BusinessDomainUtils.resolveDomain(FactoryType.BRANCH)).isEqualTo("RESTAURANT");
        assertThat(BusinessDomainUtils.resolveDomain("branch")).isEqualTo("RESTAURANT");
        assertThat(BusinessDomainUtils.isRestaurantDomain(FactoryType.BRANCH)).isTrue();
    }

    @Test
    void mapsProductionLikeOrUnknownTypesToFactoryDomain() {
        assertThat(BusinessDomainUtils.resolveDomain(FactoryType.FACTORY)).isEqualTo("FACTORY");
        assertThat(BusinessDomainUtils.resolveDomain(FactoryType.CENTRAL_KITCHEN)).isEqualTo("FACTORY");
        assertThat(BusinessDomainUtils.resolveDomain(FactoryType.HEADQUARTERS)).isEqualTo("FACTORY");
        assertThat(BusinessDomainUtils.resolveDomain((FactoryType) null)).isEqualTo("FACTORY");
    }
}
