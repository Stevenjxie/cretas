package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductWorkProcess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductWorkProcessRepository extends JpaRepository<ProductWorkProcess, Long> {

    List<ProductWorkProcess> findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(
            String factoryId, String productTypeId);

    Optional<ProductWorkProcess> findByFactoryIdAndProductTypeIdAndProcessOrder(
            String factoryId, String productTypeId, Integer processOrder);

    Optional<ProductWorkProcess> findByFactoryIdAndId(String factoryId, Long id);

    boolean existsByFactoryIdAndProductTypeIdAndWorkProcessId(
            String factoryId, String productTypeId, String workProcessId);

    void deleteByFactoryIdAndProductTypeIdAndWorkProcessId(
            String factoryId, String productTypeId, String workProcessId);

    List<ProductWorkProcess> findByFactoryIdAndWorkProcessId(String factoryId, String workProcessId);
}
