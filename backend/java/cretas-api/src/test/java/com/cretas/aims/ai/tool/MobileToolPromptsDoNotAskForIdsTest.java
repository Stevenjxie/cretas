package com.cretas.aims.ai.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手机端会走到的 Tool（原料 / 加工）追问文案里不许管操作员要 ID。
 *
 * <p>为什么是棘轮而不是等式: 「名称或ID」「暂不支持名称查找」这类**说了实话**的文案
 * 应当保留，所以闸盯的是白名单之外有没有新增。
 *
 * <p>⚠️ 两条自保:
 * <ul>
 *   <li>先断言扫到的**文件数 > 0** —— 「一个都没找到」是这类闸最像「一切正常」的坏法；</li>
 *   <li>只看字符串字面量，不看注释 —— 否则闸会把自己的说明文档也测进去。</li>
 * </ul>
 */
class MobileToolPromptsDoNotAskForIdsTest {

    /** 追问文案里出现「请提供…ID」的字面量。 */
    private static final Pattern ASK_FOR_ID =
            Pattern.compile("\"([^\"]*请提供[^\"]*ID[^\"]*)\"");

    /** 行注释 / 块注释, 剥掉后再扫 —— 闸不该数自己的注释。 */
    private static final Pattern COMMENTS =
            Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * 白名单: 这些文案**给了名称这条路**或如实声明了限制, 不是在为难操作员。
     * 新增条目必须在 PR 里说明为什么手机端用户拿得到这个 ID。
     */
    private static final Set<String> ALLOWED = Set.of(
            "请提供产品类型ID（productTypeId），暂不支持名称查找",
            "请提供产品类型ID或产品名称",
            "请问是否针对某个特定生产计划释放？如有请提供计划ID。",
            "请问是否关联某个生产计划？如有请提供计划ID。",
            "请提供产品模板名称或ID",
            "请提供生产批次ID，或留空分析所有活跃任务",
            "请提供要查询的工序任务ID"
    );

    private static final List<String> SCANNED_DIRS = List.of(
            "src/main/java/com/cretas/aims/ai/tool/impl/material",
            "src/main/java/com/cretas/aims/ai/tool/impl/processing");

    @Test
    @DisplayName("原料/加工 Tool 的追问文案不新增「请提供…ID」")
    void mobileFacingToolsDoNotAskForIds() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scannedFiles = 0;

        for (String dir : SCANNED_DIRS) {
            Path root = Paths.get(dir);
            assertThat(root)
                    .as("扫描目录必须存在, 否则这条闸在空集上恒绿: %s", dir)
                    .exists();

            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    scannedFiles++;
                    String src = Files.readString(p, StandardCharsets.UTF_8);
                    String code = COMMENTS.matcher(src).replaceAll(" ");
                    Matcher m = ASK_FOR_ID.matcher(code);
                    while (m.find()) {
                        String literal = m.group(1);
                        if (!ALLOWED.contains(literal)) {
                            offenders.add(root.relativize(p) + " → " + literal);
                        }
                    }
                }
            }
        }

        // 「一个都没找到」最像「一切正常」—— 先证明仪器确实读到了东西。
        assertThat(scannedFiles)
                .as("应当扫到 40+ 个 Tool 源文件(实测 47); 数到 0 说明路径错了, 而不是全都合规")
                .isGreaterThan(40);

        assertThat(offenders)
                .as("手机端 Tool 追问文案不许管操作员要 ID(报批次号/名称即可)")
                .isEmpty();
    }
}
