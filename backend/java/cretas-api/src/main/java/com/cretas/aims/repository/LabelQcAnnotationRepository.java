package com.cretas.aims.repository;

import com.cretas.aims.entity.LabelQcAnnotation;
import com.cretas.aims.entity.enums.LabelQcAnnotationSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LabelQcAnnotationRepository extends JpaRepository<LabelQcAnnotation, String> {
    List<LabelQcAnnotation> findByFactoryIdAndTaskIdOrderByCreatedAtAsc(String factoryId, String taskId);

    List<LabelQcAnnotation> findByFactoryIdAndPhotoIdOrderByCreatedAtAsc(String factoryId, String photoId);

    Optional<LabelQcAnnotation> findByFactoryIdAndPhotoIdAndId(
            String factoryId, String photoId, String id);

    long countByFactoryIdAndTaskIdAndSource(
            String factoryId, String taskId, LabelQcAnnotationSource source);

    void deleteByFactoryIdAndTaskIdAndSource(
            String factoryId, String taskId, LabelQcAnnotationSource source);
}
