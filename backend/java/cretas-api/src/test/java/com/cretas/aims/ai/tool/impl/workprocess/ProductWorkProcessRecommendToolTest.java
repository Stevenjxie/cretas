package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ProductWorkProcessRecommendTool 推荐打分")
class ProductWorkProcessRecommendToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String TARGET_ID = "target-product";

    @Test
    @DisplayName("同产品大类历史工序按频次打分取 Top，并按平均工序顺序输出草稿链")
    void recommendsBySameProductCategoryFrequencyAndAverageOrder() {
        ProductTypeRepository productTypeRepository = mock(ProductTypeRepository.class);
        ProductWorkProcessRepository productWorkProcessRepository = mock(ProductWorkProcessRepository.class);
        WorkProcessRepository workProcessRepository = mock(WorkProcessRepository.class);

        ProductType target = product("target-product", "新卤猪舌", "FINISHED_PRODUCT", "冷藏");
        ProductType similarA = product("similar-a", "卤猪蹄", "FINISHED_PRODUCT", "冷藏");
        ProductType similarB = product("similar-b", "卤牛腱", "FINISHED_PRODUCT", "冷冻");
        ProductType similarC = product("similar-c", "酱猪耳", "FINISHED_PRODUCT", "冷藏");
        ProductType differentCategory = product("raw-a", "冻猪舌", "RAW_MATERIAL", "冷冻");

        when(productTypeRepository.findByIdAndFactoryId(TARGET_ID, FACTORY_ID))
                .thenReturn(java.util.Optional.of(target));
        when(productTypeRepository.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(target, similarA, similarB, similarC, differentCategory));

        when(productWorkProcessRepository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, "similar-a"))
                .thenReturn(List.of(binding("cut", 1), binding("cook", 2), binding("pack", 3)));
        when(productWorkProcessRepository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, "similar-b"))
                .thenReturn(List.of(binding("cut", 1), binding("cook", 2), binding("pack", 3)));
        when(productWorkProcessRepository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, "similar-c"))
                .thenReturn(List.of(binding("cut", 1), binding("pickle", 2), binding("pack", 3)));
        when(productWorkProcessRepository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, "raw-a"))
                .thenReturn(List.of(binding("raw-only", 1), binding("pack", 2)));

        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY_ID, List.of("cut", "cook", "pack")))
                .thenReturn(List.of(
                        workProcess("cut", "修整"),
                        workProcess("cook", "卤制"),
                        workProcess("pack", "包装")
                ));

        ProductWorkProcessRecommendTool tool = new ProductWorkProcessRecommendTool(
                productTypeRepository,
                productWorkProcessRepository,
                workProcessRepository,
                null,
                new ObjectMapper()
        );

        ProductWorkProcessRecommendTool.RecommendationResult result =
                tool.recommend(FACTORY_ID, TARGET_ID, 3);

        assertEquals("HISTORY", result.source());
        assertEquals("AI 建议，请核对", result.notice());
        assertFalse(result.recommendations().isEmpty());
        assertEquals(List.of("cut", "cook", "pack"),
                result.recommendations().stream()
                        .map(ProductWorkProcessRecommendTool.RecommendedProcess::workProcessId)
                        .toList());
        assertEquals(List.of(3, 2, 3),
                result.recommendations().stream()
                        .map(ProductWorkProcessRecommendTool.RecommendedProcess::score)
                        .toList());
    }

    private static ProductType product(String id, String name, String productCategory, String temperatureZone) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId(FACTORY_ID);
        product.setName(name);
        product.setProductCategory(productCategory);
        product.setTemperatureZone(temperatureZone);
        product.setUnit("kg");
        product.setIsActive(true);
        return product;
    }

    private static ProductWorkProcess binding(String workProcessId, int order) {
        return ProductWorkProcess.builder()
                .factoryId(FACTORY_ID)
                .productTypeId("unused")
                .workProcessId(workProcessId)
                .processOrder(order)
                .isActive(true)
                .build();
    }

    private static WorkProcess workProcess(String id, String name) {
        return WorkProcess.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .processName(name)
                .processCategory("生产")
                .unit("kg")
                .isActive(true)
                .build();
    }
}
