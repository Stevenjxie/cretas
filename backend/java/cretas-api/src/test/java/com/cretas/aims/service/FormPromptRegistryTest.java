package com.cretas.aims.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表单 prompt 注册表回归。
 *
 * <p>这些 prompt 是从 web-admin 的 {@code ai-entry/types.ts} 搬过来的，搬运时
 * 剥掉了原来的 {@code FILL_FORM} 输出段（会和后端的 field_values 契约打架），
 * 只保留域知识。下面的断言盯住两件事：**7 个实体一个都不能丢**，以及
 * **防呆规则不能在后续编辑中被顺手删掉** —— 那些规则不是措辞，是硬需求。
 */
class FormPromptRegistryTest {

    private final FormPromptRegistry registry = new FormPromptRegistry();

    private static final List<String> ALL_ENTITIES = List.of(
            "PRODUCTION_PLAN", "PRODUCT", "PURCHASE_ORDER",
            "SALES_ORDER", "STOCKTAKING", "WH_INBOUND", "PROCESS_TASK");

    @Test
    @DisplayName("7 个工厂侧实体都能查到专属 prompt")
    void everyFactoryEntityHasAPrompt() {
        for (String entity : ALL_ENTITIES) {
            Optional<String> p = registry.promptFor("factory", entity);
            assertTrue(p.isPresent(), entity + " 缺少 prompt 资源文件");
            assertTrue(p.get().length() > 100, entity + " 的 prompt 短得可疑");
        }
    }

    @Test
    @DisplayName("生产计划的防呆规则必须保留 — 它决定 SKU 能否唯一匹配")
    void productionPlanKeepsItsAntiHallucinationRules() {
        String p = registry.promptFor("factory", "PRODUCTION_PLAN").orElseThrow();
        // SKU 名逐字保留: 页面随后拿这个名字去跟真实产品表做唯一匹配,
        // 被缩写/归一化就会匹配失败, 或更糟 —— 匹配到另一个产品。
        assertTrue(p.contains("逐字保留"), "SKU 逐字保留规则丢失");
        assertTrue(p.contains("禁止缩写"), "禁止缩写规则丢失");
        // 不许编造产品清单
        assertTrue(p.contains("不要假设"), "不要假设产品清单的规则丢失");
        assertTrue(p.contains("不要列举编造的产品例子"), "禁止编造产品例子的规则丢失");
    }

    @Test
    @DisplayName("含明细数组的实体要保留 items 子字段说明")
    void lineItemEntitiesKeepNestedFieldDocs() {
        for (String entity : List.of("PURCHASE_ORDER", "SALES_ORDER", "WH_INBOUND")) {
            String p = registry.promptFor("factory", entity).orElseThrow();
            assertTrue(p.contains("items"), entity + " 丢了 items 明细结构说明");
        }
    }

    @Test
    @DisplayName("搬运时剥掉的 FILL_FORM 输出契约不能回流")
    void portedPromptsCarryNoOutputContract() {
        for (String entity : ALL_ENTITIES) {
            String p = registry.promptFor("factory", entity).orElseThrow();
            assertFalse(p.contains("FILL_FORM"),
                    entity + " 残留 FILL_FORM —— 会和后端 field_values 契约打架");
        }
    }

    @Test
    @DisplayName("{{currentFactoryDate}} 被替换成上海时区的今天")
    void factoryDatePlaceholderIsSubstituted() {
        String p = registry.promptFor("factory", "PRODUCTION_PLAN").orElseThrow();
        assertFalse(p.contains("{{currentFactoryDate}}"), "模板变量没被替换");
        assertTrue(p.contains(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()),
                "prompt 里没出现工厂当前日期");
    }

    @Test
    @DisplayName("未登记的实体回退通用 prompt, 不抛异常")
    void unknownEntityFallsBackQuietly() {
        assertTrue(registry.promptFor("factory", "NOT_REGISTERED_YET").isEmpty());
        assertTrue(registry.promptFor("restaurant", "PRODUCTION_PLAN").isEmpty());
    }

    @Test
    @DisplayName("entityType 来自请求体 — 必须挡住路径穿越")
    void rejectsPathTraversalKeys() {
        assertTrue(registry.promptFor("factory", "../../application").isEmpty());
        assertTrue(registry.promptFor("../factory", "PRODUCTION_PLAN").isEmpty());
        assertTrue(registry.promptFor("factory", null).isEmpty());
        assertTrue(registry.promptFor(null, "PRODUCTION_PLAN").isEmpty());
    }

    @Test
    @DisplayName("大小写归一 — 前端传小写也能查到")
    void keysAreCaseInsensitive() {
        assertEquals(registry.promptFor("factory", "PRODUCTION_PLAN"),
                registry.promptFor("FACTORY", "production_plan"));
    }
}
