package com.cretas.aims.ai.grounding;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数字接地校验 —— 把「LLM 不许编数字」从一句嘱咐变成一道闸。
 *
 * <p>用法：把喂给模型的事实值集合和模型产出的文案一起交进来，本类找出文案里
 * <b>出现了但事实里没有</b>的数字。调用方拿到非空结果应当<b>拒绝这次生成</b>，
 * 而不是把文案照发（禁止降级处理）。
 *
 * <h2>为什么可以严格校验</h2>
 *
 * 朴素做法会被「第 1 步」「三个方面」这类序号淹没，于是不得不给小整数开后门 ——
 * 而后门一开，「缺 12kg」这种真该管的数字也跟着漏过去了。
 *
 * <p>这里换了个方向：<b>约束生成格式，让严格校验成为可能</b>。提示词要求模型用
 * 「·」分点、不要用数字编号，于是文案里剩下的每一个数字都应当是引用事实。校验
 * 因此可以不留例外。模型若不守格式，产出会被拒 —— 那是我们要的结果，不是需要
 * 绕开的麻烦。
 *
 * <h2>比较口径</h2>
 *
 * 用 {@link BigDecimal#compareTo} 而不是字符串相等：事实里的 {@code 38} 与文案里的
 * {@code 38.0} 是同一个数，字符串比较会把它误判成编造。反过来，{@code 380} 与
 * {@code 38} 不同，数值比较能分开。
 */
@Component
public class GroundedNumberValidator {

    /**
     * 匹配文本中的数字：可带负号、千分位逗号、小数点。
     *
     * <p><b>只用前置边界排除标识符</b>（{@code MB-001} 里 {@code 001} 前面是
     * {@code -}，被 lookbehind 挡掉），尾部只防止把数字自身截断。
     *
     * <p>⚠️ 尾部<b>不能</b>排除字母：数量后面几乎总是跟着单位（{@code 38kg}、
     * {@code 12%}）。第一版写成 {@code (?![0-9A-Za-z_-])} 的后果是这类数字<b>一个
     * 也匹配不到</b>，于是「全部有据」的测试<b>空匹配通过</b>——看起来在校验，
     * 实际什么都没验。是 UT-GNV-04（量级差异）把它暴露出来的。
     */
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![0-9A-Za-z_.-])(-?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|-?\\d+(?:\\.\\d+)?)(?![0-9])");

    /**
     * 找出文案里没有事实支撑的数字。
     *
     * @param generatedText 模型产出的文案
     * @param facts         喂给模型的事实值（任意对象，取其 {@code toString} 里的数字）
     * @return 无支撑的数字原文，按出现顺序去重；空列表表示全部有据
     */
    public List<String> findUngroundedNumbers(String generatedText, Collection<?> facts) {
        if (generatedText == null || generatedText.isBlank()) {
            return List.of();
        }
        Set<BigDecimal> grounded = extractNumbers(factsToText(facts));

        List<String> ungrounded = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(generatedText);
        while (m.find()) {
            String raw = m.group(1);
            BigDecimal value = parse(raw);
            if (value == null) {
                continue;
            }
            if (grounded.stream().noneMatch(g -> g.compareTo(value) == 0) && seen.add(raw)) {
                ungrounded.add(raw);
            }
        }
        return ungrounded;
    }

    /** 便捷判定：是否全部数字都有事实支撑。 */
    public boolean isGrounded(String generatedText, Collection<?> facts) {
        return findUngroundedNumbers(generatedText, facts).isEmpty();
    }

    private String factsToText(Collection<?> facts) {
        if (facts == null || facts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object fact : facts) {
            if (fact != null) {
                sb.append(fact).append(' ');
            }
        }
        return sb.toString();
    }

    private Set<BigDecimal> extractNumbers(String text) {
        Set<BigDecimal> out = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            BigDecimal v = parse(m.group(1));
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    private BigDecimal parse(String raw) {
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
