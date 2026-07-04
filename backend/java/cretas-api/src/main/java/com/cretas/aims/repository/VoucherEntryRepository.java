package com.cretas.aims.repository;

import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.finance.VoucherEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
     *
     * <p>⚠️ F006 财务审计 Bug 6 (2026-07-04): 本方法 <b>包含 DRAFT</b> (未财审草稿) — 官方财务报表
     * (资产负债表/利润表/科目余额表/试算平衡表/总账/明细账) <b>不应再使用本方法</b>, 已全部改用
     * {@link #aggregateBySubjectExcludingDraft} / {@link #aggregateBySubjectExcludingDraftAndClosing}
     * (人工审核制: DRAFT = 待财务审核, 计入官方报表会把"未审核"误当"已发生")。本方法保留仅供潜在的
     * 内部/管理层预览视图 (若未来需要"含未审核草稿"的口径), 当前代码库无此类调用方。
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
     * F006 财务审计 Bug 6 (2026-07-04): 官方财务报表 (资产负债表/科目余额表/总账/试算平衡表/明细账)
     * 专用 — 排除 DRAFT (未财审草稿), 但<b>保留 REVERSED</b>.
     *
     * <p>与 {@link #aggregateBySubject} 的唯一区别: 多排除 DRAFT。与 {@link #aggregateBySubjectPosted}
     * 的关键区别: <b>不</b>排除 REVERSED, 也<b>不</b>按 voucherType 排除 PL_CLOSING/COST_CARRYOVER
     * 红冲镜像。
     *
     * <p><b>为什么必须保留 REVERSED</b> (红字冲销 "金蝶规范", 见 {@code VoucherServiceImpl.reversePostedVoucher}):
     * 已过账凭证不可直接抹掉, 而是原凭证标记 REVERSED (状态标记, 财务效应<b>不</b>清零) + 生成一张
     * 借贷互换的红字冲销凭证 (POSTED, 同期). 两者<b>都需计入</b>聚合才能借贷互相抵消归零
     * (原凭证 debit X 仍计 + 冲销凭证 credit X 计 → 该科目净额归零)。若像
     * {@link #aggregateBySubjectPosted} 那样用 {@code status = POSTED} 排除 REVERSED, 则原凭证的
     * 财务效应被抹掉、只剩冲销凭证的相反分录被计入 → 净额变成"倍数冲销"(双重错误), 而非正确的零。
     * 这不是假设 — {@code SalesOrderVoucherListener}/{@code ReturnOrderVoucherListener} 复用同一
     * {@code voidVoucher→reversePostedVoucher} 机制处理销售/退货凭证撤销, 是日常高频业务场景。
     *
     * <p><b>为什么不排除 PL_CLOSING/COST_CARRYOVER</b>: 资产负债表/总账/科目余额表/试算平衡表/明细账
     * 要反映<b>真实账簿状态</b> (含结转分录) —— 已结账期间的 6xxx 科目就<b>应该</b>显示被结转清零后的
     * 期末余额, 4103 就<b>应该</b>显示结转转入的留存收益。排除结转凭证会人为"解除"已完成的结账,
     * 使报表与真实账簿脱节。({@link #aggregateBySubjectPosted} 的结转排除逻辑是
     * {@code ProfitLossClosingServiceImpl} 专用 — 它要计算"本期业务发生额、不含结转噪音"以生成
     * 下一张结转凭证, 语义完全不同, 不应被本报表复用。)
     *
     * <p>用途: {@code BalanceSheetService}, {@code VoucherExportServiceImpl} 的
     * exportSubjectBalance / exportGeneralLedger / exportTrialBalance / loadOpeningAggregates /
     * exportSubsidiaryLedger (经 loadVoucherLines excludeDraft=true)。
     */
    @Query("SELECT new com.cretas.aims.dto.finance.SubjectAggregateRow(" +
            "  e.subjectCode, MAX(e.subjectName), " +
            "  COALESCE(SUM(e.debit), 0), COALESCE(SUM(e.credit), 0), " +
            "  COUNT(e)) " +
            "FROM VoucherEntry e JOIN e.voucher v " +
            "WHERE v.factoryId = :factoryId " +
            "  AND v.voucherDate BETWEEN :startDate AND :endDate " +
            "  AND v.status <> com.cretas.aims.entity.enums.VoucherStatus.VOID " +
            "  AND v.status <> com.cretas.aims.entity.enums.VoucherStatus.DRAFT " +
            "  AND v.deletedAt IS NULL " +
            "GROUP BY e.subjectCode " +
            "ORDER BY e.subjectCode ASC")
    List<com.cretas.aims.dto.finance.SubjectAggregateRow> aggregateBySubjectExcludingDraft(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * F006 财务审计 Bug 6 (2026-07-04): 利润表 (Income Statement) 专用 — 排除 DRAFT (未财审草稿)
     * + 排除结转产物 (PL_CLOSING / COST_CARRYOVER 红冲镜像), 但<b>保留 REVERSED</b> (与
     * {@link #aggregateBySubjectExcludingDraft} 同理, 见其 javadoc 关于红字冲销的说明)。
     *
     * <p>为什么利润表必须排除结转产物: 若不排除, 已结账期间的 6xxx 科目在聚合里会被结转分录
     * 清零抵消 → 利润表对一个<b>已结账</b>的期间会显示营业收入=0/成本=0 (荒谬 —— 利润表要展示的
     * 是"本期真实发生的业务活动", 与是否已完成结账机械动作无关)。此排除逻辑与
     * {@link #aggregateBySubjectPosted} (结转损益专用) 完全一致 —— 二者本质计算同一个量
     * ("本期业务口径损益, 不含结转噪音") —— 唯一区别是本方法<b>保留 REVERSED</b> 以支持红字冲销
     * 正确抵消 (见上)。
     *
     * <p>用途: {@code IncomeStatementService}, {@code VoucherExportServiceImpl.exportIncomeStatement}。
     */
    @Query("SELECT new com.cretas.aims.dto.finance.SubjectAggregateRow(" +
            "  e.subjectCode, MAX(e.subjectName), " +
            "  COALESCE(SUM(e.debit), 0), COALESCE(SUM(e.credit), 0), " +
            "  COUNT(e)) " +
            "FROM VoucherEntry e JOIN e.voucher v " +
            "WHERE v.factoryId = :factoryId " +
            "  AND v.voucherDate BETWEEN :startDate AND :endDate " +
            "  AND v.status <> com.cretas.aims.entity.enums.VoucherStatus.VOID " +
            "  AND v.status <> com.cretas.aims.entity.enums.VoucherStatus.DRAFT " +
            "  AND v.voucherType <> com.cretas.aims.entity.enums.VoucherType.PL_CLOSING " +
            "  AND NOT (v.voucherType = com.cretas.aims.entity.enums.VoucherType.COST_CARRYOVER " +
            "           AND v.originalVoucherId IS NOT NULL) " +
            "  AND v.deletedAt IS NULL " +
            "GROUP BY e.subjectCode " +
            "ORDER BY e.subjectCode ASC")
    List<com.cretas.aims.dto.finance.SubjectAggregateRow> aggregateBySubjectExcludingDraftAndClosing(
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
}
