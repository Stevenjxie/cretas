package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestaurantDishDeleteToolTest {

    @Test
    void previewShowsBeforeAfterWithoutMutatingDish() {
        ProductTypeRepository repository = mock(ProductTypeRepository.class);
        ProductType dish = new ProductType();
        dish.setId("dish-1");
        dish.setName("卤炸牛肉串");
        dish.setIsActive(true);
        when(repository.findByFactoryIdAndName("DEMO_REST", "卤炸牛肉串"))
                .thenReturn(Optional.of(dish));

        RestaurantDishDeleteTool tool = new RestaurantDishDeleteTool();
        ReflectionTestUtils.setField(tool, "productTypeRepository", repository);

        Map<String, Object> preview = ReflectionTestUtils.invokeMethod(
                tool,
                "doPreview",
                "DEMO_REST",
                Map.of("name", "卤炸牛肉串"),
                Map.of("factoryId", "DEMO_REST", "userId", 7L));

        assertThat(preview).isNotNull();
        assertThat(preview.get("status")).isEqualTo("PREVIEW");
        Map<?, ?> currentValues = (Map<?, ?>) preview.get("currentValues");
        Map<?, ?> newValues = (Map<?, ?>) preview.get("newValues");
        assertThat(currentValues.get("菜品")).isEqualTo("卤炸牛肉串");
        assertThat(currentValues.get("状态")).isEqualTo("在售");
        assertThat(newValues.get("状态")).isEqualTo("已下架");
        assertThat(dish.getIsActive()).isTrue();
        verify(repository, never()).save(dish);
    }
}
