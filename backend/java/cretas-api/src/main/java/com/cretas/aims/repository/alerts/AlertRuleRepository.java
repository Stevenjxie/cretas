package com.cretas.aims.repository.alerts;

import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AlertRule 数据访问 — Phase 2 Canvas-Alerts skeleton.
 *
 * <p>Hibernate {@code @Where(deleted_at IS NULL)} 由 entity 层提供, 所有
 * find* 方法天然过滤软删除记录.
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRule, UUID> {

    /** 列出工厂启用的全部规则 — scheduler / listener 主用. */
    List<AlertRule> findByFactoryIdAndEnabledTrue(String factoryId);

    /** 按 type 过滤启用规则 — listener 路由用. */
    List<AlertRule> findByFactoryIdAndAlertTypeAndEnabledTrue(String factoryId, AlertType alertType);

    /** 唯一约束 check — controller create / update 用. */
    Optional<AlertRule> findByFactoryIdAndRuleName(String factoryId, String ruleName);

    /** 列工厂全部规则 (含 disabled), UI 管理列表用. */
    List<AlertRule> findByFactoryId(String factoryId);
}
