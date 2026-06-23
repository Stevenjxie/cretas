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
}
