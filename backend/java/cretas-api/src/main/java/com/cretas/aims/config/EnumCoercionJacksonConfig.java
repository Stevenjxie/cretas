package com.cretas.aims.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局 Jackson 强制转换配置：把请求体里枚举字段的空字符串 {@code ""} 当作 {@code null}。
 *
 * <p><b>背景 / 问题</b>：前端表单里可选的枚举字段（如原料的税率 {@code taxRate}、
 * 储存类型等）在用户"未配置"时会提交 {@code "字段": ""}。Jackson 默认<b>拒绝</b>把空字符串
 * 强转成枚举，于是在 {@code @RequestBody} 反序列化阶段抛
 * {@link org.springframework.http.converter.HttpMessageNotReadableException}：
 * <pre>Cannot coerce empty String ("") to `...TaxRate` value</pre>
 * 这发生在 Bean Validation（{@code @Valid}）<b>之前</b>，用户看到的是晦涩的
 * "请求格式不正确，请检查JSON格式" 400，而该字段本来就是可选的。
 *
 * <p>真实事故：六扇门（F006）新建原料类型时税率留空 → 直接 400，建档被阻断
 * （2026-06-16）。Service 层已对 {@code taxRate == null} 做了 null-guard，所以
 * 把 {@code ""} 归一成 {@code null} 正是期望行为。
 *
 * <p><b>为什么是全局而非单字段</b>：这是一整类 bug（任何"前端可不发/发空串的枚举"
 * 都会踩）。全局 {@link CoercionInputShape#EmptyString} → {@link CoercionAction#AsNull}
 * 一处根治：
 * <ul>
 *   <li>可选枚举（如 taxRate）：{@code ""} → {@code null} → 正常落库为"未配置"。</li>
 *   <li>必填枚举（带 {@code @NotNull}）：{@code ""} → {@code null} → 触发字段级
 *       Bean Validation，给出"xxx 不能为空"的<b>清晰</b>提示，而不是晦涩的 JSON 解析错。</li>
 * </ul>
 * 没有任何枚举把空串作为合法取值，所以该转换不会改变正确请求的语义。
 *
 * <p><b>集合 / 数组同理（2026-06-16 补充）</b>：可选的 {@code List}/数组字段（如报工的
 * {@code photos}、{@code workerIds}）前端"无内容"时若提交 {@code "字段": ""}（空串而非
 * {@code []}），Jackson 抛 <pre>Cannot coerce empty String ("") to element of `ArrayList`</pre>
 * 这条消息<b>不含</b> "Cannot deserialize value of type"，因此不匹配 {@code GlobalExceptionHandler}
 * 的清晰分支 → 同样落到晦涩的 "请求格式不正确" fallback。和枚举是<b>同一族</b> bug
 * （"Cannot coerce empty String"）。把集合/数组的空串也归一成 {@code null}（= 等同于省略该字段，
 * service 本就 null-guard）即根治。发现于报工端点 {@code process-work-reporting/normal} 的
 * {@code photos} 注入探测（F006 soak 审计补测）。
 *
 * <p>仅作用于 Spring Boot 自动配置的 {@code ObjectMapper}（即 MVC {@code @RequestBody}
 * 反序列化使用的那个）；AI 工具等自建 ObjectMapper 不受影响。
 *
 * @since 2026-06-16
 */
@Configuration
public class EnumCoercionJacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer enumEmptyStringAsNullCustomizer() {
        return builder -> builder.postConfigurer(mapper -> {
            // 空字符串 "" → null，覆盖整个 "Cannot coerce empty String" 家族:
            // 枚举 (taxRate/storageType...) + 集合/数组 (photos/workerIds...)。
            mapper.coercionConfigFor(LogicalType.Enum)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull);
            mapper.coercionConfigFor(LogicalType.Collection)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull);
            mapper.coercionConfigFor(LogicalType.Array)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull);
        });
    }
}
