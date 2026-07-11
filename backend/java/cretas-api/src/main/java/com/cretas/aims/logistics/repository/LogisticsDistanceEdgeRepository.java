package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsDistanceEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LogisticsDistanceEdgeRepository extends JpaRepository<LogisticsDistanceEdge, String> {

    Optional<LogisticsDistanceEdge> findByFactoryIdAndFromPointIdAndToPointIdAndDeletedAtIsNull(
            String factoryId, String fromPointId, String toPointId);
}
