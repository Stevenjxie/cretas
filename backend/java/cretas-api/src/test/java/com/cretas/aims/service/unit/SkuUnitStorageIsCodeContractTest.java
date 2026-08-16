package com.cretas.aims.service.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKU 单位<b>存英文码</b>，原料与成品两侧必须同向。
 *
 * <h2>为什么是码不是中文（Steve 2026-08-02 拍板）</h2>
 *
 * <p>别名映射是<b>多对一</b>：{@code alias("pcs","件","个","只","pc","piece","pieces")}
 * —— 七种写法归一到一个 {@code pcs}。存码只有一个规范形，展示层挑一个中文词显示；
 * 存中文就<b>没有</b>规范形，库里同时有「个/只/件」，比较、去重、换算都得先猜哪个是主。
 *
 * <p>prod 数据直接印证：原料里 个67 + 只3 + 件2 是三个词对同一个码；
 * kg598 + KG28 + 公斤2 是同一个单位的三种写法。
 *
 * <h2>这条测试守什么</h2>
 *
 * <p>历史事故是<b>两侧方向相反</b>：原料侧 {@code normalizeInventoryUnit} 早就返回
 * {@code normalized.code()}，而 {@code V20261029_32} 迁移把数据改成中文，
 * 成品侧写入干脆<b>裸传不归一</b> —— prod 实测原料 660 英文 / 成品 629 中文。
 * 客户撞到的「报工单位<b>袋</b>，BOM 单位 <b>bag</b>」409 就是这么来的：
 * 不是数据脏，是两边存的形态不一样。
 *
 * <h2>🔴 2026-08-16 订正：守的不再是「都归到码」，是「都走同一个权威出口」</h2>
 *
 * <p>本测试原先钉的是字面 {@code return normalized.code();}。那是<b>当时</b>的实现，
 * 而两侧后来都被<b>有意</b>迁到了 {@code UnitContractService#storageUnit} ——
 * 因为 {@code code()} 会把「只」写成 {@code pcs}、把工厂自定义单位写成自动生成的拼音码。
 * 「件/个/只」是<b>三个独立单位</b>（2026-08-14 实测：两厂 50 个包材因此加不进 BOM）。
 *
 * <p>{@code storageUnit} 的三条规则：①权威表认不出 → 原样保留；②同码多中文写法
 * （实测只有 {@code pcs}: 件/个/只）或工厂自定义单位 → <b>保中文字面</b>；③内置单位 → 存英文码。
 * ⇒ 「都归到码」这个说法本身已经不成立，它只在规则③下为真。
 *
 * <p>于是这两条断言从「守着一个已经被判定为缺陷的旧行为」变成了阻挡，在
 * {@code origin/main} 上一直是红的（且因为 CI 选择器不跑它，红了两天没人看见）。
 * 现在把守的东西从<b>字面</b>抬到<b>性质</b>：
 * 「<b>两侧都归一，且经由同一个权威出口</b>」，并各配一条阴性对照钉住「不许退回 code()」。
 * 哪天有人把任一侧改回裸传、或绕开 {@code storageUnit}，这里立刻红。
 */
@DisplayName("SKU 单位存储契约：两侧都走同一个权威出口 storageUnit")
class SkuUnitStorageIsCodeContractTest {

    private static final Path RAW = Paths.get(
            "src/main/java/com/cretas/aims/service/impl/RawMaterialTypeServiceImpl.java");
    private static final Path PRODUCT = Paths.get(
            "src/main/java/com/cretas/aims/service/impl/ProductTypeServiceImpl.java");
    private static final Path MIGRATION = Paths.get(
            "src/main/resources/db/flyway/V20261029_85__normalize_sku_units_to_chinese_codes.sql");

    private String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("阳性对照: 三份源码都读得到")
    void positiveControl() throws IOException {
        assertThat(read(RAW)).contains("class RawMaterialTypeServiceImpl");
        assertThat(read(PRODUCT)).contains("class ProductTypeServiceImpl");
        assertThat(read(MIGRATION)).contains("backup_sku_units_zh_20260814");
    }

    @Test
    @DisplayName("🔴 原料侧把落库字面交给权威函数（既有行为，别被改回去）")
    void rawMaterialNormalizesToCode() throws IOException {
        String src = read(RAW);
        assertThat(src).contains("private String normalizeInventoryUnit(");
        assertThat(src)
                .as("原料侧落库字面必须由 UnitContractService#storageUnit 决定")
                .contains("unitContractService.storageUnit(factoryId, value)");
        assertThat(src)
                .as("⛔ 不许退回 normalized.code() —— 它把「只」写成 pcs, 把自定义单位写成拼音码")
                .doesNotContain("return normalized.code();");
        assertThat(src).contains("dto.setUnit(normalizeInventoryUnit(");
    }

    @Test
    @DisplayName("🔴 成品侧必须走同一个权威函数 —— 此前是裸传, 两侧方向相反正是 409 的成因")
    void productTypeNormalizesToo() throws IOException {
        String src = read(PRODUCT);
        assertThat(src)
                .as("成品创建裸传 dto.getUnit() → 与原料侧方向相反")
                .doesNotContain("productType.setUnit(dto.getUnit());");
        assertThat(src)
                .as("成品更新同样不许裸传")
                .doesNotContain("if (dto.getUnit() != null) productType.setUnit(dto.getUnit());");
        assertThat(src).contains("private String normalizeProductUnit(");
        assertThat(src)
                .as("成品侧与原料侧必须是【同一个】权威函数, 否则两侧还会漂")
                .contains("unitContractService.storageUnit(factoryId, value)");
        assertThat(src)
                .as("⛔ 成品侧同样不许退回 normalized.code()")
                .doesNotContain("normalized.recognized() ? normalized.code() : value");
    }

    @Test
    @DisplayName("🔑 两侧钉的是【同一个】权威出口 —— 这条才是「不会漂」的真正保证")
    void bothSidesRouteThroughTheSameAuthority() throws IOException {
        String authority = "unitContractService.storageUnit(factoryId, value)";
        // 抽不成一份(两个 Service 各写各的), 就由这条闸钉住「两份指向同一个出口」。
        assertThat(read(RAW)).as("原料侧").contains(authority);
        assertThat(read(PRODUCT)).as("成品侧").contains(authority);
        // 阳性对照: 这个串确实是从源码里读出来的, 不是笔误成了空断言
        assertThat(read(RAW)).contains("private String normalizeInventoryUnit(");
        assertThat(read(PRODUCT)).contains("private String normalizeProductUnit(");
    }

    @Test
    @DisplayName("⛔ 成品侧刻意比原料宽松: 认不出原样留, 不抛 400 也不兜底成 kg")
    void productSideStaysPermissive() throws IOException {
        String src = read(PRODUCT);
        int begin = src.indexOf("private String normalizeProductUnit(");
        assertThat(begin).isGreaterThan(0);
        String fn = src.substring(begin, Math.min(begin + 1400, src.length()));
        assertThat(fn)
                .as("成品单位不合法不该挡住建品 —— 库里真有「半只」这种规格值")
                .doesNotContain("throw new BusinessException");
        assertThat(fn)
                .as("空值不许兜底成 kg —— 成品按件不计量是合法的, 编个 kg 会让下游按重量算")
                .doesNotContain("\"kg\"");
    }

    @Test
    @DisplayName("迁移与权威表同源: 别名表条目必须能对上 systemAliases")
    void migrationAliasesMatchAuthority() throws IOException {
        String sql = read(MIGRATION);
        // 抽查几个跨语言别名, 确认迁移抄的是权威表那份
        for (String pair : new String[] {
                "('pcs','件')", "('portion','份')", "('slice','片')",
                "('bag','袋')", "('case','箱')", "('box','盒')",
                "('crate','框')", "('sheet','张')", "('tray','托盘')" }) {
            assertThat(sql).as("迁移别名表缺 %s", pair).contains(pair);
        }
    }

    @Test
    @DisplayName("⛔ 迁移不许猜: 映射不出的行原样留下并报出来")
    void migrationDoesNotGuess() throws IOException {
        String sql = read(MIGRATION);
        assertThat(sql)
                .as("必须 JOIN 别名表(只动能映射的), 不许用 CASE 兜底瞎折")
                .contains("JOIN _zh_alias a ON a.en = lower(t.unit)");
        assertThat(sql)
                .as("映射不出的要 RAISE NOTICE 点名, 否则没人知道剩了什么")
                .contains("未归一(映射不出, 需人工定)");
        assertThat(sql).as("必须有台账才能回滚").contains("backup_sku_units_zh_20260814");
    }
}
