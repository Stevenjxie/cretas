package com.cretas.aims.service.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通知模板渲染引擎 — Phase 3 Canvas-Notify Step T5.
 *
 * <p>替换 NotifyTemplate.body_template / title 中的 {{var}} 占位符为 NotifyRequest.params
 * 中对应 key 的实际值.
 *
 * <p>实现方案 A (推荐): 简单 regex {@code \{\{(\w+)\}\}}, 配 params 直接替换. 性能最好,
 * 适合 90% 普通业务模板. 容忍 {{ var }} 内部空白.
 *
 * <p>Fool-proof Rule 1: 未提供变量值时显式抛 {@link IllegalArgumentException},
 * 不静默用 {@code ""} (否则用户收到"您有 笔 元 待审"残缺通知).
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class TemplateEngine {

    /**
     * 占位符 pattern: {{ varName }} — 容忍内部空白, varName 是 \w+ (字母数字下划线).
     */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");

    /**
     * 渲染模板. {{var}} 占位符替换为 params 中对应值.
     *
     * @param template 模板字符串, 含 {{var}} 占位符
     * @param params  实际参数, key 必须 cover 模板中所有 {{var}}
     * @return 渲染后字符串
     * @throws IllegalArgumentException 模板中 {{var}} 在 params 找不到 key (fool-proof Rule 1)
     */
    public String render(String template, Map<String, Object> params) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        Map<String, Object> safeParams = params != null ? params : Map.of();

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1);
            if (!safeParams.containsKey(varName)) {
                throw new IllegalArgumentException(
                        "通知模板渲染失败: 缺少变量 {{" + varName + "}} (params 中无此 key)");
            }
            Object value = safeParams.get(varName);
            String replacement = value != null ? value.toString() : "";
            // Matcher.quoteReplacement: escape $ and \ in replacement string
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
