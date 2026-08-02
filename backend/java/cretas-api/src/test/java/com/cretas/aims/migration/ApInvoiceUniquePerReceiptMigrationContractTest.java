package com.cretas.aims.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应付发票唯一性: 从「每采购订单一条」改成「每收货单一条」。
 *
 * <p><b>为什么必须改</b>: 分批到货的采购订单, 第二批<b>永远入不了库</b>。prod 实证
 * (F006 PO-20260707-0002, 10kg 分两批):
 * <pre>
 *   7/07 第一批 3.456kg → 确认收货 → 记应付 42.65 元 ✓
 *   8/03 第二批 6.544kg → 确认收货 → 再记 80.75 元 → 撞 uk_aat_ap_invoice_per_po → 整事务回滚 → 货没入库
 * </pre>
 * 服务端日志逐条对上: 先打 {@code INFO 自动应付挂账(实收值): receivedValue=80.75}(代码以为成功了),
 * 下一毫秒 flush 才 {@code ERROR duplicate key ... uk_aat_ap_invoice_per_po}, 最后被全局兜底成
 * 通用 {@code 409 数据已存在, 请勿重复提交} —— 报错完全指不到真因。
 *
 * <p><b>为什么是约束错而不是代码错</b>: {@code PurchaseServiceImpl#confirmReceive} 的注释
 * (2026-07-02 doomed-tx 修复) 白纸黑字写着「幂等键改为每张入库单 (PURCHASE_RECEIVE, receiveId):
 * 同一入库单重复确认不重复挂账; 分批入库<b>各挂各的</b>实收值」。「各挂各的」= 一个 PO 多条 AP_INVOICE,
 * 正是旧约束禁止的。当时<b>只改了代码没改约束</b>, 所以那次修复从未真正生效 ——
 * 症状只是从「抛 BusinessException」变成「insert 时炸」, 现象一模一样。
 *
 * <p><b>prod 干跑双向验证</b>(事务内, 已回滚): 换约束后第二批那条 AP 能插入(同一 PO 变 2 条);
 * 同一收货单再插一条仍被唯一约束挡住(幂等保持)。
 *
 * <p><b>存量影响</b>: 全库 96 条 AP_INVOICE, 同一 PO 多条的现存 0 条 → 新约束不撞历史数据;
 * 49 条 source_id 为 NULL 的 legacy 行不纳入本约束, 保持现状。
 */
@DisplayName("迁移契约 — 应付发票按收货单唯一")
class ApInvoiceUniquePerReceiptMigrationContractTest {

    private static final Path SQL = Path.of("src", "main", "resources", "db", "flyway",
            "V20261029_49__ap_invoice_unique_per_receipt.sql");

    private String sql() throws Exception {
        assertThat(SQL).as("迁移文件必须存在").exists();
        return Files.readString(SQL);
    }

    @Test
    @DisplayName("回归: 必须建立按收货单的新唯一约束")
    void createsPerReceiptUniqueIndex() throws Exception {
        String sql = sql();
        assertThat(sql).as("新约束名").contains("uk_aat_ap_invoice_per_receipt");
        assertThat(sql.replaceAll("\\s+", " "))
                .as("唯一键必须是 (factory_id, source_type, source_id) —— 与代码幂等键 (PURCHASE_RECEIVE, receiveId) 一致")
                .contains("(factory_id, source_type, source_id)");
    }

    @Test
    @DisplayName("回归: 必须删除旧的「每 PO 一条」约束, 否则分批收货照样入不了库")
    void dropsLegacyPerPurchaseOrderIndex() throws Exception {
        assertThat(sql())
                .as("旧约束不删则第二批收货仍然撞唯一键 —— 这条正是本次修复的目标")
                .contains("DROP INDEX")
                .contains("uk_aat_ap_invoice_per_po");
    }

    @Test
    @DisplayName("幂等不能丢: 新约束仍须限定 AP_INVOICE + 未删除")
    void keepsPartialPredicateSoIdempotencyHolds() throws Exception {
        String flat = sql().replaceAll("\\s+", " ");
        assertThat(flat).as("只约束应付发票, 不误伤其它交易类型")
                .contains("transaction_type = 'AP_INVOICE'");
        assertThat(flat).as("软删除的行不参与唯一性, 否则冲销后无法重开")
                .contains("deleted_at IS NULL");
        assertThat(flat).as("source_id 为空的 legacy 行不纳入, 否则 49 条历史行会互相冲突")
                .contains("source_id IS NOT NULL");
    }

    @Test
    @DisplayName("顺序: 先建新约束再删旧的 —— 不留「旧的删了新的没建上」的中间态")
    void createsBeforeDropSoNoUnprotectedWindow() throws Exception {
        String sql = sql();
        int create = sql.indexOf("CREATE UNIQUE INDEX");
        int drop = sql.indexOf("DROP INDEX");
        assertThat(create).as("必须有 CREATE").isGreaterThanOrEqualTo(0);
        assertThat(drop).as("必须有 DROP").isGreaterThanOrEqualTo(0);
        assertThat(create)
                .as("若先 DROP 再 CREATE, 历史数据违反新约束时会留下完全无保护的表")
                .isLessThan(drop);
    }
}
