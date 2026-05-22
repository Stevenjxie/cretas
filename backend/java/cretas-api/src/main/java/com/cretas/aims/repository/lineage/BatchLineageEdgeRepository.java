package com.cretas.aims.repository.lineage;

import com.cretas.aims.entity.lineage.BatchLineageEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 批次溯源有向边 Repository — forensic 一旦写入不删。
 */
@Repository
public interface BatchLineageEdgeRepository extends JpaRepository<BatchLineageEdge, String> {

    List<BatchLineageEdge> findByFactoryIdAndSourceIdAndSourceType(
            String factoryId, String sourceId, String sourceType);

    List<BatchLineageEdge> findByFactoryIdAndTargetIdAndTargetType(
            String factoryId, String targetId, String targetType);

    List<BatchLineageEdge> findByFactoryId(String factoryId);
}
