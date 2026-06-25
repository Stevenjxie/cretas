package com.cretas.aims.repository;

import com.cretas.aims.entity.processentry.ProcessSheetRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * SP-F Task 1.1: ProcessSheetRow repository.
 *
 * <p>主键方法 findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId 对应 uk_sheet_row 唯一约束,
 * 供后续 upsert 端点使用。
 */
public interface ProcessSheetRowRepository extends JpaRepository<ProcessSheetRow, Long> {

    Optional<ProcessSheetRow> findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
            String factoryId, String planId, String processCode, String clientRowId);

    List<ProcessSheetRow> findByFactoryIdAndPlanIdAndProcessCode(
            String factoryId, String planId, String processCode);

    /**
     * SP-F role-mode fix: 双键查询 (factory, plan, processCode, processOrder)。
     *
     * <p>role-mode 下多道普通工序共享同一 archetype processCode (如 'chaoshui'),
     * 仅 processCode 过滤会把多道的行/库存混在一起。加 processOrder (链内唯一) 后
     * 每道分别隔离。仅当 processOrder 非空时由 service 调用; 旧客户端不传 processOrder
     * 时回退到 code-only finder (向后兼容)。
     */
    List<ProcessSheetRow> findByFactoryIdAndPlanIdAndProcessCodeAndProcessOrder(
            String factoryId, String planId, String processCode, Integer processOrder);

    List<ProcessSheetRow> findByFactoryIdAndPlanId(
            String factoryId, String planId);

    /**
     * SP-F Task 1.8: 按 (factory, plan, clientRowId) 查行 —— delete 端点路径不含 processCode。
     * 正常情况下 clientRowId 在同一 plan 内跨工序不重复，返回 1 条；边缘情形返多条则全删。
     */
    List<ProcessSheetRow> findByFactoryIdAndPlanIdAndClientRowId(
            String factoryId, String planId, String clientRowId);
}
