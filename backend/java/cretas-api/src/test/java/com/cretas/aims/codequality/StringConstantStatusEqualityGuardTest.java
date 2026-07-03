package com.cretas.aims.codequality;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 同因扫描 regression guard — 防止 "String 常量 holder 类" 状态/类型字段被 == / != 引用比较
 * (而非 .equals() 值比较) 的 bug 类再次出现。
 *
 * <p>背景 (#1202 / #1205): {@code FinishedGoodsBatch.Status} 是 {@code public static final class}
 * 持有 {@code public static final String} 常量 (不是 Java {@code enum})，{@code getStatus()} 返回
 * {@code String}。写成 {@code batch.getStatus() == Status.REVERSED} 是 <b>String 引用相等</b>：
 * H2/单测下常量字符串常被 intern，恰好为真而照不出 bug；prod PostgreSQL 从 varchar 列读出的是全新
 * (非 intern) 字符串对象，引用比较恒为 false → 分支永不进入 → 静默数据腐蚀 (成品"复活"分支失效，
 * FG 批次尸体状态 REVERSED 却被上层当作已入库处理)。
 *
 * <p>本测试对全部已知的 String 常量 holder 类 (逐一人工确认过 —— 不是 enum) 做全仓库源码扫描，
 * 断言任何地方都不存在对其常量的 {@code ==} / {@code !=} 比较。新增此类 holder 类时，把它加进
 * {@link #GUARDED_CLASSES} 数组即可自动纳入保护。
 *
 * <p>纯字符串扫描，不依赖 Spring 上下文 / 数据库，运行极快，跑在每次 {@code mvn test}。
 *
 * @since 2026-07-04 (same-cause sweep for #1202 / #1205)
 */
class StringConstantStatusEqualityGuardTest {

    /**
     * 已确认的 "String 常量 holder 类" (非 enum) 前缀。全仓库 grep 逐一人工核实
     * (2026-07-04 sweep): {@code public static final class} 里全是
     * {@code public static final String} 字段，getter 返回 {@code String}。
     *
     * <p>不在此列表的其它 {@code XxxStatus}/{@code XxxType} 都是真 Java {@code enum}
     * (ProductionPlanStatus / SalesOrderStatus / TransferStatus / MaterialBatchStatus /
     * ProductionBatchStatus / ReturnOrderStatus / SalesDeliveryStatus / VoucherStatus /
     * InstanceStatus / WorkstationStatus / AccountingPeriod.Status / ProductionReport.Status /
     * DeliveryNoteSourceType / PaymentSourceType / PlanSourceType 等) —— enum 用 {@code ==}
     * 是 Java 语言保证安全的单例比较，不在此扫描范围内。
     */
    private static final String[] GUARDED_CLASSES = {
            "FinishedGoodsBatch.Status",
            "BatchWorkSession.Status",
            "SemiFinishedInventoryTransaction.TxnType",
            "SemiFinishedInventoryTransaction.SourceType",
    };

    /**
     * 匹配 `<GuardedClass>.CONST ==` 或 `== <GuardedClass>.CONST` 或对应的 `!=` 形式。
     * 常量名: 大写字母/数字/下划线。前后允许空白。
     */
    private static Pattern buildPattern(String guardedClass) {
        String qClass = Pattern.quote(guardedClass);
        // 形式 A: GuardedClass.CONST  ==/!=
        String formA = qClass + "\\.[A-Z0-9_]+\\s*(==|!=)";
        // 形式 B: ==/!=  GuardedClass.CONST
        String formB = "(==|!=)\\s*" + qClass + "\\.[A-Z0-9_]+";
        return Pattern.compile(formA + "|" + formB);
    }

    @Test
    void noReferenceEqualityComparisonAgainstStringConstantStatusHolders() throws IOException {
        Path mainSrcRoot = resolveMainJavaRoot();
        List<String> violations = new ArrayList<>();

        Pattern[] patterns = new Pattern[GUARDED_CLASSES.length];
        for (int i = 0; i < GUARDED_CLASSES.length; i++) {
            patterns[i] = buildPattern(GUARDED_CLASSES[i]);
        }

        try (Stream<Path> files = Files.walk(mainSrcRoot)) {
            List<Path> javaFiles = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList());

            for (Path file : javaFiles) {
                List<String> lines = Files.readAllLines(file);
                for (int lineNo = 0; lineNo < lines.size(); lineNo++) {
                    String line = lines.get(lineNo);
                    for (int i = 0; i < GUARDED_CLASSES.length; i++) {
                        Matcher m = patterns[i].matcher(line);
                        if (m.find()) {
                            violations.add(String.format(
                                    "%s:%d — 疑似对 String 常量 holder 类 %s 做引用相等(==/!=)比较, 必须改用 .equals(): %s",
                                    mainSrcRoot.relativize(file), lineNo + 1, GUARDED_CLASSES[i], line.trim()));
                        }
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("发现 String 常量 holder 类 引用相等(==) bug 类 (H2/单测常 intern 恰好为真, prod Postgres 非 intern 恒 false, 详见 #1202/#1205):\n"
                    + String.join("\n", violations));
        }
    }

    /**
     * 定位 {@code src/main/java}：优先相对当前工作目录 (Maven surefire 默认 module basedir)，
     * 找不到则向上逐级找 {@code pom.xml} 所在的 cretas-api 模块目录。
     */
    private static Path resolveMainJavaRoot() {
        Path candidate = Paths.get("src/main/java");
        if (Files.isDirectory(candidate)) {
            return candidate.toAbsolutePath().normalize();
        }
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path probe = dir.resolve("src/main/java");
            if (Files.isDirectory(probe)) {
                return probe.normalize();
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "无法定位 src/main/java (cwd=" + Paths.get("").toAbsolutePath() + ") — guard test 无法运行");
    }
}
