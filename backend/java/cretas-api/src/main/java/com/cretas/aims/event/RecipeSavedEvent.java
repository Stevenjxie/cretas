package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * 餐饮配方保存事件 (#57 成本卡/出菜反推).
 *
 * <p>当餐饮 {@code Recipe} create / update 后发布, 驱动 Python gold 缓存
 * {@code agg_restaurant_product_cost} 对该菜品重算 (best-effort, fail-soft)。
 *
 * @author Cretas Team
 * @since 2026-06-04 (feature #57)
 */
@Getter
public class RecipeSavedEvent extends ApplicationEvent {

    private final String factoryId;
    private final String productTypeId;
    private final String recipeId;
    private final LocalDateTime savedAt;

    public RecipeSavedEvent(Object source, String factoryId, String productTypeId, String recipeId) {
        super(source);
        this.factoryId = factoryId;
        this.productTypeId = productTypeId;
        this.recipeId = recipeId;
        this.savedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return String.format("RecipeSavedEvent[factoryId=%s, productTypeId=%s, recipeId=%s, savedAt=%s]",
                factoryId, productTypeId, recipeId, savedAt);
    }
}
