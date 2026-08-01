package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OA 待办列表的「业务类型」中文名覆盖契约。
 *
 * <p>客户截图: 待办里一条显示成「未知状态（BUDGET）」。查下来<b>不是漏了 BUDGET 一个码</b> ——
 * 权威表 {@link DecisionTypeMetadataRegistry} 有 30+ 个 moduleCode 且各自带 chineseName,
 * 而前端 {@code pending.vue} 的 MODULE_LABELS <b>手抄了其中 4 个</b>
 * (PURCHASE_ORDER / SALES_ORDER / INVENTORY_TRANSFER / INVENTORY_ADJUSTMENT)。
 * 也就是说另外 20 多个码<b>同样会显示成「未知状态（X）」</b>, 只是还没人点到。
 *
 * <p>本契约锁住「权威表自身完整」这一半; 另一半(前端优先用后端下发的 moduleLabel)
 * 由 web-admin 侧的 source spec 锁。
 */
class ModuleLabelCoverageTest {

    private DecisionTypeMetadataRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DecisionTypeMetadataRegistry();
        registry.init();
    }

    @Test
    @DisplayName("权威表里每个 moduleCode 都有非空中文名")
    void everyModuleCodeHasChineseName() {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<DecisionType, DecisionTypeMetadata> entry : registry.getAll().entrySet()) {
            DecisionTypeMetadata metadata = entry.getValue();
            if (metadata.getModuleCode() == null || metadata.getModuleCode().isBlank()) {
                continue;
            }
            String name = metadata.getChineseName();
            if (name == null || name.isBlank() || name.contains("未知")) {
                offenders.add(metadata.getModuleCode() + " -> " + name);
            }
        }
        assertThat(offenders)
                .as("这些 moduleCode 没有可用的中文名, 待办列表会显示成「未知状态（X）」")
                .isEmpty();
    }

    @Test
    @DisplayName("BUDGET 能反查到 DecisionType 且拿得到中文名")
    void budgetResolvesToChineseName() {
        DecisionType decisionType = registry.lookupByModuleCode("BUDGET");
        assertThat(decisionType)
                .as("BUDGET 必须能反查到 DecisionType, 否则 moduleLabel 无从解析")
                .isNotNull();
        assertThat(registry.get(decisionType).getChineseName()).isNotBlank();
    }

    @Test
    @DisplayName("前端手抄的那 4 个码只是权威表的一小部分 —— 记录差距, 防止有人把前端表当事实来源")
    void frontendHardcodedSubsetIsMuchSmallerThanAuthority() {
        long authorityCount = registry.getAll().values().stream()
                .map(DecisionTypeMetadata::getModuleCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .count();
        assertThat(authorityCount)
                .as("权威表 moduleCode 数应远多于前端手抄的 4 个 —— "
                        + "这正是「未知状态（BUDGET）」的成因: 前端维护了第二份且只覆盖一小部分")
                .isGreaterThan(4L);
    }

    @Test
    @DisplayName("未知 moduleCode 反查返回 null —— 解析方据此走兜底而不是编造")
    void unknownModuleCodeReturnsNull() {
        assertThat(registry.lookupByModuleCode("NOT_A_REAL_MODULE")).isNull();
        assertThat(registry.lookupByModuleCode(null)).isNull();
        assertThat(registry.lookupByModuleCode("  ")).isNull();
    }
}
