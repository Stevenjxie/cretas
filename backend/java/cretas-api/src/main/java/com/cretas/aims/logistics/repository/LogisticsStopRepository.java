package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LogisticsStopRepository extends JpaRepository<LogisticsStop, String> {

    List<LogisticsStop> findByTripIdAndDeletedAtIsNullOrderBySequenceNo(String tripId);

    Optional<LogisticsStop> findByDeliveryOrderIdAndDeletedAtIsNull(String deliveryOrderId);
}
