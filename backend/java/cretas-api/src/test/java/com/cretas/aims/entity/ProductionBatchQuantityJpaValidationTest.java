package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.ProductionBatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Hibernate/JPA Context 门禁：库存生产的批次目标量 0 表示开放数量，必须可持久化。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("ProductionBatch 开放数量 JPA 校验")
class ProductionBatchQuantityJpaValidationTest {

    @Autowired
    private ProductionBatchRepository productionBatchRepository;

    @Test
    @DisplayName("quantity=0 可保存并回读")
    void zeroTargetQuantity_persistsAndLoads() {
        ProductionBatch batch = ProductionBatch.builder()
                .factoryId("F-JPA-OPEN-QTY")
                .batchNumber("PB-JPA-OPEN-QTY-001")
                .productTypeId("PT-JPA-OPEN-QTY")
                .productName("开放数量测试产品")
                .plannedQuantity(BigDecimal.ZERO)
                .quantity(BigDecimal.ZERO)
                .unit("bag")
                .status(ProductionBatchStatus.IN_PROGRESS)
                .build();

        ProductionBatch saved = productionBatchRepository.saveAndFlush(batch);

        assertThat(saved.getId()).isNotNull();
        assertThat(productionBatchRepository.findById(saved.getId()))
                .get()
                .extracting(ProductionBatch::getQuantity)
                .isEqualTo(BigDecimal.ZERO);
    }
}
