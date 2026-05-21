package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository — Sprint 8 P3 Phase A {@link HaccpCheckpoint}.
 *
 * <p>BaseEntity 软删除 via @Where 自动过滤 deleted_at IS NULL.
 */
@Repository
public interface HaccpCheckpointRepository extends JpaRepository<HaccpCheckpoint, Long> {

    /** 查 factory 全部启用的 CCP. */
    List<HaccpCheckpoint> findByFactoryIdAndActiveTrue(String factoryId);

    /** 按编码查找 (factory 内唯一). */
    Optional<HaccpCheckpoint> findByFactoryIdAndCheckpointCode(String factoryId, String checkpointCode);
}
