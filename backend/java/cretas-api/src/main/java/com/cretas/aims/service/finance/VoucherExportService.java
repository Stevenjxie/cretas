package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.VoucherExportRequestDTO;
import com.cretas.aims.entity.enums.VoucherTargetSystem;

import java.io.OutputStream;

/**
 * SP11: 凭证导出服务.
 *
 * <p>支持两种导出格式:
 * <ol>
 *   <li>凭证序时账 (Sequential Ledger) — 按日期排列的全凭证明细, 适合金蝶/用友导入</li>
 *   <li>科目余额表 (Subject Balance) — 汇总各科目期初/借方/贷方/期末余额</li>
 * </ol>
 *
 * <p>规则: 同工厂 + 同类型 + 同期间 5 分钟内重复请求返回已有记录 (Rule-4 幂等防重复).
 */
public interface VoucherExportService {

    /**
     * 导出凭证序时账 (写入 out 流, 并记录 VoucherExportRecord).
     *
     * @param factoryId 工厂 ID
     * @param req       导出请求 (时间范围 + 目标系统)
     * @param userId    操作人 ID
     * @param out       输出流 (xlsx binary)
     * @return 文件名 (含扩展名)
     */
    String exportSequentialLedger(String factoryId, VoucherExportRequestDTO req,
                                   Long userId, OutputStream out) throws Exception;

    /**
     * 导出科目余额表 (写入 out 流).
     *
     * @return 文件名
     */
    String exportSubjectBalance(String factoryId, VoucherExportRequestDTO req,
                                 Long userId, OutputStream out) throws Exception;

    String exportChronologicalLedger(String factoryId, VoucherExportRequestDTO req,
                                     Long userId, OutputStream out) throws Exception;

    String exportGeneralLedger(String factoryId, VoucherExportRequestDTO req,
                               Long userId, OutputStream out) throws Exception;

    String exportSubsidiaryLedger(String factoryId, VoucherExportRequestDTO req,
                                  Long userId, OutputStream out) throws Exception;

    String exportTrialBalance(String factoryId, VoucherExportRequestDTO req,
                              Long userId, OutputStream out) throws Exception;

    String exportIncomeStatement(String factoryId, VoucherExportRequestDTO req,
                                 Long userId, OutputStream out) throws Exception;

    String exportQuantityAmountLedger(String factoryId, VoucherExportRequestDTO req,
                                      Long userId, OutputStream out) throws Exception;
}
