package com.cretas.aims.service.finance;

import com.cretas.aims.entity.finance.AccountingPeriod;

import java.util.List;
import java.util.Optional;

/**
 * Sprint 7 T2 F-PERIOD: 期间结账状态机服务.
 *
 * <p>5 个 transition + 1 查询:
 * <ul>
 *   <li>{@link #openPeriod}: 创建 OPEN 状态 row (幂等)</li>
 *   <li>{@link #requestClose}: OPEN → PENDING_CLOSE + 触发审批工作流</li>
 *   <li>{@link #confirmClose}: PENDING_CLOSE → CLOSED, voucher 写 gate 生效</li>
 *   <li>{@link #reopenPeriod}: CLOSED → OPEN, 含审计原因</li>
 *   <li>{@link #getStatus}: 查询 (无 row 默认 OPEN, backwards compat)</li>
 * </ul>
 *
 * @since 2026-05-20
 */
public interface AccountingPeriodService {

    /**
     * 打开期间. 幂等 — 已有 OPEN row 直接返回; 已 CLOSED 抛错 (应先 reopen).
     *
     * @return 创建或已存在的 OPEN 状态 period
     */
    AccountingPeriod openPeriod(String factoryId, Integer year, Integer month, Long userId);

    /**
     * 发起结账请求 (财务 → finance director 审批).
     *
     * <p>OPEN → PENDING_CLOSE. 触发 BUDGET_APPROVAL workflow (workflow 未配置时 fail-open
     * 直接进 PENDING_CLOSE — 等 confirmClose 手工推进).
     *
     * @return PENDING_CLOSE 状态 period
     */
    AccountingPeriod requestClose(String factoryId, Integer year, Integer month, Long userId);

    /**
     * 确认结账. PENDING_CLOSE → CLOSED. voucher 写 gate 立即生效.
     *
     * <p>调用者必须有 finance_manager / factory_super_admin role
     * (Controller 层 @RequirePermission 守门).
     *
     * @return CLOSED 状态 period
     */
    AccountingPeriod confirmClose(String factoryId, Integer year, Integer month, Long userId);

    /**
     * 反结账. CLOSED → OPEN. 必须填原因 (audit log).
     *
     * <p>调用者必须有 finance_manager / factory_super_admin role.
     *
     * @param reason 反结账原因, 不可为空
     * @return OPEN 状态 period (含 reopen audit fields)
     */
    AccountingPeriod reopenPeriod(String factoryId, Integer year, Integer month,
                                  String reason, Long userId);

    /**
     * 手工立即锁定结转: 把 CLOSED 期间 adjustDeadline 设为 now (强制 LOCKED) 并立即结转损益。
     * 财务确认无后续调整时用, 不必等 20 天窗口。需 finance 权限 (Controller 守门)。幂等 (已结转直接返回)。
     */
    AccountingPeriod forceLockAndClose(String factoryId, Integer year, Integer month, Long userId);

    /**
     * 查询期间状态. 无 row 时返回 OPEN (backwards compat — legacy factory 默认不被 gate).
     *
     * @return 该 period 的状态. 总返非 null.
     */
    AccountingPeriod.Status getStatus(String factoryId, Integer year, Integer month);

    /**
     * 查询期间实体 (含全部 audit 字段). 无 row 时返 empty.
     */
    Optional<AccountingPeriod> findPeriod(String factoryId, Integer year, Integer month);

    /**
     * 列出 factory 内全部 period (按年月 DESC). admin UI 用.
     */
    List<AccountingPeriod> listByFactory(String factoryId);

    /**
     * 声明: 若 (factoryId, year, month) 已 CLOSED, 抛 {@link com.cretas.aims.exception.PeriodClosedException}.
     * 否则 silently pass. 用于 VoucherService 写操作 gate.
     */
    void assertOpen(String factoryId, Integer year, Integer month);
}
