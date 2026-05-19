package com.cretas.aims.repository.notify;

import com.cretas.aims.entity.notify.NotifyTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link NotifyTemplate} — Phase 3 Canvas-Notify Step T3.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Repository
public interface NotifyTemplateRepository extends JpaRepository<NotifyTemplate, UUID> {

    /** Find template by factory + code. Used by NotifySender when resolving templateCode. */
    Optional<NotifyTemplate> findByFactoryIdAndTemplateCode(String factoryId, String templateCode);

    /** List all templates for a factory (Canvas Tab 通知模板 list view). */
    List<NotifyTemplate> findByFactoryId(String factoryId);
}
