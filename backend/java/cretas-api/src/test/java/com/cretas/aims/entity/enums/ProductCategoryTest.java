package com.cretas.aims.entity.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductCategoryTest {

    @Test
    void semiFinishedIsManagedAsSkuButNotOfferedForSale() {
        assertThat(ProductCategory.isSku(ProductCategory.SEMI_FINISHED)).isTrue();
        assertThat(ProductCategory.isSellable(ProductCategory.SEMI_FINISHED)).isFalse();
    }
}
