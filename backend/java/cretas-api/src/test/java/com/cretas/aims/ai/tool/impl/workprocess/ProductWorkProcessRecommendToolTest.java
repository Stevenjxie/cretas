package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    @DisplayName("无同类产品历史工序时返回 NO_HISTORY emptyResult，recommendations 为空，notice 不为空")
    void noSimilarProductsReturnsNoHistoryEmptyResult() {
        ProductTypeRepository productTypeRepository = mock(ProductTypeRepository.class);
        ProductWorkProcessRepository productWorkProcessRepository = mock(ProductWorkProcessRepository.class);
        WorkProcessRepository workProcessRepository = mock(WorkProcessRepository.class);

        ProductType target = product("target-product", "新品猪蹄", "FINISHED_PRODUCT", "冷藏");
        // 只有 target 本身，无同类历史产品
        when(productTypeRepository.findByIdAndFactoryId(TARGET_ID, FACTORY_ID))
                .thenReturn(java.util.Optional.of(target));
        when(productTypeRepository.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(target));
        // LLM 客户端传 null → LLM_UNAVAILABLE 路径
        ProductWorkProcessRecommendTool tool = new ProductWorkProcessRecommendTool(
                productTypeRepository,
                productWorkProcessRepository,
                workProcessRepository,
                null,
                new ObjectMapper()
        );

        ProductWorkProcessRecommendTool.RecommendationResult result =
                tool.recommend(FACTORY_ID, TARGET_ID, 5);

        // 无历史 + 无 LLM → LLM_UNAVAILABLE emptyResult
        assertEquals("LLM_UNAVAILABLE", result.source());
        assertTrue(result.recommendations().isEmpty(), "无历史时 recommendations 应为空");
        assertFalse(result.notice().isBlank(), "notice 不应为空字符串");
        assertTrue(result.message().contains("LLM") || result.message().contains("没有"),
                "message 应说明无历史/LLM 不可用，实际为: " + result.message());
    }

    @Test
    @DisplayName("有 LLM 客户端但 LLM 返回的工序名在 catalog 中找不到时，返回 LLM_NO_MATCH emptyResult")
    void llmSuggestsNamesNotInCatalogReturnsLlmNoMatchEmptyResult() {
        ProductTypeRepository productTypeRepository = mock(ProductTypeRepository.class);
        ProductWorkProcessRepository productWorkProcessRepository = mock(ProductWorkProcessRepository.class);
        WorkProcessRepository workProcessRepository = mock(WorkProcessRepository.class);
        PythonLLMClient pythonLLMClient = mock(PythonLLMClient.class);

        ProductType target = product("target-product", "新品鸭货", "FINISHED_PRODUCT", "冷冻");
        // 无同类历史，走 LLM 路径
        when(productTypeRepository.findByIdAndFactoryId(TARGET_ID, FACTORY_ID))
                .thenReturn(java.util.Optional.of(target));
        when(productTypeRepository.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(target));

        // Catalog 只有"腌制"和"包装"
        WorkProcess catalogProcess1 = workProcess("wp-marinate", "腌制");
        WorkProcess catalogProcess2 = workProcess("wp-pack", "包装");
        when(workProcessRepository.findByFactoryIdAndIsActiveTrueOrderBySortOrderAsc(FACTORY_ID))
                .thenReturn(List.of(catalogProcess1, catalogProcess2));

        // LLM 建议"切割"和"蒸制" — catalog 中均不存在
        ChatCompletionResponse llmResponse = mock(ChatCompletionResponse.class);
        when(llmResponse.hasError()).thenReturn(false);
        when(llmResponse.getContent()).thenReturn("[{\"processName\":\"切割\"},{\"processName\":\"蒸制\"}]");
        when(pythonLLMClient.chatCompletion(any())).thenReturn(llmResponse);

        ProductWorkProcessRecommendTool tool = new ProductWorkProcessRecommendTool(
                productTypeRepository,
                productWorkProcessRepository,
                workProcessRepository,
                pythonLLMClient,
                new ObjectMapper()
        );

        ProductWorkProcessRecommendTool.RecommendationResult result =
                tool.recommend(FACTORY_ID, TARGET_ID, 5);

        assertEquals("LLM_NO_MATCH", result.source());
        assertTrue(result.recommendations().isEmpty(), "LLM 名不在 catalog 时 recommendations 应为空");
        assertFalse(result.notice().isBlank(), "notice 不应为空");
        assertTrue(result.message().contains("catalog") || result.message().contains("命中"),
                "message 应提示未命中 catalog，实际为: " + result.message());
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
