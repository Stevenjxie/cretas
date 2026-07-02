package com.cretas.aims.repository;

import com.cretas.aims.entity.FactorySettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;
/**
 * 工厂设置数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface FactorySettingsRepository extends JpaRepository<FactorySettings, Integer> {
    /**
     * 根据工厂ID查找设置
     */
    Optional<FactorySettings> findByFactoryId(String factoryId);
     /**
     * 检查工厂设置是否存在
      */
    boolean existsByFactoryId(String factoryId);
     /**
     * 获取AI设置
      */
    @Query("SELECT f.aiSettings FROM FactorySettings f WHERE f.factoryId = :factoryId")
    String findAiSettingsByFactoryId(@Param("factoryId") String factoryId);
     /**
     * 获取通知设置
      */
    @Query("SELECT f.notificationSettings FROM FactorySettings f WHERE f.factoryId = :factoryId")
    String findNotificationSettingsByFactoryId(@Param("factoryId") String factoryId);
     /**
     * 获取生产设置
      */
    @Query("SELECT f.productionSettings FROM FactorySettings f WHERE f.factoryId = :factoryId")
    String findProductionSettingsByFactoryId(@Param("factoryId") String factoryId);
     /**
     * 获取库存设置
      */
    @Query("SELECT f.inventorySettings FROM FactorySettings f WHERE f.factoryId = :factoryId")
    String findInventorySettingsByFactoryId(@Param("factoryId") String factoryId);
     /**
     * 获取工作时间设置
      */
    @Query("SELECT f.workTimeSettings FROM FactorySettings f WHERE f.factoryId = :factoryId")
    String findWorkTimeSettingsByFactoryId(@Param("factoryId") String factoryId);
     /**
     * 获取工厂级"免工序报工默认值" (Fable 审计修复 — 多租户安全, V20261018_02).
     * 无 settings 行时返回 null (调用方兜底为 false = 逐道, 安全默认).
      */
    @Query("SELECT f.skipProcessReportingDefault FROM FactorySettings f WHERE f.factoryId = :factoryId")
    Boolean findSkipProcessReportingDefaultByFactoryId(@Param("factoryId") String factoryId);
     /**
     * 获取工厂级"报工前必须领料确认"开关 (② Part B 生产领料单 Gate, V20261027_32).
     * 无 settings 行时返回 null (调用方兜底为 false = 报工照旧, 向后兼容安全默认).
      */
    @Query("SELECT f.requireRequisitionBeforeReport FROM FactorySettings f WHERE f.factoryId = :factoryId")
    Boolean findRequireRequisitionBeforeReportByFactoryId(@Param("factoryId") String factoryId);
     /**
     * 更新AI每周配额（仅平台管理员可用）
      */
    @Query("UPDATE FactorySettings f SET f.aiWeeklyQuota = :quota WHERE f.factoryId = :factoryId")
    void updateAiWeeklyQuota(@Param("factoryId") String factoryId, @Param("quota") Integer quota);
}
