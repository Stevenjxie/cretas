package com.cretas.aims.repository;

import com.cretas.aims.entity.LabelQcPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LabelQcPhotoRepository extends JpaRepository<LabelQcPhoto, String> {
    List<LabelQcPhoto> findByFactoryIdAndTaskIdOrderByOrderIndexAsc(String factoryId, String taskId);

    Optional<LabelQcPhoto> findByFactoryIdAndId(String factoryId, String id);

    Optional<LabelQcPhoto> findByFactoryIdAndTaskIdAndAttachmentId(
            String factoryId, String taskId, String attachmentId);

    boolean existsByFactoryIdAndTaskIdAndOrderIndex(String factoryId, String taskId, Integer orderIndex);
}
