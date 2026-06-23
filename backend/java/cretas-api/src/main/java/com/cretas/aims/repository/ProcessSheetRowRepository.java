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

    List<ProcessSheetRow> findByFactoryIdAndPlanId(
            String factoryId, String planId);

    /**
     * SP-F Task 1.8: 按 (factory, plan, clientRowId) 查行 —— delete 端点路径不含 processCode。
     * 正常情况下 clientRowId 在同一 plan 内跨工序不重复，返回 1 条；边缘情形返多条则全删。
     */
    List<ProcessSheetRow> findByFactoryIdAndPlanIdAndClientRowId(
            String factoryId, String planId, String clientRowId);
}
