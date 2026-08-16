package com.cretas.aims.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 表单助手的 {@code success} 不许采信模型自报。
 *
 * <h2>🔴 2026-08-13 真机抓到(LIUSHANMEN 生产)</h2>
 * 「AI 录入」把「客户王伟，温氏黄油鸡2只单价30，吸塑盒2014-3.5 1个单价2」解析得
 * <b>完全正确</b>, 界面上却只回一句 <b>「操作成功」</b>, 预览卡不出、什么也没发生。
 * 直接打接口拿到的原始响应:
 *
 * <pre>
 * "data": { "success": false,                         ← 🔴
 *           "fieldValues": { customerName: 王伟,
 *                            items: [ {温氏黄油鸡,2,只,30}, {吸塑盒2014-3.5,1,个,2} ] },
 *           "confidence": 0.9, "message": null, "missingRequiredFields": [] }
 * </pre>
 *
 * <p>成因: prompt 的输出 schema 里有 {@code "success"} 这个键, <b>却从没告诉模型它是
 * 什么意思</b>。模型于是自由发挥 —— 字段全对照样返 false。服务端
 * {@code json.path("success").asBoolean(true)} 原样收下, 前端把它当失败,
 * 而 {@code message} 是 null, 兜底落到 ApiResponse 包装层的通用文案「操作成功」。
 *
 * <h2>这条闸守什么</h2>
 * 两件事, 缺一不可:
 * <ol>
 *   <li><b>不向模型索取我们不采信的东西</b> —— prompt schema 里不许再出现 {@code "success"};
 *       留着它, 下一个读代码的人就会把它重新接回去。</li>
 *   <li><b>不采信模型自报的成败</b> —— 代码里不许再有 {@code json.path("success")}。
 *       真正的失败有自己的载体: 各 {@code error(...)} 工厂会设 success=false <b>并带 message</b>。</li>
 * </ol>
 *
 * <p>读源码、不连库、毫秒级, 与 {@code FlywayVersionUniquenessTest} 同一类。
 *
 * <p>⚠️ CI 的 Java selector 目前跑
 * {@code *RepositoryQueryValidationTest,*StartupGuardTest,FlywayVersionUniquenessTest},
 * <b>不覆盖本用例</b>(本仓 Java 全量套件只在 full_audit 跑)。
 */
class FormAssistantSuccessNotSelfReportedContractTest {

    private static final Path SERVICE = Path.of(
            "src/main/java/com/cretas/aims/service/FormAssistantService.java");

    /** 容忍空白与换行: {@code json . path ( "success" )}。 */
    private static final Pattern READS_MODEL_SUCCESS = Pattern.compile(
            "\\.\\s*path\\s*\\(\\s*\"success\"\\s*\\)");

    /** prompt schema 里的 {@code "success": ...} 行(在 Java text block 内)。 */
    private static final Pattern ASKS_FOR_SUCCESS = Pattern.compile(
            "\"success\"\\s*:\\s*(true|false)");

    private String source() throws IOException {
        assertThat(Files.exists(SERVICE))
                .as("找不到 %s —— 文件挪了位置, 这条闸需要跟着改, 否则它在守一个空气",
                        SERVICE.toAbsolutePath())
                .isTrue();
        return Files.readString(SERVICE);
    }

    /**
     * 剥掉注释再断言。
     *
     * <p>⚠️ 这一步不能省, 而且是实测撞出来的: 修复处的注释里就写着
     * {@code "success": false}(在讲这个缺陷本身), 不剥的话本闸第一次跑就红 4 条 ——
     * 修好了照样红, 「锚在注释上的假红」。
     *
     * <p>先去掉块注释({@code /*...*}{@code /}, 含 javadoc), 再去掉整行的 {@code //} 注释。
     * 只删「整行以 // 开头」的, 不碰行内内容 —— text block 里的 JSON 不会以 // 开头。
     */
    private static String stripComments(String src) {
        String noBlocks = src.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlocks.split("\n", -1)) {
            if (!line.trim().startsWith("//")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    @DisplayName("服务端不再读模型自报的 success")
    void doesNotTrustModelReportedSuccess() throws IOException {
        Matcher m = READS_MODEL_SUCCESS.matcher(stripComments(source()));
        int hits = 0;
        while (m.find()) {
            hits++;
        }
        assertThat(hits)
                .as("""
                        FormAssistantService 又开始读模型自报的 success 了。\
                        模型会在【字段全部解析正确】的情况下返 false(2026-08-13 生产实测), \
                        前端据此判失败, 而 message 为 null 时兜底会落到包装层的「操作成功」\
                        —— 用户看到"成功"却什么也没发生。成败要由服务端确定性判定: \
                        能解出 JSON 就是成功, 真失败走 error(...)(它会带 message)。""")
                .isZero();
    }

    @Test
    @DisplayName("prompt 不再向模型索要 success 字段")
    void promptDoesNotAskForSuccess() throws IOException {
        Matcher m = ASKS_FOR_SUCCESS.matcher(stripComments(source()));
        int hits = 0;
        while (m.find()) {
            hits++;
        }
        assertThat(hits)
                .as("""
                        prompt 的输出 schema 里又出现了 "success"。\
                        它没有定义(schema 只给了一个示例值), 模型只能自由发挥; \
                        而服务端并不采信它 —— 向模型索取一个我们不用的字段, \
                        只会让下一个人把它重新接回判定里。""")
                .isZero();
    }

    /**
     * ⚠️ 反向断言: 确认这个文件里确实还有解析逻辑。
     * 少了这条, 上面两条在「文件被清空/改名」时会一起变成恒真式。
     */
    @Test
    @DisplayName("文件里确实还有 JSON 解析逻辑(否则上面两条是恒真式)")
    void theFileStillContainsParsingLogic() throws IOException {
        String src = source();
        assertThat(src).contains("private FormParseResult parseFormParseResponse(");
        assertThat(src).contains("result.setSuccess(true);");
        assertThat(src).contains("field_values");
    }
}
