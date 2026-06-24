package com.cretas.aims.repository;

import com.cretas.aims.entity.processentry.ProcessSheetRowChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * SP-G P3: 逐工序电子表格行级操作记录 repository。
 *
 * <p>行级时间线读取: 按 (factory, plan, processCode, clientRowId) 定位某一行的全部变更，
 * 按创建时间倒序 (最新在前)。查询天然 factory-scoped (🔒)。
 */
@Repository
public interface ProcessSheetRowChangeLogRepository
        extends JpaRepository<ProcessSheetRowChangeLog, Long> {

    List<ProcessSheetRowChangeLog>
        findByFactoryIdAndPlanIdAndProcessCodeAndClientRowIdOrderByCreatedAtDesc(
            String factoryId, String planId, String processCode, String clientRowId);
}
