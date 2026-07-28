package com.cretas.aims.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 表单填写 prompt 注册表 —— 按「域 + 实体」查 prompt。
 *
 * <p><b>为什么要有它</b>(2026-07-28)：这些 prompt 原本住在 web-admin 的
 * {@code components/ai-entry/types.ts} 里，随前端发布。那样有四个长期毛病：
 * <ol>
 *   <li>改不动 —— 调一句措辞要发一次前端；</li>
 *   <li>看不见 —— 哪个实体解析成功率高/低，服务端一无所知；</li>
 *   <li>会复制 —— 手机端将来要填表就得抄一份，两边慢慢漂；</li>
 *   <li>进不了飞轮 —— 无法记录「哪个 prompt 版本产出了什么结果」。</li>
 * </ol>
 * 现在 prompt 作为资源文件随后端走，前端只传 {@code entityType}。
 *
 * <p><b>目录约定</b>：{@code resources/ai/form-prompts/{domain}/{ENTITY_TYPE}.md}。
 * 按域分目录是为将来餐饮/工厂拆独立服务做准备 —— 拆分时整个域目录跟着走。
 * 当前 7 个实体全部属于 factory 域。
 *
 * <p><b>职责边界</b>：资源文件里只放<b>域知识</b>（字段含义、防呆规则、交互规则）。
 * 输出格式契约由 {@link FormAssistantService} 统一追加 —— 因为解析端要什么形状，
 * 只有解析端知道。搬运时已把原前端 prompt 里的 {@code FILL_FORM} 输出段剥掉，
 * 否则会和后端的 {@code field_values} 契约打架。
 */
@Slf4j
@Component
public class FormPromptRegistry {

    /** 工厂所在时区 —— 日期类字段（"明天"/"下周一"）必须按它换算。 */
    private static final ZoneId FACTORY_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String BASE_PATH = "ai/form-prompts/";

    /** entityType 允许的形态，挡住路径穿越（entityType 来自请求体）。 */
    private static final java.util.regex.Pattern SAFE_KEY =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    /** 缓存已加载的模板；资源在 jar 内不会变，加载一次即可。 */
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    /**
     * 取某个域某个实体的 prompt（已完成变量替换）。
     *
     * @return 空 = 该实体没有注册专属 prompt，调用方应回退到通用 prompt
     */
    public Optional<String> promptFor(String domain, String entityType) {
        if (domain == null || entityType == null) {
            return Optional.empty();
        }
        String d = domain.trim().toLowerCase(Locale.ROOT);
        String e = entityType.trim().toUpperCase(Locale.ROOT);
        if (!SAFE_KEY.matcher(e).matches() || !d.matches("[a-z][a-z0-9_]{0,31}")) {
            log.warn("[FormPromptRegistry] 非法 domain/entityType, 拒绝加载: {}/{}", domain, entityType);
            return Optional.empty();
        }
        return cache
                .computeIfAbsent(d + "/" + e, this::load)
                .map(this::substitute);
    }

    private Optional<String> load(String key) {
        String path = BASE_PATH + key + ".md";
        ClassPathResource res = new ClassPathResource(path);
        if (!res.exists()) {
            log.debug("[FormPromptRegistry] 无专属 prompt, 回退通用: {}", path);
            return Optional.empty();
        }
        try (InputStream in = res.getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                log.warn("[FormPromptRegistry] prompt 文件为空, 回退通用: {}", path);
                return Optional.empty();
            }
            log.info("[FormPromptRegistry] 载入 {} ({} 字符)", path, text.length());
            return Optional.of(text);
        } catch (IOException ex) {
            // 读不出来就回退通用 prompt，不要让填表功能整个挂掉。
            log.warn("[FormPromptRegistry] 读取失败, 回退通用: {} — {}", path, ex.getMessage());
            return Optional.empty();
        }
    }

    /** 替换模板变量。目前只有 {@code {{currentFactoryDate}}}。 */
    private String substitute(String template) {
        return template.replace("{{currentFactoryDate}}", today());
    }

    /** 工厂当前日期（Asia/Shanghai），YYYY-MM-DD。 */
    public String today() {
        return LocalDate.now(FACTORY_ZONE).toString();
    }
}
