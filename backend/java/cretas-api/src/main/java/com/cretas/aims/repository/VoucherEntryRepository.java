package com.cretas.aims.repository;

import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.finance.VoucherEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface VoucherEntryRepository extends JpaRepository<VoucherEntry, String> {

    List<VoucherEntry> findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc(String voucherId);

    void deleteByVoucherId(String voucherId);

    /**
     * H-BUG-3 (2026-06-21 transcript-e2e R1): 统计指定科目 (subjectCode) 在某工厂下被多少条
     * 凭证分录引用。用于 {@code AccountService.softDelete} 的引用阻删校验。
     *
     * <p>VoucherEntry 通过 {@code subject_code} 引用 Account.code (非 FK), 工厂归属经
     * {@code voucher.factoryId} 判定。{@code @Where(deleted_at IS NULL)} 已在 VoucherEntry
     * 与 Voucher 上强制软删过滤, 故只统计存活的分录/凭证 (含 DRAFT/POSTED/VOID —— 任何历史引用
     * 都意味着删除该科目会孤立历史凭证记录, 因此都阻删)。
     */
    @Query("SELECT COUNT(e) FROM VoucherEntry e JOIN e.voucher v " +
            "WHERE v.factoryId = :factoryId AND e.subjectCode = :subjectCode")
    long countBySubjectCodeAndFactory(@Param("factoryId") String factoryId,
                                      @Param("subjectCode") String subjectCode);

    /**
     * Sprint 6 W4-A: 按 auxiliaryEntityId 聚合一段时间内的分录, 计算借/贷/净余额/分录数.
     *
     * <p>过滤条件:
     * <ul>
     *   <li>factory_id = :factoryId (跨 voucher 表 JOIN)</li>
     *   <li>auxiliary_type = :auxiliaryType (e.g. CUSTOMER)</li>
     *   <li>voucher.voucherDate BETWEEN :startDate AND :endDate (业务日期, 非 createdAt)</li>
     *   <li>voucher.status != VOID (排除作废, DRAFT/POSTED 都纳入余额)</li>
     *   <li>voucher.deletedAt IS NULL (@Where 已强制, 此处显式以备 ad-hoc 调用)</li>
     *   <li>entry.auxiliaryEntityId IS NOT NULL (定义上 paired, 防御性)</li>
     * </ul>
     *
     * <p>JPQL 用 SELECT NEW 直接构造 DTO, 避免 Object[] 投射不安全 cast.
     *
     * <p>返回按 entryCount DESC 排序 (活跃实体在前), 同 count 时按 entityId 字典序.
     */
    @Query("SELECT new com.cretas.aims.dto.finance.AuxiliaryAggregateRow(" +
            "  e.auxiliaryType, e.auxiliaryEntityId, " +
            "  COALESCE(SUM(e.debit), 0), COALESCE(SUM(e.credit), 0), " +
            "  COALESCE(SUM(e.debit), 0) - COALESCE(SUM(e.credit), 0), " +
            "  COUNT(e)) " +
            "FROM VoucherEntry e JOIN e.voucher v " +
            "WHERE v.factoryId = :factoryId " +
            "  AND e.auxiliaryType = :auxiliaryType " +
            "  AND e.auxiliaryEntityId IS NOT NULL " +
            "  AND v.voucherDate BETWEEN :startDate AND :endDate " +
            "  AND v.status <> com.cretas.aims.entity.enums.VoucherStatus.VOID " +
            "  AND v.deletedAt IS NULL " +
            "GROUP BY e.auxiliaryType, e.auxiliaryEntityId " +
            "ORDER BY COUNT(e) DESC, e.auxiliaryEntityId ASC")
    List<com.cretas.aims.dto.finance.AuxiliaryAggregateRow> aggregateByAuxiliary(
            @Param("factoryId") String factoryId,
            @Param("auxiliaryType") AuxiliaryType auxiliaryType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Sprint 7 T3 报表三表: 按 subjectCode 聚合 voucher entries 在 [startDate, endDate].
     *
     * <p>过滤条件:
     * <ul>
     *   <li>voucher.factoryId = :factoryId</li>
     *   <li>voucher.voucherDate BETWEEN :startDate AND :endDate</li>
     *   <li>voucher.status != VOID</li>
     *   <li>voucher.deletedAt IS NULL + entry.deletedAt IS NULL (@Where 双重)</li>
     * </ul>
     *
     * <p>跟 BalanceSheet 不同, IncomeStatement 用本方法 — BalanceSheet 累计期初到月末全部,
     * IncomeStatement 仅取期间发生. 都用本方法, BalanceSheet 把 startDate 设为远古 (1970-01-01).
     *
     * <p>subjectName 取 max(e.subjectName), 避免同 code 在不同 voucher 里 name 抖动导致 GROUP BY 失败.
     */
    @Query("SELECT new com.cretas.aims.dto.finance.SubjectAggregateRow(" +
            "  e.subjectCode, MAX(e.subjectName), " +
            "  COALESCE(SUM(e.debit), 0), COALESCE(SUM(e.credit), 0), " +
            "  COUNT(e)) " +
            "FROM VoucherEntry e JOIN e.voucher v " +
            "WHERE v.factoryId = :factoryId " +
            "  AND v.voucherDate BETWEEN :startDate AND :endDate " +
            "  AND v.status <> com.cretas.aims.entity.enums.VoucherStatus.VOID " +
            "  AND v.deletedAt IS NULL " +
            "GROUP BY e.subjectCode " +
            "ORDER BY e.subjectCode ASC")
    List<com.cretas.aims.dto.finance.SubjectAggregateRow> aggregateBySubject(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 结转损益专用: 仅 POSTED 业务凭证按 subjectCode 聚合 [startDate, endDate].
     *
     * <p>与 {@link #aggregateBySubject} 区别:
     * <ul>
     *   <li>排除 DRAFT/REVERSED (只算已过账), 防把草稿计进结转;</li>
     *   <li><b>排除 PL_CLOSING 类型凭证</b> (结转凭证本身 + 其红冲镜像) —— 结转聚合的是
     *       <b>业务</b>损益发生额, 结转产物不算业务活动。</li>
     * </ul>
     *
     * <p><b>为什么必须排除 PL_CLOSING</b> (2026-06-25 reopen→reclose 双计 bug):
     * 反结账 (reversePeriodClosing → voidVoucher) 把原结转凭证置 REVERSED (本查询已排除),
     * 但同时 POST 一张借贷互换的红冲镜像 (voucherType 仍是 PL_CLOSING, status=POSTED)。
     * 若不排 PL_CLOSING, 该镜像的 6xxx 分录会漏进聚合 → 再结转时把 6xxx 双计
     * (原结转 REVERSED 被排, 镜像 POSTED 被计, 二者不相抵)。排除 PL_CLOSING 后,
     * 聚合永远只看业务 6xxx, reopen→reclose 任意多次都得正确净额。
     *
     * <p><b>COST_CARRYOVER (结转成本) 的差异化处理</b>: 与 PL_CLOSING 不同, COST_CARRYOVER
     * 的主凭证 (借 6401 主营业务成本 / 贷 1405) 是<b>真实业务发生额</b> (期末权责化的销售成本),
     * <b>必须</b>被本聚合计入 → 让 6401 经结转损益进 4103 (修复"毛利=收入")。
     * 但它的<b>红冲镜像</b> (反结账产生, originalVoucherId 非空, voucherType 仍是 COST_CARRYOVER,
     * status=POSTED) 会重蹈 PL_CLOSING 的双计覆辙 (原 COST_CARRYOVER 置 REVERSED 被排,
     * 镜像 POSTED 被计, 二者不相抵) → reopen→reclose 时 6401 被镜像抵消回 0。
     * 故这里<b>只排 COST_CARRYOVER 的红冲镜像</b> (originalVoucherId IS NOT NULL),
     * 保留其主凭证。这样 first-close 时 6401 计入, reopen→reclose 时 (原→REVERSED 排 +
     * 镜像→本条排 + 新主凭证→计) 净额恒等于新一轮的真实 COGS。
     */
    @Query("SELECT new com.cretas.aims.dto.finance.SubjectAggregateRow(" +
            "  e.subjectCode, MAX(e.subjectName), " +
            "  COALESCE(SUM(e.debit), 0), COALESCE(SUM(e.credit), 0), " +
            "  COUNT(e)) " +
            "FROM VoucherEntry e JOIN e.voucher v " +
            "WHERE v.factoryId = :factoryId " +
            "  AND v.voucherDate BETWEEN :startDate AND :endDate " +
            "  AND v.status = com.cretas.aims.entity.enums.VoucherStatus.POSTED " +
            "  AND v.voucherType <> com.cretas.aims.entity.enums.VoucherType.PL_CLOSING " +
            "  AND NOT (v.voucherType = com.cretas.aims.entity.enums.VoucherType.COST_CARRYOVER " +
            "           AND v.originalVoucherId IS NOT NULL) " +
            "  AND v.deletedAt IS NULL " +
            "GROUP BY e.subjectCode " +
            "ORDER BY e.subjectCode ASC")
    List<com.cretas.aims.dto.finance.SubjectAggregateRow> aggregateBySubjectPosted(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Sprint 7 T3 现金流量表: 找出指定现金科目在期间内的 voucher entries + 对手科目列表.
     *
     * <p>返回 voucher_id 列表 — caller 拉 voucher 详情找对手 entry.
     * 比 N+1 query 更优雅的写法: 直接拉 entries 由 caller filter 现金 vs 非现金.
     *
     * <p>过滤现金 voucher: voucher 内至少有 1 entry subjectCode IN (...).
     */
    @Query("SELECT DISTINCT v.id FROM Voucher v JOIN v.entries e " +
            "WHERE v.factoryId = :factoryId " +
            "  AND v.voucherDate BETWEEN :startDate AND :endDate " +
            "  AND v.status <> com.cretas.aims.entity.enums.VoucherStatus.VOID " +
            "  AND v.deletedAt IS NULL " +
            "  AND e.subjectCode IN :cashCodes")
    List<String> findCashFlowVoucherIds(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("cashCodes") List<String> cashCodes);

    /**
     * 资金段子账↔总账对账 (finance audit Bug 5): 某科目族 (subjectCode LIKE prefix) 在非作废凭证下的
     * 净借方余额 = Σ debit − Σ credit (跨全部历史, 与 sumReceivables/sumPayables 的累计口径一致)。
     *
     * <p>用途: 月结对账比对 —— 应收 (1122, 借方常态) net-debit 应 ≈ AR 子账余额;
     * 应付 (2202, 贷方常态) 取 net-debit 后取负 ≈ AP 子账余额。二者偏差 = 两套账漂移
     * (手工调整无凭证 / 凭证生成失败 / 历史数据), 由月结 WARNING 暴露。
     *
     * <p>含 DRAFT+POSTED+REVERSED (排 VOID), 与 {@link #aggregateBySubject} 一致:
     * REVERSED 原凭证 + 其 POSTED 红冲镜像相互抵消, 净额自洽。
     */
    @Query("SELECT COALESCE(SUM(e.debit), 0) - COALESCE(SUM(e.credit), 0) " +
            "FROM VoucherEntry e JOIN e.voucher v " +
            "WHERE v.factoryId = :factoryId " +
            "  AND e.subjectCode LIKE :subjectPrefix " +
            "  AND v.status <> com.cretas.aims.entity.enums.VoucherStatus.VOID " +
            "  AND v.deletedAt IS NULL")
    BigDecimal sumNetDebitBySubjectPrefix(
            @Param("factoryId") String factoryId,
            @Param("subjectPrefix") String subjectPrefix);
}
