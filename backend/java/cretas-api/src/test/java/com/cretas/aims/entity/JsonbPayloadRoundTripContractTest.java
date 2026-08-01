package com.cretas.aims.entity;

import com.cretas.aims.dto.orchestration.LineItemMatch;
import com.cretas.aims.entity.inventory.SalesOrderShortageReport;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import io.hypersistence.utils.hibernate.type.util.ObjectMapperWrapper;
import org.hibernate.annotations.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * JSONB 载荷必须能被 Hibernate 原样 deep-copy 回来。
 *
 * <p><b>这测的不是"Jackson 能不能转字符串"，而是一类静默丢写。</b>
 * {@code @Type(JsonBinaryType.class)} 的字段每次 flush 前都会被 Hibernate 做一次
 * deep-copy —— 实现是「序列化成 JSON 再反序列化回来」。只要载荷类里有一个
 * <b>只读属性</b>（有 getter、没有 backing field 也没有 setter），Jackson 会把它
 * 写进 JSON，回读时又因为 {@code FAIL_ON_UNKNOWN_PROPERTIES} 找不到落点而抛
 * {@code UnrecognizedPropertyException}。
 *
 * <p>致命之处在于失败发生在 <b>flush 阶段</b>：SQL 根本没发出去，事务整个回滚，
 * 而调用方只看到一条 "fan-out failed" 日志。{@code sales_order_shortage_report}
 * 这张表建表至今 0 行、{@code PP-AUTO-*} 停在 2026-04-15，77 次丢写里有 76 次
 * 是这个成因（{@code LineItemMatch.isFullySatisfied()}）。
 *
 * <p>两层判据：
 * <ol>
 *   <li><b>忠实复现</b> — 用 {@code ObjectMapperWrapper.INSTANCE}（Hibernate 可变类型
 *       deep-copy 实际调用的那个），而不是自己 new 的 ObjectMapper —— 后者
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES} 配置可能不同，根本红不了。
 *       写路（{@code clone}）与读路（按实体声明的泛型 {@code fromString}）各测一条。</li>
 *   <li><b>同类扫描</b> — 风险是"下一个新 DTO 又加了个计算 getter"，所以扫全部
 *       JSONB 列的载荷类型，静态断言没有只读属性。加一个新的计算 getter 会当场变红。</li>
 * </ol>
 */
class JsonbPayloadRoundTripContractTest {

    private static final String ENTITY_PACKAGE = "com.cretas.aims.entity";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 1. 忠实复现: 走 Hibernate 真正那条路 ====================

    private static LineItemMatch sampleMatch() {
        LineItemMatch match = new LineItemMatch();
        match.setSalesOrderItemId("soi-1");
        match.setProductTypeId("pt-1");
        match.setProductTypeName("酱鸭腿");
        match.setRequiredQuantity(new BigDecimal("130"));
        match.setAvailableQuantity(new BigDecimal("0"));
        match.setShortfallQuantity(new BigDecimal("130"));
        return match;
    }

    @Test
    @DisplayName("flush 前的 deep-copy 不能抛 —— 抛了就是整个 fan-out 事务回滚, SQL 根本不发出")
    void availableColumnSurvivesHibernateDeepCopy() {
        List<LineItemMatch> payload = new ArrayList<>(List.of(sampleMatch()));

        // ObjectMapperWrapper#clone 就是 Hibernate 可变类型 deep-copy 实际调的那条路,
        // 不是我另外 new 的 ObjectMapper —— 配置不同的话根本红不了。
        assertThatCode(() -> ObjectMapperWrapper.INSTANCE.clone(payload))
                .as("deepCopy 抛异常 = flush 阶段炸 = SQL 根本没发出去, 报告表永远 0 行")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("按实体声明的泛型类型回读 JSONB 不能抛 —— 这是读列那条路")
    void availableColumnSurvivesReadBack() throws Exception {
        Field available = SalesOrderShortageReport.class.getDeclaredField("available");
        String json = ObjectMapperWrapper.INSTANCE.toString(new ArrayList<>(List.of(sampleMatch())));

        assertThatCode(() -> ObjectMapperWrapper.INSTANCE.fromString(json, available.getGenericType()))
                .as("回读抛异常 = 就算写进去了也读不出来")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("round-trip 要保真, 不能只是'没抛异常'; 且计算属性的业务语义不能被 @JsonIgnore 改掉")
    @SuppressWarnings("unchecked")
    void deepCopyPreservesValues() {
        List<LineItemMatch> copy =
                ObjectMapperWrapper.INSTANCE.clone(new ArrayList<>(List.of(sampleMatch())));

        assertThat(copy).hasSize(1);
        LineItemMatch back = copy.get(0);
        assertThat(back.getSalesOrderItemId()).isEqualTo("soi-1");
        assertThat(back.getProductTypeName()).isEqualTo("酱鸭腿");
        assertThat(back.getShortfallQuantity()).isEqualByComparingTo("130");
        // 计算属性不落库, 但必须仍然算得出来 —— @JsonIgnore 只该影响序列化, 不该动业务语义
        assertThat(back.isFullySatisfied())
                .as("缺口 130 > 0 → 不满足")
                .isFalse();
    }

    @Test
    @DisplayName("序列化出来的 JSON 里不该再出现 fullySatisfied —— 它是算出来的, 不是状态")
    void computedPropertyIsNotSerialized() {
        String json = ObjectMapperWrapper.INSTANCE.toString(new ArrayList<>(List.of(sampleMatch())));

        assertThat(json)
                .as("落库的是状态; 计算属性写进去只会在回读时找不到落点")
                .doesNotContain("fullySatisfied");
        // 阳性对照: 真字段还在, 证明不是整个序列化都坏了
        assertThat(json).contains("shortfallQuantity");
    }

    // ==================== 2. 同类扫描: 下一个新 DTO 也别再犯 ====================

    @Test
    @DisplayName("所有 JSONB 列的载荷类型都不许有只读属性 (有 getter 但无 field/setter)")
    void noJsonbPayloadTypeHasReadOnlyProperty() {
        Set<Class<?>> payloadTypes = scanJsonbPayloadTypes();

        // 阳性对照: 扫描本身得真的扫到东西, 否则"零违规"只是因为什么都没扫到
        assertThat(payloadTypes)
                .as("应扫到项目自有的 JSONB 载荷类型; 扫到 0 个说明扫描逻辑坏了, 不是真干净")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Class<?> type : payloadTypes) {
            JavaType javaType = MAPPER.getTypeFactory().constructType(type);
            BeanDescription desc = MAPPER.getSerializationConfig().introspect(javaType);
            for (BeanPropertyDefinition prop : desc.findProperties()) {
                boolean writable = prop.hasSetter() || prop.hasField() || prop.hasConstructorParameter();
                if (prop.hasGetter() && !writable) {
                    violations.add(String.format(
                            "%s.%s —— 有 getter 但没有 backing field/setter, "
                            + "Jackson 会写进 JSON 而回读时抛 UnrecognizedPropertyException",
                            type.getSimpleName(), prop.getName()));
                }
            }
        }

        assertThat(violations)
                .as("只读属性会让 Hibernate deep-copy 在 flush 阶段抛异常, "
                    + "整个事务回滚且 SQL 不发出 —— 修法是给该 getter 加 @JsonIgnore, "
                    + "或改造成有 backing field 的普通属性")
                .isEmpty();
    }

    /** 扫实体包里所有 {@code @Type(JsonBinaryType.class)} 字段, 取出项目自有的载荷类型。 */
    private Set<Class<?>> scanJsonbPayloadTypes() {
        Set<Class<?>> result = new LinkedHashSet<>();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));

        for (BeanDefinition bd : scanner.findCandidateComponents(ENTITY_PACKAGE)) {
            Class<?> entity;
            try {
                entity = Class.forName(bd.getBeanClassName());
            } catch (Throwable ignored) {
                continue;
            }
            for (Field field : entity.getDeclaredFields()) {
                Type typeAnn = field.getAnnotation(Type.class);
                if (typeAnn == null || !JsonBinaryType.class.equals(typeAnn.value())) {
                    continue;
                }
                collectProjectOwnedTypes(field.getGenericType(), result);
            }
        }
        return result;
    }

    /** 从声明类型里递归摘出 {@code com.cretas} 自有的类 (跳过 List/Map/String/Object 等)。 */
    private void collectProjectOwnedTypes(java.lang.reflect.Type type, Set<Class<?>> sink) {
        if (type instanceof Class<?> clazz) {
            if (clazz.getName().startsWith("com.cretas") && !clazz.isEnum()) {
                sink.add(clazz);
            }
            return;
        }
        if (type instanceof ParameterizedType pt) {
            collectProjectOwnedTypes(pt.getRawType(), sink);
            for (java.lang.reflect.Type arg : pt.getActualTypeArguments()) {
                collectProjectOwnedTypes(arg, sink);
            }
        }
    }
}
