package com.cretas.aims.codequality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报工链路里标了 {@code BLOCKING} 的报错必须同时给出 {@code withHint} —— 拦得住, 也得说下一步。
 *
 * <p><b>为什么单独钉这一类</b>: {@code BLOCKING} 是「现场干不下去了」的最高级别，而报工是
 * <b>车间现场</b>操作 —— 操作员站在机器前，没有翻文档或问研发的条件。
 *
 * <p>2026-08-01 六膳门重建走完整链路时实测：七次被拦有六次都给了明确动作
 * （「请先完成 BOM 配置」/「请前往「生产管理 → BOM成本管理」为该原料填写标准用量」/
 * 精确到 {@code items[2].materialCategory}），唯独报工投料那条是：
 *
 * <pre>
 * 409 所选批次不在可投料的生产库中
 * errorCode: PRODUCTION_INPUT_BATCH_NOT_IN_WORKSHOP
 * actionHint: null        ← 唯一一个没有
 * </pre>
 *
 * <p>它说了批次不在生产仓，但没说要<b>先领料把料从原料仓领到生产仓</b>。
 *
 * <h2>⚠️ 这是棘轮，不是"全绿门禁"</h2>
 *
 * <p>顺着这条线扫下去，报工链路里<b>共有 {@value #KNOWN_DEBT_SIZE} 处</b>历史 BLOCKING
 * 同样没带 hint（全仓另有 20+ 处，多在 AI 工具的参数校验里，不在本用例范围）。
 *
 * <p><b>刻意不一次改完</b>：每条 hint 都是「该把用户导到哪个页面做什么」的产品判断，
 * 批量编出 27 条面向车间的中文指引而不经业务确认，只会产出一堆看着像话、实际指错地方的文案 ——
 * 那比没有 hint 更糟。
 *
 * <p>所以本用例断言的是<b>「违规集合 ⊆ 已知债务」</b>：
 * <ul>
 *   <li>新写一个不带 hint 的 BLOCKING → <b>红</b>（这是主要目的）；</li>
 *   <li>修好一条历史债务 → 必须同时从 {@link #KNOWN_DEBT} 里删掉，否则「陈旧条目」断言会红
 *       —— 名单只能变短，不能变长。</li>
 * </ul>
 */
class BlockingErrorsCarryActionHintContractTest {

    static final int KNOWN_DEBT_SIZE = 18;

    /**
     * 只扫<b>报工 / 生产</b>链路 —— BLOCKING 在这里的字面意思就是"人停在机器前干不下去了"。
     * 全仓其余部分另案处理，不在本 PR 静默扩大范围。
     */
    private static final List<Path> SCANNED = List.of(
            Path.of("src/main/java/com/cretas/aims/service/processentry"),
            Path.of("src/main/java/com/cretas/aims/service/production"));

    /** 一条 throw 语句里从 {@code new BusinessException} 到分号为止的链式调用。 */
    private static final Pattern THROW_CHAIN = Pattern.compile(
            "new\\s+BusinessException\\s*\\((?:[^;]*?)\\)(?:\\s*\\.\\w+\\s*\\([^;]*?\\))*\\s*;",
            Pattern.DOTALL);

    private static final Pattern CODE = Pattern.compile("withCode\\(\"([A-Z0-9_]+)\"\\)");

    /**
     * 2026-08-01 盘点出的历史欠账。<b>只允许变短。</b>
     * 修好一条就从这里删一条；新增一条会让下面的"未登记违规"断言变红。
     */
    private static final Set<String> KNOWN_DEBT = Set.of(
            "AUTOMATIC_MATERIAL_REQUIREMENT_INVALID",
            "AUTOMATIC_MATERIAL_UNIT_REQUIRED",
            "BOM_OUTPUT_BASIS_INVALID",
            "PACKAGING_REQUIREMENT_INVALID",
            "PINNED_BOM_FAMILY_INVALID",
            "PINNED_BOM_NOT_FOUND",
            "PROCESS_SHEET_INPUT_QUANTITY_INVALID",
            "PROCESS_SHEET_PLAN_UNIT_MISMATCH",
            "PROCESS_SHEET_WORKFLOW_INPUT_PORT_ID_MISSING",
            "PROCESS_SHEET_WORKFLOW_INPUT_PORT_NOT_FOUND",
            "PROCESS_SHEET_WORKFLOW_MULTI_OUTPUT_REQUIRED",
            "PROCESS_SHEET_WORKFLOW_SELECTION_GROUP_SNAPSHOT_INVALID",
            "PRODUCTION_INPUT_BATCH_MATERIAL_MISMATCH",
            "PRODUCTION_INPUT_BATCH_NOT_FOUND",
            "PRODUCTION_INPUT_BATCH_UNIT_REQUIRED",
            "PRODUCTION_PLAN_NOT_FOUND",
            "PRODUCTION_PLAN_OWNERSHIP_CONTEXT_REQUIRED",
            "SEASONING_REQUIREMENT_INVALID");

    private record Scan(Set<String> hintless, int blockingSeen) {
    }

    private Scan scan() throws IOException {
        Set<String> hintless = new LinkedHashSet<>();
        int blockingSeen = 0;
        List<Path> javaFiles = new ArrayList<>();
        for (Path root : SCANNED) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                javaFiles.addAll(files.filter(p -> p.toString().endsWith(".java")).toList());
            }
        }
        for (Path file : javaFiles) {
            String source = Files.readString(file);
            if (!source.contains("BLOCKING")) {
                continue;
            }
            Matcher m = THROW_CHAIN.matcher(source);
            while (m.find()) {
                String chain = m.group();
                if (!chain.contains("\"BLOCKING\"")) {
                    continue;
                }
                blockingSeen++;
                if (chain.contains(".withHint(")) {
                    continue;
                }
                Matcher code = CODE.matcher(chain);
                hintless.add(code.find() ? code.group(1) : "(无 errorCode) " + chain.strip());
            }
        }
        return new Scan(hintless, blockingSeen);
    }

    @Test
    @DisplayName("不许新增没有 actionHint 的 BLOCKING —— 拦住车间却不说怎么办, 等于把人晾在机器前")
    void noNewBlockingErrorWithoutActionHint() throws IOException {
        Scan scan = scan();

        // 阳性对照: 扫不到任何 BLOCKING 说明正则失配, 那么"零违规"是假的
        assertThat(scan.blockingSeen())
                .as("一个 BLOCKING 都没扫到 —— 正则失配, 本用例的结论无效")
                .isGreaterThan(0);

        Set<String> unregistered = new TreeSet<>(scan.hintless());
        unregistered.removeAll(KNOWN_DEBT);

        assertThat(unregistered)
                .as("新写的 BLOCKING 报错没有 actionHint。BLOCKING = 现场干不下去了, "
                    + "必须同时告诉用户下一步去哪做什么。"
                    + "修法: 链上补 .withHint(\"请前往「X → Y」…\")；"
                    + "确有理由不给提示的, 说明理由后登记进 KNOWN_DEBT")
                .isEmpty();
    }

    @Test
    @DisplayName("KNOWN_DEBT 只能变短 —— 修好一条必须删一条, 不许留陈旧条目")
    void knownDebtHasNoStaleEntries() throws IOException {
        Scan scan = scan();

        Set<String> stale = new TreeSet<>(KNOWN_DEBT);
        stale.removeAll(scan.hintless());

        assertThat(stale)
                .as("这些 code 已经带上 hint 了(或已不存在), 请从 KNOWN_DEBT 里删掉 —— "
                    + "名单留着陈旧条目就会掩护下一个同名的新违规")
                .isEmpty();

        assertThat(KNOWN_DEBT)
                .as("KNOWN_DEBT 的声明数量与 KNOWN_DEBT_SIZE 常量必须一致(Javadoc 引用了它)")
                .hasSize(KNOWN_DEBT_SIZE);
    }
}
