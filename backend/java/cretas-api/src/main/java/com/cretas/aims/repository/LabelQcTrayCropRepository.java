package com.cretas.aims.repository;

import com.cretas.aims.entity.LabelQcTrayCrop;
import com.cretas.aims.entity.enums.LabelQcTrayCropStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LabelQcTrayCropRepository extends JpaRepository<LabelQcTrayCrop, String> {
    Optional<LabelQcTrayCrop> findByFactoryIdAndCropSpecSha256(String factoryId, String cropSpecSha256);
    Optional<LabelQcTrayCrop> findByFactoryIdAndId(String factoryId, String id);
    Page<LabelQcTrayCrop> findByFactoryIdAndStatusOrderByCreatedAtAsc(
            String factoryId, LabelQcTrayCropStatus status, Pageable pageable);
    Page<LabelQcTrayCrop> findByFactoryIdOrderByCreatedAtAsc(String factoryId, Pageable pageable);
}
