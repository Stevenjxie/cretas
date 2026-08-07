package com.cretas.aims.service.finding;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.grounding.GroundedNumberValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发现 → 行动建议。**领域参数化**，两个出口共用。
 *
 * <p>本类是从 {@code FindingActionPlanTool} 抽出来的。抽的原因是那个 Tool 里
 * {@code DOMAIN = "inventory"} 是**硬编码**的（2026-08-07 实测），于是：
 *
 * <ul>
 *   <li>餐饮租户问「现在该干什么」，拿到的是按**库存域**跑出来的发现
 *       （{@code LowStockFindingProvider}），与餐饮的毛利/损耗规则完全不搭；</li>
 *   <li>提示词写死「你是食品加工厂的生产助理」，对店长也是错的身份。</li>
 * </ul>
 *
 * <p>更要命的是它**只是个 {@code @Tool}**：餐饮提问在到达 Java Tool 之前就被
 * tiered 路由委派给 Python 了（2026-08-06 实测某餐饮 Tool 日志 0 次调用），
 * 所以对餐饮它是「挂在没人走的路上」。REST 出口由
 * {@code FindingController} 提供，与发现层的「主动出口」同一个位置。
 *
 * <h2>LLM 的职责边界</h2>
 * <ul>
 *   <li><b>LLM 做</b>：把干巴巴的事实组织成人话、排出先后、给出可执行动作</li>
 *   <li><b>LLM 不做</b>：产生任何数字</li>
 * </ul>
 *
 * <p>这条边界不靠嘱咐，靠 {@link GroundedNumberValidator} 在事后卡住：产出里出现
 * 事实之外的数字 → <b>抛异常拒绝这次生成</b>。一个编了数字的行动建议比没有建议
 * 更糟 —— 它会被照着执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FindingActionPlanService {

    /**
     * 提示词刻意要求「·」分点且禁用数字编号。
     *
     * <p>不是排版偏好：序号会让「第 1 步」里的 1 混进数字流，逼校验给小整数开
     * 后门，而后门一开「缺 12kg」这类真该管的数字也会漏过去。约束格式是让严格
     * 校验成为可能的前提。
     *
     * <p>{@code %s} 是角色设定 —— 唯一随领域变化的部分。其余四条硬性要求对两个
     * 领域逐字相同，刻意不各写一份：那是让两边慢慢漂开的起点。
     */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是%s。根据给出的异常事实，写一段简短的行动建议。

            硬性要求：
            1. 只能使用事实中已给出的数字。禁止计算、估算、推断任何新数字，
               禁止出现事实里没有的金额、百分比、天数。
            2. 用「·」开头分点，每点一行。禁止使用「1.」「2.」「第3步」这类数字编号。
            3. 不确定的地方就不写，不要用「大约」「预计」来掩饰。
            4. 每点不超过 30 字，总共不超过 4 点。
            """;

    private final FindingService findingService;
    private final DashScopeClient dashScopeClient;
    private final GroundedNumberValidator groundedNumberValidator;
    /** 只用它的 {@code renderDigestLines} —— 纯模板零 LLM，见 {@link #renderFacts}。 */
    private final FindingTextRenderer findingTextRenderer;

    /**
     * @param factoryId 租户
     * @param domain    发现层领域（{@code inventory} / {@code restaurant}）
     * @return 行动建议结果；{@code hasPlan=false} 表示本领域当前没有需要处理的异常
     * @throws IllegalStateException 发现层未完整执行 / 模型未产出 / 产出含无据数字
     */
    public Map<String, Object> generate(String factoryId, String domain) {
        FindingService.Result findingResult = findingService.detectInline(factoryId, domain);

        // 发现层没跑完就生成建议 = 拿不完整的事实去指挥行动。宁可不给建议。
        if (!findingResult.complete()) {
            throw new IllegalStateException(
                    "异常检测未完整执行, 拒绝据此生成行动建议: 失败规则=" + findingResult.failedRules());
        }

        List<Finding> findings = findingResult.findings();
        if (findings.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("hasPlan", false);
            empty.put("plan", null);
            empty.put("domain", domain);
            // ⛔ 「没有异常」与「判不了」不是一句话。skippedRules 非空时说成
            // 「无需行动建议」就是把缺数据渲染成了健康 —— 三态在这个出口也要活着。
            empty.put("skippedRules", findingResult.skippedRules());
            empty.put("message", findingResult.skippedRules().isEmpty()
                    ? noIssueMessage(domain)
                    : noIssueMessage(domain) + "（另有规则因数据不足未参与判断）");
            return empty;
        }

        List<Object> facts = buildFacts(findings);
        String plan = dashScopeClient.chatLowTemp(
                systemPrompt(domain), renderFacts(findingResult));

        if (plan == null || plan.isBlank()) {
            throw new IllegalStateException("模型未产出行动建议, 拒绝返回空方案");
        }

        // 事后卡住 LLM 编数字。一个编了数字的行动建议比没有建议更糟 —— 它会被照着执行。
        List<String> ungrounded = groundedNumberValidator.findUngroundedNumbers(plan, facts);
        if (!ungrounded.isEmpty()) {
            log.warn("行动建议含无据数字, 已拒绝: factoryId={}, domain={}, ungrounded={}",
                    factoryId, domain, ungrounded);
            throw new IllegalStateException(
                    "生成的行动建议里出现了系统数据中不存在的数字 " + ungrounded + "，已拒绝返回");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasPlan", true);
        result.put("plan", plan);
        result.put("domain", domain);
        result.put("basedOnFindings", facts.size());
        result.put("facts", facts);
        result.put("skippedRules", findingResult.skippedRules());
        result.put("message", plan);
        return result;
    }

    /**
     * 角色设定按领域分。餐饮与工厂**是两个产品**（Steve 已更正过两次），
     * 对店长说「食品加工厂的生产助理」不只是措辞不当，是身份就错了。
     */
    private String systemPrompt(String domain) {
        String persona = "restaurant".equals(domain)
                ? "餐厅的经营助理"
                : "食品加工厂的生产助理";
        return SYSTEM_PROMPT_TEMPLATE.formatted(persona);
    }

    private String noIssueMessage(String domain) {
        return "restaurant".equals(domain)
                ? "当前没有需要处理的经营异常，无需行动建议。"
                : "当前没有需要处理的库存异常，无需行动建议。";
    }

    /** 只把渲染要用到的字段交给模型，减少它顺手编造的空间。 */
    private List<Object> buildFacts(List<Finding> findings) {
        List<Object> facts = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("对象", f.subjectName());
            item.putAll(f.facts());
            facts.add(item);
        }
        return facts;
    }

    /**
     * 喂给模型的事实文本 —— 用 {@link FindingTextRenderer} 已经渲染好的整句，
     * 不是裸的 {@code {对象: X, ...}} 字典。
     *
     * <p>🔴 2026-08-07 prod 实测的缺陷：裸字典喂进去时，模型把两条**互不相关**的
     * 发现揉成了一条 —— 谜题菜「罗氏虾」和损耗类型「变质」被写成
     * 「排查罗氏虾变质原因」，还冒出「排查罗氏虾10个菜品销售表现」。
     * 数字没编（{@link GroundedNumberValidator} 放行是对的），但**归因错了**；
     * 一个归因错的行动建议和一个编了数字的一样糟 —— 它会被照着执行。
     *
     * <p>根因是裸字典里只有「对象」两个字，模型分不清这是两件事。渲染层的整句
     * 天然带着「谁的什么指标」，而它是**纯模板零 LLM** 的 —— 于是模型拿到的每
     * 一句话在归因上已经是对的，它只需要排先后、给动作。
     *
     * <p>⚠️ 校验仍然用**结构化** facts（{@link #buildFacts}）取合法数字集合 ——
     * 那一侧刻意不改：从渲染好的句子里再解析数字，等于让校验依赖措辞，
     * 措辞一变校验就漏。
     */
    private String renderFacts(FindingService.Result result) {
        StringBuilder sb = new StringBuilder("当前异常事实（每行一条，彼此独立，不要合并）：\n");
        for (String line : findingTextRenderer.renderDigestLines(result)) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
