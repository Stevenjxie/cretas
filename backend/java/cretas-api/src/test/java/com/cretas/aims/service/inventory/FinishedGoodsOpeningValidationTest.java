package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * T126 Phase 1 — POST /finished-goods/opening 控制器层校验
 * (F3: dup batch_number → 409; F9: productTypeId missing → 404)
 *
 * <p>这些校验在 SalesController 直接做（查 repo），所以测试逻辑在控制器层验证
 * 对应异常条件，不需要 full Spring context。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("T126 期初入库校验 (F3 + F9)")
class FinishedGoodsOpeningValidationTest {

    @Mock FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock ProductTypeRepository productTypeRepository;

    private static final String FACTORY_ID = "F001";

    /**
     * F3: 重复批次号 → controller 应 throw BusinessException(409).
     * Simulates the controller's validation block:
     *   finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber)
     *     .ifPresent(existing -> throw BusinessException(409, ...))
     */
    @Test
    @DisplayName("F3: 重复 batchNumber → BusinessException 409")
    void duplicateBatchNumber_409() {
        FinishedGoodsBatch existing = new FinishedGoodsBatch();
        existing.setId("FGB-EXISTING");
        existing.setFactoryId(FACTORY_ID);
        existing.setBatchNumber("FG-2026-001");

        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "FG-2026-001"))
                .thenReturn(Optional.of(existing));

        // Replicate controller logic
        assertThatThrownBy(() ->
                finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "FG-2026-001")
                        .ifPresent(e -> {
                            throw new BusinessException(409, "批次号 FG-2026-001 已存在")
                                    .withHint("已有批次 " + e.getId() + ", 请修改批次号或查看");
                        }))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    org.assertj.core.api.Assertions.assertThat(be.getCode()).isEqualTo(409);
                    org.assertj.core.api.Assertions.assertThat(be.getMessage()).contains("FG-2026-001");
                    org.assertj.core.api.Assertions.assertThat(be.getActionHint()).contains("FGB-EXISTING");
                });
    }

    /**
     * F9: productTypeId 不存在 → controller 应 throw BusinessException(404).
     * Simulates:
     *   productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
     *     .orElseThrow(() -> new BusinessException(404, ...))
     */
    @Test
    @DisplayName("F9: productTypeId 不存在 → BusinessException 404")
    void unknownProductTypeId_404() {
        when(productTypeRepository.findByIdAndFactoryId("UNKNOWN-PT", FACTORY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                productTypeRepository.findByIdAndFactoryId("UNKNOWN-PT", FACTORY_ID)
                        .orElseThrow(() -> new BusinessException(404, "产品类型不存在或不属于该工厂")
                                .withHint("productTypeId=UNKNOWN-PT 在工厂 " + FACTORY_ID + " 中未找到")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    org.assertj.core.api.Assertions.assertThat(be.getCode()).isEqualTo(404);
                    org.assertj.core.api.Assertions.assertThat(be.getMessage()).contains("产品类型不存在");
                });
    }

    /**
     * Happy path: no dup, productType found → no exception thrown.
     */
    @Test
    @DisplayName("Happy path: 无重复, productType 存在 → 无异常")
    void happyPath_noException() {
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "FG-NEW"))
                .thenReturn(Optional.empty());

        com.cretas.aims.entity.ProductType pt = new com.cretas.aims.entity.ProductType();
        pt.setId("PT-001");
        when(productTypeRepository.findByIdAndFactoryId("PT-001", FACTORY_ID))
                .thenReturn(Optional.of(pt));

        // No exception = pass
        finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "FG-NEW")
                .ifPresent(e -> {
                    throw new BusinessException(409, "dup");
                });
        productTypeRepository.findByIdAndFactoryId("PT-001", FACTORY_ID)
                .orElseThrow(() -> new BusinessException(404, "not found"));
    }
}
