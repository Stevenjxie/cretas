package com.cretas.aims.entity.enums;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用门禁: 「Java 枚举加了值, 但 DB CHECK 白名单没跟上」的漂移类。
 *
 * <p>这个 pattern 在本仓库已发作 8 次 —— V20261027_15 / _29 / _34 / _35 / _37、
 * V20260822_04, 以及 V20261029_33 一次扫出的 3 条 (ck_sdr_status / chk_att_entity_type /
 * ck_iti_type)。前 6 次都是「客户撞到 500 → 查日志 → 单点补一条 widening migration →
 * 补一条只盯这一个枚举的测试」。本测试把它一次性通用化: 不再逐个枚举写一个测试类。</p>
 *
 * <p><b>为什么只能静态检查</b>: CHECK 约束是 PG-only, H2 单测与 mock repository 都碰不到;
 * 而 CI 的 Postgres service container 上<b>并不跑 Flyway</b> (IT 库的 schema 来自
 * {@code r10-it-schema.sql} + ddl-auto), 所以 CI 里不存在「全部 migration 已应用」的库
 * 可供查询 {@code pg_constraint}。于是本测试改为在编译/CI 期断言:
 * Flyway 语料里每个「纯白名单式」CHECK 约束, 都必须列出对应枚举的<b>全部</b>常量。</p>
 *
 * <p><b>覆盖边界 (务必知情)</b>:</p>
 * <ul>
 *   <li>只看 Flyway 语料。只存在于 {@code database/*.sql} (未纳入版本管理的 bootstrap
 *       脚本) 的约束看不见 —— {@code ck_iti_type} 和 {@code ck_it_status} 就是这么漏掉的。
 *       所以 V20261029_33 顺带把 ck_iti_type 纳入了 Flyway。</li>
 *   <li>只判「纯白名单」: CHECK body 除了一个 {@code col IN/= ANY} 白名单 (可带
 *       {@code col IS NULL OR} 守卫) 之外没有别的条件。复合业务规则里的 ARRAY 是子句
 *       而非列的值域, 判它必然假阳性, 一律跳过。</li>
 *   <li>只判 {@code @Enumerated(EnumType.STRING)} 字段 (ORDINAL 存的是 int, 无关)。</li>
 * </ul>
 *
 * <p><b>活库全量对账</b>用 {@code scripts/audit/enum_check_constraint_drift.py} —— 它直查
 * {@code pg_constraint}, 没有上面这些边界, 但需要连到目标库。发布前跑一次。</p>
 */
class EnumCheckConstraintDriftTest {

    /**
     * 解析器坏掉时的兜底: 若哪天正则/扫描静默失效, 配对数会掉到 0, 断言就"没有漂移"
     * 而假绿 —— 与「无效篡改冒充通过」同一类陷阱。2026-07-30 实测配上 87 组
     * (Flyway 语料 116 条纯白名单 × 反射扫出 332 个枚举列), 下限取约一半:
     * 只要求「明显还在工作」, 不需要跟着新增实体逐次上调。
     */
    private static final int MIN_EXPECTED_PAIRS = 50;

    // ── 1) SQL 侧: 从 Flyway 语料里取每个约束「版本最高」的那份定义 ──────────────

    /** {@code ADD CONSTRAINT n CHECK (…)} 与 CREATE TABLE 内联 {@code CONSTRAINT n CHECK (…)}。 */
    private static final Pattern CONSTRAINT_DECL = Pattern.compile(
            "CONSTRAINT\\s+(\\w+)\\s+CHECK\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** 白名单子句: 可选括号包裹的列名, 可选 ::text 转型, 然后 {@code = ANY (} 或 {@code IN (}。 */
    private static final Pattern WHITELIST = Pattern.compile(
            "\\(?\\b([a-z_][a-z0-9_]*)\\b\\)?(?:\\s*::\\s*(?:text|character\\s+varying))?"
                    + "\\s*(?:=\\s*ANY\\s*\\(|IN\\s*\\()",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OWNING_TABLE = Pattern.compile(
            "(?:ALTER|CREATE)\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:public\\.)?\"?(\\w+)\"?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LITERAL = Pattern.compile("'((?:[^']|'')*)'");

    private record Whitelist(String constraint, String table, String column,
                             Set<String> allowed, String source) {
    }

    // ── 2) 实体侧: 反射拿 (表, 列) → 枚举 ─────────────────────────────────────

    private record EnumColumn(String table, String column, Class<?> enumType,
                              String declaringClass, String fieldName) {
    }

    @Test
    void everyFlywayWhitelistCoversItsEnumEntirely() throws Exception {
        Map<String, Whitelist> whitelists = parseFlywayWhitelists();
        Map<String, EnumColumn> enumColumns = scanEnumColumns();

        List<String> drift = new ArrayList<>();
        int pairs = 0;

        for (Whitelist w : whitelists.values()) {
            EnumColumn ec = enumColumns.get(key(w.table(), w.column()));
            if (ec == null) {
                continue; // 该列不是 @Enumerated(STRING) 映射, 无枚举可对
            }
            pairs++;
            List<String> missing = new ArrayList<>();
            for (Object constant : ec.enumType().getEnumConstants()) {
                String name = ((Enum<?>) constant).name();
                if (!w.allowed().contains(name)) {
                    missing.add(name);
                }
            }
            if (!missing.isEmpty()) {
                drift.add(String.format(
                        "%n  %s.%s [%s] (定义于 %s)%n"
                                + "    枚举 %s (%s#%s) 有 %d 个值, 约束只放行 %d 个%n"
                                + "    DB 会拒绝: %s%n"
                                + "    → 加一条 widening migration, DROP CONSTRAINT IF EXISTS 后按枚举全量重建。",
                        w.table(), w.column(), w.constraint(), w.source(),
                        ec.enumType().getSimpleName(), ec.declaringClass(), ec.fieldName(),
                        ec.enumType().getEnumConstants().length, w.allowed().size(),
                        String.join(", ", missing)));
            }
        }

        final int matchedPairs = pairs;
        assertTrue(matchedPairs >= MIN_EXPECTED_PAIRS, () -> String.format(
                "只配上 %d 组 (约束 ↔ 枚举), 少于下限 %d —— 大概率是本测试的 SQL 解析或实体扫描"
                        + "坏了, 而不是真的没有漂移。空跑会假绿, 所以这里直接失败。"
                        + " (解析到 %d 条纯白名单约束, %d 个枚举列)",
                matchedPairs, MIN_EXPECTED_PAIRS, whitelists.size(), enumColumns.size()));

        assertTrue(drift.isEmpty(), () -> "枚举 ↔ DB CHECK 白名单漂移 "
                + drift.size() + " 处 —— 代码写得进, PG 会拒:" + String.join("", drift));
    }

    // ── 3) 三条已知漂移的具名回归 (通用扫描若被改动, 意图仍然留痕) ─────────────

    @Test
    void salesDeliveryStatusWhitelistCoversParentChildStates() throws Exception {
        Whitelist w = requireWhitelist("ck_sdr_status");
        for (SalesDeliveryStatus s : SalesDeliveryStatus.values()) {
            assertTrue(w.allowed().contains(s.name()),
                    "ck_sdr_status 缺 '" + s.name() + "' —— V20261028_87 母子发运单只加了列没扩状态白名单, "
                            + "导致「销售订单→明细→新建发货单」自 2026-07-20 起 100% 报 500 (追踪码 200102BA)。");
        }
        assertTrue(w.allowed().contains("PENDING_SPLIT"),
                "PENDING_SPLIT 是 createDeliveryRecord 带 salesOrderId 时的初始状态, 缺它等于整条建单路径不可用。");
    }

    @Test
    void attachmentEntityTypeWhitelistCoversCustomerSuppliedReceipt() throws Exception {
        Whitelist w = requireWhitelist("chk_att_entity_type");
        assertTrue(w.allowed().contains("CUSTOMER_SUPPLIED_RECEIPT"),
                "缺 CUSTOMER_SUPPLIED_RECEIPT —— 客供料收货凭证传不上去, "
                        + "SalesOrderSuppliedMaterialRequirementService「至少一张该类型附件」那道闸就永远过不了。");
    }

    @Test
    void transferItemTypeWhitelistCoversPackagingMaterial() throws Exception {
        Whitelist w = requireWhitelist("ck_iti_type");
        for (TransferItemType t : TransferItemType.values()) {
            assertTrue(w.allowed().contains(t.name()),
                    "ck_iti_type 缺 '" + t.name() + "' —— TransferServiceImpl 直接 valueOf(dto) 落库, "
                            + "客户一做包材调拨就撞。该约束原本只在 database/p3_transfer_pricelist_pg.sql, "
                            + "已由 V20261029_33 纳入 Flyway。");
        }
    }

    @Test
    void wideningMigrationsAreIdempotent() throws Exception {
        for (String name : List.of("ck_sdr_status", "chk_att_entity_type", "ck_iti_type")) {
            String sql = readMigration(requireWhitelist(name).source());
            assertTrue(sql.contains("DROP CONSTRAINT IF EXISTS " + name),
                    name + " 的 widening migration 必须先 DROP CONSTRAINT IF EXISTS 再 ADD, "
                            + "否则在已手工放开过的环境上重跑会失败。");
        }
    }

    @Test
    void wideningMigrationGuardsAgainstMissingTablesOnFreshDatabases() throws Exception {
        String sql = readMigration(requireWhitelist("ck_sdr_status").source());
        assertTrue(sql.contains("to_regclass"),
                "全新 CI DB 上 Flyway 先于 Hibernate ddl-auto 跑, 表还不存在时裸 ALTER 会报 "
                        + "\"relation does not exist\" 并阻断启动 —— 必须有 to_regclass 存在性守卫 "
                        + "(沿用 V20260822_04 的做法)。");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Whitelist requireWhitelist(String constraintName) throws Exception {
        Whitelist w = parseFlywayWhitelists().get(constraintName);
        assertFalse(w == null,
                "Flyway 语料里找不到纯白名单式约束 " + constraintName
                        + " —— 它可能被改成了复合条件, 或只剩在 database/*.sql 里 (那样本测试看不见)。");
        return w;
    }

    /** 按版本升序遍历, 同名约束以最后一份 (版本最高) 为准。 */
    private static Map<String, Whitelist> parseFlywayWhitelists() throws IOException {
        Path dir = new ClassPathResource("db/flyway").getFile().toPath();
        Map<String, Whitelist> byName = new LinkedHashMap<>();

        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(EnumCheckConstraintDriftTest::versionKey))
                    .toList();
        }

        for (Path file : files) {
            String sql = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = CONSTRAINT_DECL.matcher(sql);
            while (m.find()) {
                String name = m.group(1);
                String body = balanced(sql, m.end() - 1, '(', ')');
                if (body == null) {
                    continue;
                }
                String table = owningTable(sql, m.start());
                if (table == null) {
                    continue;
                }
                Whitelist w = pureWhitelist(name, table, body, file.getFileName().toString());
                if (w != null) {
                    byName.put(name, w);
                } else {
                    // 变成复合条件 / 非白名单 → 撤掉旧版本的判定, 不要拿过期定义误判
                    byName.remove(name);
                }
            }
        }
        return byName;
    }

    /**
     * 只有「CHECK body 去掉唯一一个白名单子句和可选的 IS NULL/OR 守卫后什么都不剩」
     * 才算纯白名单。否则 ARRAY 是复合业务规则的子句, 判它必假阳性。
     */
    private static Whitelist pureWhitelist(String name, String table, String body, String source) {
        Matcher m = WHITELIST.matcher(body);
        String column = null;
        Set<String> allowed = null;
        int hits = 0;
        while (m.find()) {
            hits++;
            String inner = balanced(body, m.end() - 1, '(', ')');
            if (inner == null) {
                return null;
            }
            int lb = inner.indexOf('[');
            String values = lb >= 0 ? balanced(inner, lb, '[', ']') : inner; // ARRAY[...] 或 IN (...)
            if (values == null) {
                return null;
            }
            Set<String> lits = new LinkedHashSet<>();
            Matcher lm = LITERAL.matcher(values);
            while (lm.find()) {
                lits.add(lm.group(1));
            }
            if (lits.isEmpty()) {
                return null;
            }
            column = m.group(1).toLowerCase(Locale.ROOT);
            allowed = lits;
        }
        if (hits != 1) {
            return null;
        }

        String residue = WHITELIST.matcher(body).replaceAll("");
        residue = residue.replaceAll("(?is)ARRAY\\s*\\[[^\\]]*\\]", "");
        residue = residue.replaceAll("(?is)'(?:[^']|'')*'", "");
        residue = residue.replaceAll("(?is)::\\s*(?:text|character\\s+varying)(?:\\s*\\[\\s*\\])?", "");
        residue = residue.replaceAll("(?is)\\b[a-z_][a-z0-9_]*\\s+IS\\s+NULL\\b", "");
        residue = residue.replaceAll("(?is)\\bOR\\b", "");
        residue = residue.replaceAll("[\\s(),]", "");
        if (!residue.isEmpty()) {
            return null;
        }
        return new Whitelist(name, table, column, allowed, source);
    }

    private static String owningTable(String sql, int declStart) {
        Matcher m = OWNING_TABLE.matcher(sql);
        String last = null;
        while (m.find()) {
            if (m.start() > declStart) {
                break;
            }
            last = m.group(1).toLowerCase(Locale.ROOT);
        }
        return last;
    }

    private static Map<String, EnumColumn> scanEnumColumns() throws ClassNotFoundException {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        Map<String, EnumColumn> out = new LinkedHashMap<>();
        for (BeanDefinition bd : provider.findCandidateComponents("com.cretas.aims")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            Table table = type.getAnnotation(Table.class);
            if (table == null || table.name().isBlank()) {
                continue;
            }
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!f.getType().isEnum()) {
                        continue;
                    }
                    Enumerated enumerated = f.getAnnotation(Enumerated.class);
                    if (enumerated == null || enumerated.value() != EnumType.STRING) {
                        continue; // ORDINAL 存 int, 与字符串白名单无关
                    }
                    Column col = f.getAnnotation(Column.class);
                    String column = (col != null && !col.name().isBlank())
                            ? col.name() : camelToSnake(f.getName());
                    out.putIfAbsent(key(table.name(), column),
                            new EnumColumn(table.name().toLowerCase(Locale.ROOT),
                                    column.toLowerCase(Locale.ROOT), f.getType(),
                                    type.getSimpleName(), f.getName()));
                }
            }
        }
        return out;
    }

    private static String key(String table, String column) {
        return table.toLowerCase(Locale.ROOT) + "." + column.toLowerCase(Locale.ROOT);
    }

    private static String camelToSnake(String s) {
        return s.replaceAll("(?<!^)(?=[A-Z])", "_").toLowerCase(Locale.ROOT);
    }

    /** 从 s[open] 处的开括号起, 返回配平后的内部内容 (不含首尾括号)。 */
    private static String balanced(String s, int open, char opener, char closer) {
        if (open < 0 || open >= s.length() || s.charAt(open) != opener) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == opener) {
                depth++;
            } else if (ch == closer) {
                depth--;
                if (depth == 0) {
                    return s.substring(open + 1, i);
                }
            }
        }
        return null;
    }

    /** 去掉 {@code --} 行注释, 避免注释里的示例 SQL 被当成真定义。 */
    private static String stripComments(String sql) {
        return sql.replaceAll("(?m)--[^\\n]*", "");
    }

    /** {@code V20261029_33__x.sql} → {@code 20261029.0033}, 保证数值序而非字典序。 */
    private static String versionKey(Path p) {
        String name = p.getFileName().toString();
        int sep = name.indexOf("__");
        String v = (sep > 0 ? name.substring(0, sep) : name).replaceFirst("^[Vv]", "");
        StringBuilder sb = new StringBuilder();
        for (String part : v.split("[._]")) {
            sb.append(String.format("%010d.", part.matches("\\d+") ? Long.parseLong(part) : 0L));
        }
        return sb.toString();
    }

    private static String readMigration(String fileName) {
        try {
            Path dir = new ClassPathResource("db/flyway").getFile().toPath();
            return Files.readString(dir.resolve(fileName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void versionKeyOrdersNumericallyNotLexicographically() {
        // 若退化成字典序, V20261029_9 会排在 V20261029_33 之后, 取到过期定义。
        assertTrue(versionKey(Path.of("V20261029_9__a.sql"))
                .compareTo(versionKey(Path.of("V20261029_33__b.sql"))) < 0,
                "版本比较必须是数值序: _9 应排在 _33 之前。");
        assertEquals(-1, Integer.signum(versionKey(Path.of("V20260822_04__a.sql"))
                .compareTo(versionKey(Path.of("V20261029_33__b.sql")))));
    }
}
